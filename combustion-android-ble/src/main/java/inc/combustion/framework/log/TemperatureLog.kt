/*
 * Project: Combustion Inc. Android Framework
 * File: TemperatureLog.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2025. Combustion Inc.
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
import inc.combustion.framework.ble.SpecializedDeviceStatus
import inc.combustion.framework.ble.uart.LogResponse
import inc.combustion.framework.service.DebugSettings
import inc.combustion.framework.service.LoggedDataPoint
import inc.combustion.framework.service.SessionInformation
import java.util.Date

internal abstract class TemperatureLog<L : LoggedDataPoint>(protected val serialNumber: String) {
    protected var sessions: Array<Session> = arrayOf()

    abstract val dataPoints: List<L>
    abstract val dataPointsBySession: List<List<L>>

    val currentSessionId: UInt get() = sessions.lastOrNull()?.id ?: 0u
    val currentSessionSamplePeriod: UInt get() = sessions.lastOrNull()?.samplePeriod ?: 0u
    val currentSessionStartTime: Date? get() = sessions.lastOrNull()?.startTime
    val logStartTime: Date? get() = sessions.firstOrNull()?.startTime

    val dataPointCount: Int
        get() {
            var count = 0
            sessions.forEach {
                count += it.dataPointCount
            }
            return count
        }

    fun missingRecordsForRange(start: UInt, end: UInt): UInt? {
        return sessions.lastOrNull()?.missingRecordsForRange(start, end)
    }

    fun missingRecordRange(start: UInt, end: UInt): RecordRange? {
        return sessions.lastOrNull()?.missingRecordRange(start, end)
    }

    private val logRequestIsStalled: Boolean
        get() = sessions.lastOrNull()?.logRequestIsStalled ?: false

    fun logRequestIsComplete(): Boolean {
        return logRequestIsStalled
    }

    fun prepareForLogRequest(
        deviceMinSequence: UInt,
        deviceMaxSequence: UInt,
        sessionInfo: SessionInformation
    ): RecordRange {
        var minSeq = 0u
        var maxSeq = 0u

        if (DebugSettings.DEBUG_LOG_SESSION_STATUS) {
            Log.d(LOG_TAG, "Session list size: ${sessions.size}, IDs: ${sessions.map { it.id }}")
        }
        // handle initial condition
        if (sessions.isEmpty()) {
            startNewSession(sessionInfo, deviceMaxSequence)
            if (DebugSettings.DEBUG_LOG_SESSION_STATUS) {
                Log.d(LOG_TAG, "Created first session. ID ${sessionInfo.sessionID}")
            }
        } else if (sessions.last().id != sessionInfo.sessionID) {
            startNewSession(sessionInfo, deviceMaxSequence)
            if (DebugSettings.DEBUG_LOG_SESSION_STATUS) {
                Log.d(LOG_TAG, "Created new session. ID ${sessionInfo.sessionID}")
            }
        }

        // determine request range for the current session
        sessions.lastOrNull()?.let { session ->
            // if the current session is empty, request everything
            if (session.isEmpty) {
                minSeq = deviceMinSequence
                maxSeq = deviceMaxSequence
            }
            // otherwise, request everything not yet uploaded to phone
            else {
                minSeq = session.logRequestStartSequence(deviceMinSequence)
                maxSeq = deviceMaxSequence
            }
        }

        // sanity check the output, log if there is a bug
        if (minSeq > maxSeq) {
            Log.w(
                LOG_TAG,
                "Sanitized range. $minSeq to $maxSeq for $serialNumber ${sessionInfo.sessionID}"
            )
            minSeq = maxSeq
        }

        if (DebugSettings.DEBUG_LOG_TRANSFER) {
            Log.d(LOG_TAG, "Next Log Request: $minSeq to $maxSeq")
        }

        return RecordRange(minSeq, maxSeq)
    }

    fun startLogRequest(range: RecordRange): UploadProgress =
        sessions.lastOrNull()?.startLogRequest(range) ?: UploadProgress.NULL_UPLOAD_PROGRESS

    fun startLogBackfillRequest(
        range: RecordRange,
        currentDeviceStatusSequence: UInt
    ): UploadProgress =
        sessions.lastOrNull()?.startLogBackfillRequest(range, currentDeviceStatusSequence)
            ?: UploadProgress.NULL_UPLOAD_PROGRESS

    fun addFromLogResponse(logResponse: LogResponse): UploadProgress =
        sessions.lastOrNull()?.addFromLogResponse(logResponse)
            ?: UploadProgress.NULL_UPLOAD_PROGRESS

    fun addFromDeviceStatus(deviceStatus: SpecializedDeviceStatus): SessionStatus =
        sessions.lastOrNull()?.addFromDeviceStatus(deviceStatus)
            ?: SessionStatus.NULL_SESSION_STATUS

    fun completeLogRequest(): SessionStatus =
        sessions.lastOrNull()?.completeLogRequest() ?: SessionStatus.NULL_SESSION_STATUS

    fun expectFutureLogRequest() =
        sessions.lastOrNull()?.expectFutureLogRequest()

    private fun startNewSession(sessionInfo: SessionInformation, deviceMaxSequence: UInt) {
        // create new session and add to map
        val session = Session(serialNumber, sessionInfo, deviceMaxSequence)
        sessions += session
    }
}