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
import inc.combustion.framework.ble.LegacyProbeManager
import inc.combustion.framework.service.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton class for manage the service log buffer and record upload from probes.
 */
internal class LogManager {
    private val probes = hashMapOf<String, LegacyProbeManager>()
    private val temperatureLogs:  SortedMap<String, ProbeTemperatureLog> = sortedMapOf()

    companion object {
        private lateinit var INSTANCE: LogManager
        private val initialized = AtomicBoolean(false)

        val instance: LogManager
            get() {
            if(!initialized.getAndSet(true)) {
                INSTANCE = LogManager()
            }
            return INSTANCE
        }
    }

    fun manage(owner: LifecycleOwner, legacyProbeManager: LegacyProbeManager) {
        if(!probes.containsKey(legacyProbeManager.probe.serialNumber)) {
            // add legacyProbeManager
            probes[legacyProbeManager.probe.serialNumber] = legacyProbeManager

            // create new temperature log for legacyProbeManager and track it
            if(!temperatureLogs.containsKey(legacyProbeManager.probe.serialNumber)) {
                temperatureLogs[legacyProbeManager.probe.serialNumber] =
                    ProbeTemperatureLog(legacyProbeManager.probe.serialNumber)
            }

            // maintain reference to log for further processing
            val temperatureLog = temperatureLogs.getValue(legacyProbeManager.probe.serialNumber)

            // monitor this probes state flow
            legacyProbeManager.addJob(owner.lifecycleScope.launch {
                owner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                    legacyProbeManager.probeStateFlow
                        .onCompletion {
                            Log.d(LOG_TAG, "Probe State Flow Complete")
                        }
                        .catch {
                            Log.i(LOG_TAG, "Probe State Flow Catch: $it")
                        }
                        .collect {
                            // if device is disconnected, uploading is unavailable, so update
                            // the state if it hs not already been updated and let the log know
                            // to expect another log request at some point in the future, for its
                            // internal bookkeeping.
                            if(it.connectionState != DeviceConnectionState.CONNECTED
                                && legacyProbeManager.probe.uploadState != ProbeUploadState.Unavailable) {
                                legacyProbeManager.onNewUploadState(ProbeUploadState.Unavailable)
                                temperatureLog?.expectFutureLogRequest()
                            }
                    }
                }
            })
            // monitor this probes device status flow
            legacyProbeManager.addJob(owner.lifecycleScope.launch {
                owner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                    legacyProbeManager.deviceStatusFlow
                        .onCompletion {
                            Log.d(LOG_TAG, "Device Status Flow Complete")
                        }
                        .catch {
                            Log.i(LOG_TAG, "Device Status Flow Catch: $it")
                        }
                        .collect { deviceStatus ->
                            when(legacyProbeManager.probe.uploadState) {
                                // if upload state is currently unavailable, and we now have a
                                // ProbeStatus, then we have everything we need to start an upload.
                                //
                                // event the new upload state to the client, and they can initiate
                                // the record transfer when they are ready.
                                ProbeUploadState.Unavailable -> {
                                    legacyProbeManager.onNewUploadState(ProbeUploadState.ProbeUploadNeeded)
                                }
                                ProbeUploadState.ProbeUploadNeeded -> {
                                    // do nothing, wait for the client to request the transfer
                                }
                                is ProbeUploadState.ProbeUploadInProgress -> {
                                    // only log normal mode packets
                                    if(deviceStatus.mode != ProbeMode.NORMAL)
                                        return@collect

                                    // add device status data points to the log while the upload
                                    // is in progress.  don't event the status, because we will
                                    // do that below in processing the log response.
                                    temperatureLog.addFromDeviceStatus(deviceStatus)

                                    // not receiving expected log responses, then complete the log
                                    // request and transition state.
                                    if(temperatureLog.logRequestIsStalled) {
                                        val sessionStatus = temperatureLog.completeLogRequest()
                                        legacyProbeManager.onNewUploadState(sessionStatus.toProbeUploadState())
                                    }
                                }
                                is ProbeUploadState.ProbeUploadComplete -> {
                                    if(DebugSettings.DEBUG_LOG_LOG_MANAGER_IO) {
                                        Log.d(
                                            LOG_TAG, "STATUS-RX : " +
                                                    "${deviceStatus.maxSequenceNumber}"
                                        )
                                    }

                                    // only log normal mode packets
                                    if(deviceStatus.mode != ProbeMode.NORMAL)
                                        return@collect

                                    // add the device status to the temperature log
                                    val sessionStatus = temperatureLog.addFromDeviceStatus(deviceStatus)

                                    // if we have any dropped records then initiate a log request to
                                    // backfill from the missing records.
                                    if(sessionStatus.droppedRecords.isNotEmpty()) {

                                        // find the first set of consecutive sequence numbers and
                                        // request that range to backfill.
                                        val (first, last) = if(sessionStatus.droppedRecords.size <= 1) {
                                            Pair(sessionStatus.droppedRecords[0], sessionStatus.droppedRecords[0])
                                        } else {
                                            val first = sessionStatus.droppedRecords[0]
                                            var last = first
                                            for(i in 1 until sessionStatus.droppedRecords.size) {
                                                if(last + 1u == sessionStatus.droppedRecords[i]) {
                                                    last++
                                                }
                                                else {
                                                    break
                                                }
                                            }
                                            Pair(first, last)
                                        }

                                        val range = RecordRange(first, last)
                                        startBackfillLogRequest(
                                            owner, temperatureLog, legacyProbeManager, range, deviceStatus.maxSequenceNumber)
                                    }
                                    // we've synchronized the log on the device here, now maintain
                                    // the log with the data points from the device status message.
                                    // and report out the progress as part of the complete state.
                                    else {
                                        legacyProbeManager.onNewUploadState(sessionStatus.toProbeUploadState())
                                    }
                                }
                            }
                    }
                }
            })
            // monitor this probes log response flow
            legacyProbeManager.addJob(owner.lifecycleScope.launch {
                owner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                    legacyProbeManager.logResponseFlow
                        .onCompletion {
                            Log.d(LOG_TAG, "Log Response Flow Complete")
                        }
                        .catch {
                            Log.i(LOG_TAG, "Log Response Flow Catch: $it")
                        }
                        .collect { response ->
                            // debug log BLE IO if enabled.
                            if(DebugSettings.DEBUG_LOG_LOG_MANAGER_IO) {
                                Log.d(LOG_TAG, "LOG-RX    : ${response.sequenceNumber}")
                            }

                            // add the response to the temperature log and handle upload progress
                            val uploadProgress = temperatureLog.addFromLogResponse(response)

                            // if we aren't complete, then update the stats of the uploading state
                            if(!uploadProgress.isComplete) {
                                legacyProbeManager.onNewUploadState(uploadProgress.toProbeUploadState())
                            }
                            // otherwise complete the request, and change the state to complete
                            else {
                                val sessionStatus = temperatureLog.completeLogRequest()
                                legacyProbeManager.onNewUploadState(sessionStatus.toProbeUploadState())
                            }
                    }
                }
            })
        }
    }

    fun clear() {
        probes.clear()
        temperatureLogs.clear()
    }

    fun requestLogsFromDevice(owner: LifecycleOwner, serialNumber: String) {
        val probe = probes[serialNumber] ?: return
        val log = temperatureLogs[serialNumber] ?: return
        val sessionInfo = probe.probe.sessionInfo ?: return

        // prepare log for the request and determine the needed range
        val range =  log.prepareForLogRequest(
            probe.probe.minSequenceNumber,
            probe.probe.maxSequenceNumber,
            sessionInfo
        )

        // if for some reason there isn't anything to request,
        // log a message and return
        if(range.size == 0u)  {
            if(DebugSettings.DEBUG_LOG_TRANSFER) {
                Log.w(LOG_TAG, "No need to request logs from device")
            }
            return
        }

        // initialize the start of the log request with the temperature log
        val progress = log.startLogRequest(range)

        // update the probe's upload state with the progress.
        owner.lifecycleScope.launch {
            probe.onNewUploadState(progress.toProbeUploadState())
        }

        // send the request to the device to start the upload
        probe.sendLogRequest(range.minSeq, range.maxSeq)

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

    fun createLogFlowForDevice(serialNumber: String): Flow<LoggedProbeDataPoint> {
        return flow {
            probes[serialNumber]?.let { probe ->

                // if the device isn't uploading or hasn't completed an upload, then
                // return out of this flow immediately, there is nothing to do.  the device
                // needs to connect, or the client needs to initiate the record transfer.
                if(probe.probe.uploadState !is ProbeUploadState.ProbeUploadInProgress &&
                        probe.probe.uploadState !is ProbeUploadState.ProbeUploadComplete)  {
                   return@flow
                }

                // wait for the upload to complete before emitting into the flow.
                while(probe.probe.uploadState is ProbeUploadState.ProbeUploadInProgress &&
                        currentCoroutineContext().isActive) {
                    delay(250)
                }

                // upload is complete, so get the logged data points stored on the phone and emit
                // them into the flow, in order.
                val log = temperatureLogs[serialNumber]
                log?.let { temperatureLog ->
                    temperatureLog.dataPoints.forEach {
                        emit(it)
                    }

                    // collect the probe status updates as they come in, in order, and emit them
                    // into the flow for the consumer.  if we are no longer in an upload complete
                    // syncing state, then exit out of this flow.
                    val sessionId = log.currentSessionId
                    probe.deviceStatusFlow
                        .onCompletion {
                            Log.i(LOG_TAG, "Log Flow: Device Status Flow Complete")
                        }
                        .catch {
                            Log.w(LOG_TAG, "Log Flow: Device Status Flow Catch: $it")
                        }
                        .collect { deviceStatus ->
                            if(probe.probe.uploadState !is ProbeUploadState.ProbeUploadInProgress &&
                                probe.probe.uploadState !is ProbeUploadState.ProbeUploadComplete)  {
                                    throw CancellationException("Upload State is Now Invalid")
                            }

                            // only log normal mode packets
                            if(deviceStatus.mode != ProbeMode.NORMAL)
                                return@collect

                            emit(LoggedProbeDataPoint.fromDeviceStatus(
                                sessionId,
                                deviceStatus,
                                log.currentSessionStartTime,
                                log.currentSessionSamplePeriod)
                            )
                    }
                }
            }
        }
    }

    private fun startBackfillLogRequest(
        owner: LifecycleOwner,
        log: ProbeTemperatureLog,
        legacyProbeManager: LegacyProbeManager,
        range: RecordRange,
        currentDeviceStatusSequence: UInt
    ) {

        // if for some reason there isn't anything to request,
        // log a message and return
        if(range.size == 0u)  {
            if(DebugSettings.DEBUG_LOG_TRANSFER) {
                Log.w(LOG_TAG, "No need to backfill request logs from device $range")
            }
            return
        }

        // initialize the start of the log request with the temperature log
        val progress = log.startLogBackfillRequest(range, currentDeviceStatusSequence)

        // update the legacyProbeManager's upload state with the progress.
        owner.lifecycleScope.launch {
            legacyProbeManager.onNewUploadState(progress.toProbeUploadState())
        }

        // send the request to the device to start the upload
        legacyProbeManager.sendLogRequest(range.minSeq, range.maxSeq)

        // processing the resulting LogRequest flow happens in the coroutine above.
    }
}