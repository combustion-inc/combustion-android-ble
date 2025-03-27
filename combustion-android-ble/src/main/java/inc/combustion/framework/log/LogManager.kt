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
import inc.combustion.framework.ble.GaugeManager
import inc.combustion.framework.ble.ProbeManager
import inc.combustion.framework.service.DebugSettings
import inc.combustion.framework.service.DeviceManager
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
    private val temperatureLogs: SortedMap<String, ProbeTemperatureLog> = sortedMapOf()

    companion object : SingletonHolder<LogManager>({ LogManager() })

    fun manageProbe(owner: LifecycleOwner, probeManager: ProbeManager) {
        if (!probes.containsKey(probeManager.serialNumber)) {
            // add legacyProbeManager
            probes[probeManager.serialNumber] = probeManager

            // create new temperature log for legacyProbeManager and track it
            if (!temperatureLogs.containsKey(probeManager.serialNumber)) {
                temperatureLogs[probeManager.serialNumber] =
                    ProbeTemperatureLog(probeManager.serialNumber)
            }

            // maintain reference to log for further processing
            val temperatureLog = temperatureLogs.getValue(probeManager.serialNumber)

            probeManager.logTransferCompleteCallback = {
                temperatureLog?.expectFutureLogRequest()
            }

            // monitor this probes device status flow
            probeManager.addJob(
                serialNumber = probeManager.serialNumber,
                job = owner.lifecycleScope.launch(
                    CoroutineName("${probeManager.serialNumber}.LogManager.probeStatusFlow")
                ) {
                    owner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                        probeManager.normalModeProbeStatusFlow
                            .onCompletion {
                                Log.d(LOG_TAG, "Probe Status Flow Complete")
                            }
                            .catch {
                                Log.i(LOG_TAG, "Probe Status Flow Catch: $it")
                            }
                            .collect { deviceStatus ->
                                // LogManager only operates on Normal mode status updates, only
                                // Normal Mode packets should be published to the flow.  Defensive
                                // check and log message.
                                if (deviceStatus.mode != ProbeMode.NORMAL) {
                                    Log.w(LOG_TAG, "Non-Normal mode packet collected on flow!")
                                    return@collect
                                }

                                when (probeManager.uploadState) {
                                    // if upload state is currently unavailable, and we now have a
                                    // ProbeStatus, then we have everything we need to start an upload.
                                    //
                                    // event the new upload state to the client, and they can initiate
                                    // the record transfer when they are ready.
                                    ProbeUploadState.Unavailable -> {
                                        if (DeviceManager.instance.settings.autoLogTransfer) {
                                            probeManager.sessionInfo?.let {
                                                // note, this will transfer to ProbeUploadInProgress
                                                requestLogsFromDevice(
                                                    serialNumber = probeManager.serialNumber,
                                                    minSequenceNumber = deviceStatus.minSequenceNumber,
                                                    maxSequenceNumber = deviceStatus.maxSequenceNumber,
                                                )
                                            }
                                        } else {
                                            probeManager.uploadState =
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

                                        // update the count of records downloaded
                                        probeManager.recordsDownloaded =
                                            temperatureLog.dataPointCount

                                        // complete the request, and change the state to complete
                                        if (temperatureLog.logRequestIsComplete()) {
                                            val sessionStatus = temperatureLog.completeLogRequest()
                                            probeManager.uploadState =
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
                                        probeManager.recordsDownloaded =
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
                                                probeManager,
                                                missingRecords,
                                                deviceStatus.maxSequenceNumber
                                            )
                                        }
                                        // we've synchronized the log on the device here, now maintain
                                        // the log with the data points from the device status message.
                                        // and report out the progress as part of the complete state.
                                        else {
                                            probeManager.uploadState =
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
                                        probeManager.logUploadPercent =
                                            ceil((have / total) * 100).toUInt()
                                    }
                                } ?: run {
                                    temperatureLog.currentSessionStartTime?.let {
                                        probeManager.logUploadPercent = 100u
                                    } ?: run {
                                        probeManager.logUploadPercent = 0u
                                    }
                                }

                            }
                    }
                })
            // monitor this probes log response flow
            probeManager.addJob(
                serialNumber = probeManager.serialNumber,
                job = owner.lifecycleScope.launch(
                    CoroutineName("${probeManager.serialNumber}.LogManager.logResponseFlow")
                ) {
                    owner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                        probeManager.logResponseFlow
                            .onCompletion {
                                Log.d(LOG_TAG, "Log Response Flow Complete")
                            }
                            .catch {
                                Log.i(LOG_TAG, "Log Response Flow Catch: $it")
                            }
                            .collect { response ->

                                // debug log BLE IO if enabled.
                                if (DebugSettings.DEBUG_LOG_LOG_MANAGER_IO) {
                                    Log.d(LOG_TAG, "LOG-RX    : ${response.sequenceNumber}")
                                }

                                // Do not store the data point if its sequence number is greater than
                                // the probe's max sequence number. This is a safety check for the
                                // probe/node sending a record with an invalid sequence number.
                                probeManager.maxSequenceNumber?.let {
                                    if (response.sequenceNumber <= it) {
                                        // add the response to the temperature log and set upload
                                        // progress
                                        val uploadProgress =
                                            temperatureLog.addFromLogResponse(response)

                                        // update the count of records downloaded
                                        probeManager.recordsDownloaded =
                                            temperatureLog.dataPointCount

                                        // update ProbeManager state
                                        probeManager.uploadState =
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
    }

    fun manageGauge(owner: LifecycleOwner, gaugeManager: GaugeManager) {
        if (gauges.containsKey(gaugeManager.serialNumber)) return
        gauges[gaugeManager.serialNumber] = gaugeManager

        // TODO : implement
    }

    fun finish(serialNumber: String) {
        probes.remove(serialNumber)
        gauges.remove(serialNumber)
        temperatureLogs.remove(serialNumber)
    }

    fun clear() {
        probes.clear()
        gauges.clear()
        temperatureLogs.clear()
    }

    /**
     * Starts a log request for [serialNumber]. If [minSequenceNumber] or [maxSequenceNumber] are
     * non-null, they will be used to set up the initial internal session parameters. If they're
     * null, cached sequence numbers are used.
     */
    fun requestLogsFromDevice(
        serialNumber: String,
        minSequenceNumber: UInt? = null,
        maxSequenceNumber: UInt? = null
    ) {
        val probeManager = probes[serialNumber] ?: return
        val log = temperatureLogs[serialNumber] ?: return
        val sessionInfo = probeManager.sessionInfo ?: return

        // prepare log for the request and determine the needed range
        val range = log.prepareForLogRequest(
            minSequenceNumber ?: probeManager.minSequenceNumber ?: 0u,
            maxSequenceNumber ?: probeManager.maxSequenceNumber ?: 0u,
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
        probeManager.uploadState = progress.toProbeUploadState()

        Log.i(
            LOG_TAG,
            "Requesting Logs[$serialNumber]: ${range.minSeq} to ${range.maxSeq} (${log.currentSessionId})"
        )

        // send the request to the device to start the upload
        probeManager.sendLogRequest(range.minSeq, range.maxSeq)

        // processing the resulting LogRequest flow happens in the coroutine above.
    }

    fun recordsDownloaded(serialNumber: String): Int {
        return temperatureLogs[serialNumber]?.dataPointCount ?: 0
    }

    fun logStartTimestampForDevice(serialNumber: String): Date {
        return temperatureLogs[serialNumber]?.logStartTime ?: Date()
    }

    fun exportLogsForDevice(serialNumber: String): List<LoggedProbeDataPoint>? {
        return temperatureLogs[serialNumber]?.dataPoints
    }

    fun exportLogsForDeviceBySession(serialNumber: String): List<List<LoggedProbeDataPoint>>? {
        return temperatureLogs[serialNumber]?.dataPointsBySession
    }

    fun createLogFlowForDevice(
        serialNumber: String,
        includeHistory: Boolean = true
    ): Flow<LoggedProbeDataPoint> {
        return flow {
            probes[serialNumber]?.let { probeManager ->

                // if the device isn't uploading or hasn't completed an upload, then
                // return out of this flow immediately, there is nothing to do.  the device
                // needs to connect, or the client needs to initiate the record transfer.
                if (probeManager.uploadState !is ProbeUploadState.ProbeUploadInProgress &&
                    probeManager.uploadState !is ProbeUploadState.ProbeUploadComplete
                ) {
                    return@flow
                }

                // wait for the upload to complete before emitting into the flow.
                while (probeManager.uploadState is ProbeUploadState.ProbeUploadInProgress &&
                    currentCoroutineContext().isActive
                ) {
                    delay(250)
                }

                // upload is complete, so get the logged data points stored on the phone and emit
                // them into the flow, in order.
                val log = temperatureLogs[serialNumber]
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
                    probeManager.normalModeProbeStatusFlow
                        .collect { deviceStatus ->
                            if (probeManager.uploadState !is ProbeUploadState.ProbeUploadInProgress &&
                                probeManager.uploadState !is ProbeUploadState.ProbeUploadComplete
                            ) {
                                throw CancellationException("Upload State is Now Invalid")
                            }

                            // only log normal mode packets
                            if (deviceStatus.mode != ProbeMode.NORMAL)
                                return@collect

                            emit(
                                LoggedProbeDataPoint.fromDeviceStatus(
                                    sessionId,
                                    deviceStatus,
                                    log.currentSessionStartTime,
                                    log.currentSessionSamplePeriod
                                )
                            )
                        }
                }
            }
        }
    }

    private fun startBackfillLogRequest(
        log: ProbeTemperatureLog,
        probeManager: ProbeManager,
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
        probeManager.uploadState = progress.toProbeUploadState()

        Log.i(
            LOG_TAG,
            "Requesting Logs for Backfill[${probeManager.serialNumber}]: ${range.minSeq} to ${range.maxSeq} (${log.currentSessionId})"
        )

        // send the request to the device to start the upload
        probeManager.sendLogRequest(range.minSeq, range.maxSeq)

        // processing the resulting LogRequest flow happens in the coroutine above.
    }
}