/*
 * Project: Combustion Inc. Android Framework
 * File: LogManager.kt
 * Author: https://github.com/miwright2
 *
 * MIT License
 *
 * Copyright (c) 2022. Combustion Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package inc.combustion.framework.log

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.SingletonHolder
import inc.combustion.framework.ble.BleManager
import inc.combustion.framework.ble.GaugeManager
import inc.combustion.framework.ble.GaugeStatus
import inc.combustion.framework.ble.ProbeManager
import inc.combustion.framework.ble.ProbeStatus
import inc.combustion.framework.service.DebugSettings
import inc.combustion.framework.service.DeviceManager
import inc.combustion.framework.service.LoggedDataPoint
import inc.combustion.framework.service.LoggedGaugeDataPoint
import inc.combustion.framework.service.LoggedProbeDataPoint
import inc.combustion.framework.service.ProbeMode
import inc.combustion.framework.service.ProbeUploadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Date
import java.util.SortedMap
import kotlin.math.ceil

/**
 * Singleton class for manage the service log buffer and record upload from probes.
 */
internal class LogManager {
    private val probes = hashMapOf<String, ProbeManager>()
    private val gauges = hashMapOf<String, GaugeManager>()
    private val probeTemperatureLogs: SortedMap<String, ProbeTemperatureLog> = sortedMapOf()
    private val gaugeTemperatureLogs: SortedMap<String, GaugeTemperatureLog> = sortedMapOf()

    companion object : SingletonHolder<LogManager>({ LogManager() })

    private fun temperatureLog(serialNumber: String): TemperatureLog<out LoggedDataPoint>? =
        probeTemperatureLogs[serialNumber] ?: gaugeTemperatureLogs[serialNumber]

    fun manageProbe(owner: LifecycleOwner, probeManager: ProbeManager) {
        if (probes.containsKey(probeManager.serialNumber)) return

        // add legacyProbeManager
        probes[probeManager.serialNumber] = probeManager

        // create new temperature log for legacyProbeManager and track it
        if (!probeTemperatureLogs.containsKey(probeManager.serialNumber)) {
            probeTemperatureLogs[probeManager.serialNumber] =
                ProbeTemperatureLog(probeManager.serialNumber)
        }

        // maintain reference to log for further processing
        val temperatureLog = probeTemperatureLogs.getValue(probeManager.serialNumber)

        manageDevice(
            owner,
            probeManager,
            temperatureLog,
        )
    }

