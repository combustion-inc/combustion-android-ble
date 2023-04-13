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
import inc.combustion.framework.ble.ProbeManager
import inc.combustion.framework.service.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton class for manage the service log buffer and record upload from probes.
 */
internal class LogManager {
    private val probes = hashMapOf<String, ProbeManager>()
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

    fun manage(owner: LifecycleOwner, probeManager: ProbeManager) {
        if(!probes.containsKey(probeManager.serialNumber)) {
            // add legacyProbeManager
            probes[probeManager.serialNumber] = probeManager

            // create new temperature log for legacyProbeManager and track it
            if(!temperatureLogs.containsKey(probeManager.serialNumber)) {
                temperatureLogs[probeManager.serialNumber] =
                    ProbeTemperatureLog(probeManager.serialNumber)
            }

            // maintain reference to log for further processing
            val temperatureLog = temperatureLogs.getValue(probeManager.serialNumber)

            probeManager.logTransferCompleteCallback = {
                temperatureLog?.expectFutureLogRequest()
            }

            // monitor this probes device status flow
            probeManager.addJob(owner.lifecycleScope.launch {
                owner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                    probeManager.probeStatusFlow
                        .onCompletion {
                            Log.d(LOG_TAG, "Probe Status Flow Complete")
                        }
                        .catch {
                            Log.i(LOG_TAG, "Probe Status Flow Catch: $it")
                        }
                        .collect { deviceStatus ->
                            when(probeManager.uploadState) {
                                // if upload state is currently unavailable, and we now have a
                                // ProbeStatus, then we have everything we need to start an upload.
                                //
                                // event the new upload state to the client, and they can initiate
                                // the record transfer when they are ready.
                                ProbeUploadState.Unavailable -> {
                                    if(DeviceManager.instance.settings.autoLogTransfer) {
                                        // note, this will transfer to ProbeUploadInProgress
                                        requestLogsFromDevice(owner, probeManager.serialNumber)
                                    }
                                    else {
                                        probeManager.uploadState = ProbeUploadState.ProbeUploadNeeded
                                    }
                                }
                                ProbeUploadState.ProbeUploadNeeded -> {
                                    // else, do nothing, wait for the client to request the transfer
                                }
                                is ProbeUploadState.ProbeUploadInProgress -> {
                                    // only log normal mode packets
                                    if(deviceStatus.mode != ProbeMode.NORMAL)
                                        return@collect

                                    // add device status data points to the log while the upload
                                    // is in progress.  don't event the status, because we will
                                    // do that below in processing the log response.
                                    temperatureLog.addFromDeviceStatus(deviceStatus)

                                    // update the count of records downloaded
                                    probeManager.recordsDownloaded = temperatureLog.dataPointCount

                                    // not receiving expected log responses, then complete the log
                                    // request and transition state.
                                    if(temperatureLog.logRequestIsStalled) {
                                        val sessionStatus = temperatureLog.completeLogRequest()
                                        probeManager.uploadState = sessionStatus.toProbeUploadState()
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

                                    // update the count of records downloaded
                                    probeManager.recordsDownloaded = temperatureLog.dataPointCount

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
                                            owner, temperatureLog, probeManager, range, deviceStatus.maxSequenceNumber)
                                    }
                                    // we've synchronized the log on the device here, now maintain
                                    // the log with the data points from the device status message.
                                    // and report out the progress as part of the complete state.
                                    else {
                                        probeManager.uploadState = sessionStatus.toProbeUploadState()
                                    }
                                }
                            }
                    }
                }
            })
            // monitor this probes log response flow
            probeManager.addJob(owner.lifecycleScope.launch {
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
                            if(DebugSettings.DEBUG_LOG_LOG_MANAGER_IO) {
                                Log.d(LOG_TAG, "LOG-RX    : ${response.sequenceNumber}")
                            }

                            // add the response to the temperature log and handle upload progress
                            val uploadProgress = temperatureLog.addFromLogResponse(response)

                            // update the count of records downloaded
                            probeManager.recordsDownloaded = temperatureLog.dataPointCount

                            // if we aren't complete, then update the stats of the uploading state
                            if(!uploadProgress.isComplete) {
                                probeManager.uploadState = uploadProgress.toProbeUploadState()
                            }
                            // otherwise complete the request, and change the state to complete
                            else {
                                val sessionStatus = temperatureLog.completeLogRequest()
                                probeManager.uploadState = sessionStatus.toProbeUploadState()
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
        val probeManager = probes[serialNumber] ?: return
        val log = temperatureLogs[serialNumber] ?: return
        val sessionInfo = probeManager.sessionInfo ?: return

        // prepare log for the request and determine the needed range
        val range =  log.prepareForLogRequest(
            probeManager.minSequenceNumber,
            probeManager.maxSequenceNumber,
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
        probeManager.uploadState = progress.toProbeUploadState()

        Log.i(LOG_TAG, "Requesting Logs[$serialNumber]: ${range.minSeq} to ${range.maxSeq} (${log.currentSessionId})")

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

    fun createLogFlowForDevice(serialNumber: String, includeHistory: Boolean = true): Flow<LoggedProbeDataPoint> {
        return flow {
            probes[serialNumber]?.let { probeManager ->

                // if the device isn't uploading or hasn't completed an upload, then
                // return out of this flow immediately, there is nothing to do.  the device
                // needs to connect, or the client needs to initiate the record transfer.
                if(probeManager.uploadState !is ProbeUploadState.ProbeUploadInProgress &&
                        probeManager.uploadState !is ProbeUploadState.ProbeUploadComplete)  {
                   return@flow
                }

                // wait for the upload to complete before emitting into the flow.
                while(probeManager.uploadState is ProbeUploadState.ProbeUploadInProgress &&
                        currentCoroutineContext().isActive) {
                    delay(250)
                }

                // upload is complete, so get the logged data points stored on the phone and emit
                // them into the flow, in order.
                val log = temperatureLogs[serialNumber]
                log?.let { temperatureLog ->
                    if(includeHistory) {
                        temperatureLog.dataPoints.forEach {
                            emit(it)
                        }
                    }

                    // collect the probe status updates as they come in, in order, and emit them
                    // into the flow for the consumer.  if we are no longer in an upload complete
                    // syncing state, then exit out of this flow.
                    val sessionId = log.currentSessionId
                    probeManager.probeStatusFlow
                        .catch {
                            Log.w(LOG_TAG, "Log Flow: Device Status Flow Catch: $it")
                        }
                        .collect { deviceStatus ->
                            if(probeManager.uploadState !is ProbeUploadState.ProbeUploadInProgress &&
                                probeManager.uploadState !is ProbeUploadState.ProbeUploadComplete)  {
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
        probeManager: ProbeManager,
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
        probeManager.uploadState = progress.toProbeUploadState()

        Log.i(LOG_TAG, "Requesting Logs for Backfill[${probeManager.serialNumber}]: ${range.minSeq} to ${range.maxSeq} (${log.currentSessionId})")

        // send the request to the device to start the upload
        probeManager.sendLogRequest(range.minSeq, range.maxSeq)

        // processing the resulting LogRequest flow happens in the coroutine above.
    }
}