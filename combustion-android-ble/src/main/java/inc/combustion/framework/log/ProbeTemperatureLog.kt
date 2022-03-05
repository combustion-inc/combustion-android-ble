/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeTemperatureLog.kt
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
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.DeviceStatus
import inc.combustion.framework.ble.uart.LogResponse
import inc.combustion.framework.service.DebugSettings
import inc.combustion.framework.service.LoggedProbeDataPoint
import java.util.*

/**
 * Session log for a single probe.
 *
 * @property serialNumber Probe's serial number.
 */
internal class ProbeTemperatureLog(private val serialNumber: String) {

    private val sessions: SortedMap<SessionId, Session> = sortedMapOf()
    var currentSessionId = SessionId.NULL_SESSION_ID
        private set

    private var _dataPoints = mutableListOf<LoggedProbeDataPoint>()
    val dataPoints: List<LoggedProbeDataPoint>
        get() {
            _dataPoints.clear()
            sessions.forEach {
                _dataPoints.addAll(it.value.dataPoints)
            }
            return _dataPoints
        }

    val logRequestIsStalled: Boolean get() = sessions[currentSessionId]?.logRequestIsStalled ?: false

    fun prepareForLogRequest(deviceMinSequence: UInt, deviceMaxSequence: UInt) : RecordRange {
        var minSeq = 0u
        var maxSeq = 0u

        // handle initial condition
        if(currentSessionId == SessionId.NULL_SESSION_ID) {
            startNewSession(deviceMaxSequence)
            if(DebugSettings.DEBUG_LOG_SESSION_STATUS) {
                Log.d(LOG_TAG, "Created first session. ID $currentSessionId")
            }
        }

        // handle the creation of new session
        sessions[currentSessionId]?.let { session ->
            if(!session.isEmpty) {
                val localMaxSequence = session.maxSequencialSequenceNumber

                // data was lost
                if(localMaxSequence < deviceMinSequence) {
                    startNewSession(deviceMaxSequence)

                    if(DebugSettings.DEBUG_LOG_SESSION_STATUS)  {
                        Log.d(
                            LOG_TAG, "Created new session - drop " +
                                "($localMaxSequence < $deviceMinSequence). ID $currentSessionId"
                        )
                    }
                }
                // either (a) firmware reset sequence number (e.g. on charger)
                //     or (b) firmware sequence number rolled over (not likely)
                else if(localMaxSequence > deviceMaxSequence) {
                    startNewSession(deviceMaxSequence)

                    if(DebugSettings.DEBUG_LOG_SESSION_STATUS) {
                        Log.d(
                            LOG_TAG, "Created new session - reset " +
                                    "($localMaxSequence > $deviceMaxSequence). ID $currentSessionId"
                        )
                    }
                }
            }
        }

        // determine request range for the current session
        sessions[currentSessionId]?.let { session ->
            // if the current session is empty, request everything
            if(session.isEmpty) {
                minSeq = deviceMinSequence
                maxSeq = deviceMaxSequence
            }
            // otherwise, request everything not yet uploaded to phone
            else {
                minSeq = session.maxSequencialSequenceNumber + 1u
                maxSeq = deviceMaxSequence
            }
        }

        // sanity check the output, log if there is a bug
        if(minSeq > maxSeq) {
            Log.w(LOG_TAG,
                "Sanitized range. $minSeq to $maxSeq for $serialNumber $currentSessionId")
            minSeq = maxSeq
        }

        if(DebugSettings.DEBUG_LOG_TRANSFER) {
            Log.d(LOG_TAG, "Next Log Request: $minSeq to $maxSeq")
        }

        return RecordRange(minSeq, maxSeq)
    }

    fun startLogRequest(range: RecordRange) : UploadProgress =
        sessions[currentSessionId]?.startLogRequest(range) ?: UploadProgress.NULL_UPLOAD_PROGRESS

    fun startLogBackfillRequest(range: RecordRange, currentDeviceStatusSequence: UInt) : UploadProgress =
        sessions[currentSessionId]?.startLogBackfillRequest(range, currentDeviceStatusSequence)
            ?: UploadProgress.NULL_UPLOAD_PROGRESS

    fun addFromLogResponse(logResponse: LogResponse) : UploadProgress =
        sessions[currentSessionId]?.addFromLogResponse(logResponse) ?: UploadProgress.NULL_UPLOAD_PROGRESS

    fun addFromDeviceStatus(deviceStatus: DeviceStatus) : SessionStatus =
        sessions[currentSessionId]?.addFromDeviceStatus(deviceStatus) ?: SessionStatus.NULL_SESSION_STATUS

    fun completeLogRequest() : SessionStatus =
        sessions[currentSessionId]?.completeLogRequest() ?: SessionStatus.NULL_SESSION_STATUS

    fun expectFutureLogRequest() =
        sessions[currentSessionId]?.expectFutureLogRequest()

    private fun startNewSession(sequence: UInt) {
        // create new session and add to map
        val session = Session(sequence, serialNumber)
        currentSessionId = session.id
        sessions[session.id] = session
    }
}