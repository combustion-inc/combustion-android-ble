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
import inc.combustion.framework.ble.uart.SessionInformation
import inc.combustion.framework.service.DebugSettings
import inc.combustion.framework.service.LoggedProbeDataPoint

/**
 * Session log for a single probe.
 *
 * @property serialNumber Probe's serial number.
 */
internal class ProbeTemperatureLog(private val serialNumber: String) {

    private var sessions: Array<Session> = arrayOf()

    private var _dataPoints = mutableListOf<LoggedProbeDataPoint>()
    val dataPoints: List<LoggedProbeDataPoint>
        get() {
            _dataPoints.clear()
            sessions.forEach {
                _dataPoints.addAll(it.dataPoints)
            }
            return _dataPoints
        }

    val logRequestIsStalled: Boolean get() = sessions.lastOrNull()?.logRequestIsStalled ?: false
    val currentSessionId: UInt get() = sessions.lastOrNull()?.id ?: 0u

    val dataPointCount: Int
        get() {
            var count = 0
            sessions.forEach {
                count += it.dataPointCount
            }
            return count
        }

    fun prepareForLogRequest(deviceMinSequence: UInt, deviceMaxSequence: UInt, sessionInfo: SessionInformation) : RecordRange {
        var minSeq = 0u
        var maxSeq = 0u

        // handle initial condition
        if(sessions.isEmpty()) {
            startNewSession(sessionInfo)
            if(DebugSettings.DEBUG_LOG_SESSION_STATUS) {
                Log.d(LOG_TAG, "Created first session. ID ${sessionInfo.sessionID}")
            }
        }
        else if(sessions.last().id != sessionInfo.sessionID) {
            startNewSession(sessionInfo)
            if(DebugSettings.DEBUG_LOG_SESSION_STATUS) {
                Log.d(LOG_TAG, "Created new session. ID ${sessionInfo.sessionID}")
            }
        }

        // determine request range for the current session
        sessions.lastOrNull()?.let { session ->
            // if the current session is empty, request everything
            if(session.isEmpty) {
                minSeq = deviceMinSequence
                maxSeq = deviceMaxSequence
            }
            // otherwise, request everything not yet uploaded to phone
            else {
                minSeq = session.maxSequentialSequenceNumber + 1u
                maxSeq = deviceMaxSequence
            }
        }

        // sanity check the output, log if there is a bug
        if(minSeq > maxSeq) {
            Log.w(LOG_TAG,
                "Sanitized range. $minSeq to $maxSeq for $serialNumber ${sessionInfo.sessionID}")
            minSeq = maxSeq
        }

        if(DebugSettings.DEBUG_LOG_TRANSFER) {
            Log.d(LOG_TAG, "Next Log Request: $minSeq to $maxSeq")
        }

        return RecordRange(minSeq, maxSeq)
    }

    fun startLogRequest(range: RecordRange) : UploadProgress =
        sessions.lastOrNull()?.startLogRequest(range) ?: UploadProgress.NULL_UPLOAD_PROGRESS

    fun startLogBackfillRequest(range: RecordRange, currentDeviceStatusSequence: UInt) : UploadProgress =
        sessions.lastOrNull()?.startLogBackfillRequest(range, currentDeviceStatusSequence)
            ?: UploadProgress.NULL_UPLOAD_PROGRESS

    fun addFromLogResponse(logResponse: LogResponse) : UploadProgress =
        sessions.lastOrNull()?.addFromLogResponse(logResponse) ?: UploadProgress.NULL_UPLOAD_PROGRESS

    fun addFromDeviceStatus(deviceStatus: DeviceStatus) : SessionStatus =
        sessions.lastOrNull()?.addFromDeviceStatus(deviceStatus) ?: SessionStatus.NULL_SESSION_STATUS

    fun completeLogRequest() : SessionStatus =
        sessions.lastOrNull()?.completeLogRequest() ?: SessionStatus.NULL_SESSION_STATUS

    fun expectFutureLogRequest() =
        sessions.lastOrNull()?.expectFutureLogRequest()

    private fun startNewSession(sessionInfo: SessionInformation) {
        // create new session and add to map
        val session = Session(serialNumber, sessionInfo)
        sessions += session
    }
}