    private fun manageDevice(
        owner: LifecycleOwner,
        manager: BleManager,
        temperatureLog: TemperatureLog<*>,
    ) {
        manager.logTransferCompleteCallback = {
            temperatureLog.expectFutureLogRequest()
        }

        // monitor this device's status flow
        manager.addJob(
            serialNumber = manager.serialNumber,
            job = owner.lifecycleScope.launch(
                CoroutineName("${manager.serialNumber}.LogManager.probeStatusFlow")
            ) {
                owner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                    manager.normalModeStatusFlow
                        .onCompletion {
                            Log.d(LOG_TAG, "Gauge Status Flow Complete")
                        }
                        .catch {
                            Log.w(LOG_TAG, "Gauge Status Flow Catch: $it")
                        }
                        .collect { deviceStatus ->
                            // LogManager only operates on Normal mode status updates, only
                            // Normal Mode packets should be published to the flow.  Defensive
                            // check and log message.
                            if (deviceStatus.mode != ProbeMode.NORMAL) {
                                Log.w(LOG_TAG, "Non-Normal mode packet collected on flow!")
                                return@collect
                            }

                            when (manager.uploadState) {
                                // if upload state is currently unavailable, and we now have a
                                // ProbeStatus, then we have everything we need to start an upload.
                                //
                                // event the new upload state to the client, and they can initiate
                                // the record transfer when they are ready.
                                ProbeUploadState.Unavailable -> {
                                    if (DeviceManager.instance.settings.autoLogTransfer) {
                                        manager.sessionInfo?.let {
                                            // note, this will transfer to ProbeUploadInProgress
                                            requestLogsFromDevice(
                                                serialNumber = manager.serialNumber,
                                                minSequenceNumber = deviceStatus.minSequenceNumber,
                                                maxSequenceNumber = deviceStatus.maxSequenceNumber,
                                            )
                                        }
                                    } else {
                                        manager.uploadState =
                                            ProbeUploadState.ProbeUploadNeeded
                                    }
                                }

                                ProbeUploadState.ProbeUploadNeeded -> {
                                    // else, do nothing, wait for the client to request the transfer
                                }

                                is ProbeUploadState.ProbeUploadInProgress -> {
                                    // add device status data points to the log while the upload
                                    // is in progress.  don't event the status, because we will
                                    // do that below in processing the log response.
                                    temperatureLog.addFromDeviceStatus(deviceStatus)

                                    // update the count of records downlåoaded
                                    manager.recordsDownloaded =
                                        temperatureLog.dataPointCount

                                    // complete the request, and change the state to complete
                                    if (temperatureLog.logRequestIsComplete()) {
                                        val sessionStatus = temperatureLog.completeLogRequest()
                                        manager.uploadState =
                                            sessionStatus.toProbeUploadState()
                                    }

                                }

                                is ProbeUploadState.ProbeUploadComplete -> {
                                    if (DebugSettings.DEBUG_LOG_LOG_MANAGER_IO) {
                                        Log.d(
                                            LOG_TAG,
                                            "STATUS-RX : ${deviceStatus.maxSequenceNumber}"
                                        )
                                    }

                                    // add the device status to the temperature log
                                    val sessionStatus =
                                        temperatureLog.addFromDeviceStatus(deviceStatus)

                                    // update the count of records downloaded
                                    manager.recordsDownloaded =
                                        temperatureLog.dataPointCount

                                    // if we have any dropped records then initiate a log request to
                                    // backfill from the missing records.
                                    val missingRecords = temperatureLog.missingRecordRange(
                                        deviceStatus.minSequenceNumber,
                                        deviceStatus.maxSequenceNumber
                                    )
                                    if (missingRecords != null) {
                                        startBackfillLogRequest(
                                            temperatureLog,
                                            manager,
                                            missingRecords,
                                            deviceStatus.maxSequenceNumber
                                        )
                                    }
                                    // we've synchronized the log on the device here, now maintain
                                    // the log with the data points from the device status message.
                                    // and report out the progress as part of the complete state.
                                    else {
                                        manager.uploadState =
                                            sessionStatus.toProbeUploadState()
                                    }
                                }
                            }

                            // calculate the percent of the logs on the probe that have made it
                            // over to the app.
                            temperatureLog.missingRecordsForRange(
                                deviceStatus.minSequenceNumber, deviceStatus.maxSequenceNumber
                            )?.let {
                                if (deviceStatus.maxSequenceNumber > deviceStatus.minSequenceNumber) {
                                    val total =
                                        (deviceStatus.maxSequenceNumber - deviceStatus.minSequenceNumber + 1u).toDouble()
                                    val have = total - it.toDouble()
                                    manager.logUploadPercent =
                                        ceil((have / total) * 100).toUInt()
                                }
                            } ?: run {
                                temperatureLog.currentSessionStartTime?.let {
                                    manager.logUploadPercent = 100u
                                } ?: run {
                                    manager.logUploadPercent = 0u
                                }
                            }

                        }
                }
            })
        // monitor this probes log response flow
        manager.addJob(
            serialNumber = manager.serialNumber,
            job = owner.lifecycleScope.launch(
                CoroutineName("${manager.serialNumber}.LogManager.logResponseFlow")
            ) {
                owner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                    manager.logResponseFlow
                        .onCompletion {
                            Log.d(LOG_TAG, "Log Response Flow Complete")
                        }
                        .catch {
                            Log.w(LOG_TAG, "Log Response Flow Catch: $it")
                        }
                        .collect { response ->

                            // debug log BLE IO if enabled.
                            if (DebugSettings.DEBUG_LOG_LOG_MANAGER_IO) {
                                Log.d(LOG_TAG, "LOG-RX    : ${response.sequenceNumber}")
                            }

                            // Do not store the data point if its sequence number is greater than
                            // the probe's max sequence number. This is a safety check for the
                            // probe/node sending a record with an invalid sequence number.
                            manager.maxSequenceNumber?.let {
                                if (response.sequenceNumber <= it) {
                                    // add the response to the temperature log and set upload
                                    // progress
                                    val uploadProgress =
                                        temperatureLog.addFromLogResponse(response)

                                    // update the count of records downloaded
                                    manager.recordsDownloaded =
                                        temperatureLog.dataPointCount

                                    // update ProbeManager state
                                    manager.uploadState =
                                        uploadProgress.toProbeUploadState()
                                } else {
                                    Log.e(
                                        LOG_TAG,
                                        "Discarding invalid log response: ${response.sequenceNumber}"
                                    )
                                }
                            }
                        }
                }
            }
        )
    }

    fun manageGauge(owner: LifecycleOwner, gaugeManager: GaugeManager) {
        if (gauges.containsKey(gaugeManager.serialNumber)) return
        gauges[gaugeManager.serialNumber] = gaugeManager

        // create new temperature log for legacyProbeManager and track it
        if (!gaugeTemperatureLogs.containsKey(gaugeManager.serialNumber)) {
            gaugeTemperatureLogs[gaugeManager.serialNumber] =
                GaugeTemperatureLog(gaugeManager.serialNumber)
        }

        // maintain reference to log for further processing
        val temperatureLog = gaugeTemperatureLogs.getValue(gaugeManager.serialNumber)
        manageDevice(
            owner,
            gaugeManager,
            temperatureLog,
        )
    }

    fun finish(serialNumber: String) {
        probes.remove(serialNumber)
        gauges.remove(serialNumber)
        probeTemperatureLogs.remove(serialNumber)
        gaugeTemperatureLogs.remove(serialNumber)
    }

    fun clear() {
        probes.clear()
        gauges.clear()
        probeTemperatureLogs.clear()
        gaugeTemperatureLogs.clear()
    }

    /**
     * Starts a log request for [serialNumber]. If [minSequenceNumber] or [maxSequenceNumber] are
     * non-null, they will be used to set up the initial internal session parameters. If they're
     * null, cached sequence numbers are used.
     */
    fun requestLogsFromDevice(
        serialNumber: String,
        minSequenceNumber: UInt? = null,
        maxSequenceNumber: UInt? = null,
    ) {
        val deviceManager = probes[serialNumber] ?: gauges[serialNumber] ?: return
        val log = when (deviceManager) {
            is ProbeManager -> probeTemperatureLogs[serialNumber]
            is GaugeManager -> gaugeTemperatureLogs[serialNumber]
            else -> null
        } ?: return
        val sessionInfo = deviceManager.sessionInfo ?: return

        // prepare log for the request and determine the needed range
        val range = log.prepareForLogRequest(
            minSequenceNumber ?: deviceManager.minSequenceNumber ?: 0u,
            maxSequenceNumber ?: deviceManager.maxSequenceNumber ?: 0u,
            sessionInfo
        )

        // if for some reason there isn't anything to request,
        // log a message and return
        if (range.size == 0u) {
            if (DebugSettings.DEBUG_LOG_TRANSFER) {
                Log.w(LOG_TAG, "No need to request logs from device")
            }
            return
        }

        // initialize the start of the log request with the temperature log
        val progress = log.startLogRequest(range)

        // update the probe's upload state with the progress.
        deviceManager.uploadState = progress.toProbeUploadState()

        Log.i(
            LOG_TAG,
            "Requesting Logs[$serialNumber]: ${range.minSeq} to ${range.maxSeq} (${log.currentSessionId})"
        )

        // send the request to the device to start the upload
        deviceManager.sendLogRequest(range.minSeq, range.maxSeq)

        // processing the resulting LogRequest flow happens in the coroutine above.
    }

    fun recordsDownloaded(serialNumber: String): Int {
        return temperatureLog(serialNumber)?.dataPointCount ?: 0
    }

    fun logStartTimestampForDevice(serialNumber: String): Date {
        return temperatureLog(serialNumber)?.logStartTime ?: Date()
    }

    fun exportLogsForDevice(serialNumber: String): List<LoggedDataPoint>? {
        return temperatureLog(serialNumber)?.dataPoints
    }

    fun exportLogsForProbe(serialNumber: String): List<LoggedProbeDataPoint>? {
        return probeTemperatureLogs[serialNumber]?.dataPoints
    }

    fun exportLogsForGauge(serialNumber: String): List<LoggedGaugeDataPoint>? {
        return gaugeTemperatureLogs[serialNumber]?.dataPoints
    }

    fun exportLogsForDeviceBySession(serialNumber: String): List<List<LoggedDataPoint>>? {
        return temperatureLog(serialNumber)?.dataPointsBySession
    }

    fun createLogFlowForDevice(
        serialNumber: String,
        includeHistory: Boolean = true
    ): Flow<LoggedDataPoint> {
        return flow {
            (probes[serialNumber] ?: gauges[serialNumber])?.let { manager ->

                // if the device isn't uploading or hasn't completed an upload, then
                // return out of this flow immediately, there is nothing to do.  the device
                // needs to connect, or the client needs to initiate the record transfer.
                if (manager.uploadState !is ProbeUploadState.ProbeUploadInProgress &&
                    manager.uploadState !is ProbeUploadState.ProbeUploadComplete
                ) {
                    return@flow
                }

                // wait for the upload to complete before emitting into the flow.
                while (manager.uploadState is ProbeUploadState.ProbeUploadInProgress &&
                    currentCoroutineContext().isActive
                ) {
                    delay(250)
                }

                // upload is complete, so get the logged data points stored on the phone and emit
                // them into the flow, in order.
                val log = probeTemperatureLogs[serialNumber]
                log?.let { temperatureLog ->
                    if (includeHistory) {
                        temperatureLog.dataPoints.forEach {
                            emit(it)
                        }
                    }

                    // collect the probe status updates as they come in, in order, and emit them
                    // into the flow for the consumer.  if we are no longer in an upload complete
                    // syncing state, then exit out of this flow.
                    val sessionId = log.currentSessionId
                    manager.normalModeStatusFlow.collect { deviceStatus ->
                        if (manager.uploadState !is ProbeUploadState.ProbeUploadInProgress &&
                            manager.uploadState !is ProbeUploadState.ProbeUploadComplete
                        ) {
                            throw CancellationException("Upload State is Now Invalid")
                        }

                        // only log normal mode packets
                        if (deviceStatus.mode != ProbeMode.NORMAL)
                            return@collect

                        emit(
                            when (deviceStatus) {
                                is GaugeStatus -> LoggedGaugeDataPoint.fromDeviceStatus(
                                    deviceStatus,
                                    log.currentSessionStartTime,
                                )

                                is ProbeStatus -> LoggedProbeDataPoint.fromDeviceStatus(
                                    sessionId,
                                    deviceStatus,
                                    log.currentSessionStartTime,
                                    log.currentSessionSamplePeriod,
                                )

                                else -> throw NotImplementedError("device status $deviceStatus is not implemented")
                            }
                        )
                    }
                }
            }
        }
    }

    private fun startBackfillLogRequest(
        log: TemperatureLog<*>,
        manager: BleManager,
        range: RecordRange,
        currentDeviceStatusSequence: UInt
    ) {
        // if for some reason there isn't anything to request,
        // log a message and return
        if (range.size == 0u) {
            if (DebugSettings.DEBUG_LOG_TRANSFER) {
                Log.w(LOG_TAG, "No need to backfill request logs from device $range")
            }
            return
        }

        // initialize the start of the log request with the temperature log
        val progress = log.startLogBackfillRequest(range, currentDeviceStatusSequence)

        // update the legacyProbeManager's upload state with the progress.
        manager.uploadState = progress.toProbeUploadState()

        Log.i(
            LOG_TAG,
            "Requesting Logs for Backfill[${manager.serialNumber}]: ${range.minSeq} to ${range.maxSeq} (${log.currentSessionId})"
        )

        // send the request to the device to start the upload
        manager.sendLogRequest(range.minSeq, range.maxSeq)

        // processing the resulting LogRequest flow happens in the coroutine above.
    }
}