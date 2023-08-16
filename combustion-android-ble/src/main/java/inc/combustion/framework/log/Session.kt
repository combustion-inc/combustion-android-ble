/*
 * Project: Combustion Inc. Android Framework
 * File: Session.kt
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
import inc.combustion.framework.ble.ProbeStatus
import inc.combustion.framework.ble.uart.LogResponse
import inc.combustion.framework.service.DebugSettings
import inc.combustion.framework.service.LoggedProbeDataPoint
import inc.combustion.framework.service.SessionInformation
import java.util.*

/**
 * Log transfer sessions and buffer instance for a probe.
 *
 * @property serialNumber Probe serial number
 * @constructor Constructs a new session.
 *
 * @param sessionInfo Session information from probe
 */
internal class Session(
    private val serialNumber: String,
    private val sessionInfo: SessionInformation,
    deviceStatusMaxSequence: UInt,
) {

    companion object {
        /**
         * If > than this threshold of device status packets are received, then
         * consider the log request stalled.
         */
        const val STALE_LOG_REQUEST_PACKET_COUNT = 2u
        const val MAX_DROPPED_PACKET_HANDLING = 5000
    }

    private val _logs: SortedMap<UInt, LoggedProbeDataPoint> = sortedMapOf()
    private var nextExpectedRecord = UInt.MAX_VALUE
    private var nextExpectedDeviceStatus = UInt.MAX_VALUE
    private var totalExpected = 0u
    private var transferCount = 0u
    private var staleLogRequestCount = STALE_LOG_REQUEST_PACKET_COUNT
    private val minSequenceNumber: UInt get() = if(isEmpty) 0u else _logs.firstKey()
    private val maxSequenceNumber: UInt get() = if(isEmpty) 0u else _logs.lastKey()

    val id = sessionInfo.sessionID
    val samplePeriod = sessionInfo.samplePeriod
    val isEmpty get() = _logs.isEmpty()
    val startTime: Date

    init {
        val milliSinceStart = (sessionInfo.samplePeriod * deviceStatusMaxSequence).toLong()
        startTime = Date(System.currentTimeMillis() - milliSinceStart)
    }

    val logRequestIsStalled: Boolean
        get() {
            return staleLogRequestCount == 0u
        }

    val dataPoints: List<LoggedProbeDataPoint>
        get() {
            return _logs.values.toList()

        }

    val dataPointCount: Int
        get() {
            return _logs.values.size
        }

    fun missingRecordRange(start: UInt, end: UInt): RecordRange? {
        var lowerBound: UInt? = null

        for (sequence in start..end) {
            if(!_logs.containsKey(sequence)) {
                lowerBound = sequence
                break
            }
        }

        lowerBound?.let {lower ->
            var upper: UInt? = null

            if (lower < end) {
                for(sequence in ((lower + 1u)..end).reversed()) {
                    if(!_logs.containsKey(sequence)) {
                        upper = sequence
                        break
                    }
                }
            }

            val upperBound = upper ?: lower

            return RecordRange(lower, upperBound)
        }

        // no lower bound implies no missing records
        return null
    }

    fun logRequestStartSequence(deviceMinSequence: UInt): UInt {
        if(deviceMinSequence < minSequenceNumber) {
            return deviceMinSequence
        }

        val iterator = _logs.keys.sorted().iterator()

        if(!iterator.hasNext())
            return deviceMinSequence

        var lastKey = iterator.next()
        while(iterator.hasNext()) {
            val key = iterator.next()
            if (lastKey >= key - 1u) {
                lastKey = key
            } else {
                break
            }
        }

        if(lastKey < deviceMinSequence) {
            return deviceMinSequence
        }

        return lastKey + 1u
    }

    fun startLogRequest(range: RecordRange) : UploadProgress {
        // initialize tracking variables for pending record transfer
        nextExpectedRecord = range.minSeq
        nextExpectedDeviceStatus = range.maxSeq + 1u
        totalExpected = range.maxSeq - range.minSeq + 1u
        transferCount = 0u
        staleLogRequestCount = STALE_LOG_REQUEST_PACKET_COUNT

        return UploadProgress(transferCount, totalExpected)
    }

    fun startLogBackfillRequest(range: RecordRange, currentDeviceStatusSequence: UInt) : UploadProgress {
        // initialize tracking variables for pending record transfer
        nextExpectedRecord = range.minSeq
        nextExpectedDeviceStatus = currentDeviceStatusSequence + 1u
        totalExpected = range.maxSeq - range.minSeq + 1u
        transferCount = 0u
        staleLogRequestCount = STALE_LOG_REQUEST_PACKET_COUNT

        return UploadProgress(transferCount, totalExpected)
    }

    fun addFromLogResponse(logResponse: LogResponse) : UploadProgress {
        val loggedProbeDataPoint = LoggedProbeDataPoint.fromLogResponse(id, logResponse, startTime, sessionInfo.samplePeriod)

        // check to see if we dropped data
        if(logResponse.sequenceNumber > nextExpectedRecord) {
            Log.d(LOG_TAG, "Detected log response data drop ($serialNumber). " +
                    "Drop range ${nextExpectedRecord}..${logResponse.sequenceNumber-1u}")

            // but still add this data and resync.  and remove any drops.
            _logs[loggedProbeDataPoint.sequenceNumber] = loggedProbeDataPoint
            nextExpectedRecord = loggedProbeDataPoint.sequenceNumber + 1u
            transferCount++
        }
        // check to see if we received duplicate data
        else if(logResponse.sequenceNumber < nextExpectedRecord) {
            // the following can occur when re-requesting records to handle gaps in the record log.
            if(DebugSettings.DEBUG_LOG_TRANSFER) {
                Log.d(
                    LOG_TAG,
                    "Received duplicate record " +
                            "$serialNumber.${logResponse.sequenceNumber} ($nextExpectedRecord)"
                )
            }
        }
        // happy path, add the record, update the next expected.
        else {
            // new log response received, reset the stalled counter.
            staleLogRequestCount = STALE_LOG_REQUEST_PACKET_COUNT

            // store the log
            _logs[loggedProbeDataPoint.sequenceNumber] = loggedProbeDataPoint

            // update debug stats
            nextExpectedRecord = loggedProbeDataPoint.sequenceNumber + 1u
            transferCount++
        }

        return UploadProgress(transferCount, totalExpected)
    }

    fun addFromDeviceStatus(probeStatus: ProbeStatus) : SessionStatus {
        val loggedProbeDataPoint = LoggedProbeDataPoint.fromDeviceStatus(id, probeStatus, startTime, sessionInfo.samplePeriod)

        // decrement the stale log request counter
        staleLogRequestCount--

        // sanity check -- if LogManger is adding ProbeStatus to the Session log, but has not
        // started the log request, then force nextExpectedDeviceStatus here.
        if(nextExpectedDeviceStatus == UInt.MAX_VALUE)  {
           nextExpectedDeviceStatus = probeStatus.maxSequenceNumber - 1u
           if(DebugSettings.DEBUG_LOG_TRANSFER) {
               Log.d(LOG_TAG, "Forcing nextExpectedDeviceStatue: $nextExpectedDeviceStatus")
           }
        }

        // check to see if we dropped data
        if(probeStatus.maxSequenceNumber > nextExpectedDeviceStatus) {
            // log a warning message
            Log.w(LOG_TAG, "Detected device status data drop ($serialNumber). " +
                "Drop range ${nextExpectedDeviceStatus}..${probeStatus.maxSequenceNumber-1u}")

            // but still add this data and resync.
            _logs[loggedProbeDataPoint.sequenceNumber] = loggedProbeDataPoint
            nextExpectedDeviceStatus = loggedProbeDataPoint.sequenceNumber + 1u
        }
        // check to see if we received duplicate data
        else if(probeStatus.maxSequenceNumber < nextExpectedDeviceStatus) {
            if(_logs.containsKey(probeStatus.maxSequenceNumber)) {
                Log.d(LOG_TAG,
                    "Dropping duplicate device status: " +
                            "$serialNumber.${probeStatus.maxSequenceNumber} " +
                            "($nextExpectedDeviceStatus)(${probeStatus.maxSequenceNumber})")
            }
            else {
                Log.w(LOG_TAG,
                    "Dropping old device status: " +
                            "$serialNumber.${probeStatus.maxSequenceNumber} " +
                            "($nextExpectedDeviceStatus)(${probeStatus.maxSequenceNumber})")
            }
        }
        // happy path, add the device status, update the next expected.
        else {
            _logs[loggedProbeDataPoint.sequenceNumber] = loggedProbeDataPoint
            nextExpectedDeviceStatus = loggedProbeDataPoint.sequenceNumber + 1u
        }

        val status = SessionStatus(
            id.toString(),
            minSequenceNumber,
            maxSequenceNumber,
            _logs.size.toUInt(),
        )

        if(DebugSettings.DEBUG_LOG_SESSION_STATUS) {
            Log.d(LOG_TAG, "$status " +
                    "[${probeStatus.minSequenceNumber.toInt()} - ${probeStatus.maxSequenceNumber.toInt()}]"
            )
        }

        return status
    }

    fun completeLogRequest() : SessionStatus {
        if(DebugSettings.DEBUG_LOG_TRANSFER) {
            Log.d(LOG_TAG, "Completing Log Request ...")
        }
        nextExpectedRecord = UInt.MAX_VALUE

        return SessionStatus(
            id.toString(),
            minSequenceNumber,
            maxSequenceNumber,
            _logs.size.toUInt(),
        )
    }

    fun expectFutureLogRequest() {
        nextExpectedDeviceStatus = UInt.MAX_VALUE
    }
}
