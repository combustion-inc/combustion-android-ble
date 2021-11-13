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
import inc.combustion.framework.ble.DeviceStatus
import inc.combustion.framework.ble.uart.LogResponse
import inc.combustion.framework.service.DebugSettings
import inc.combustion.framework.service.LoggedProbeDataPoint
import java.util.*

/**
 * Log transfer sessions and buffer instance for a probe.
 *
 * @property serialNumber Probe serial number
 * @constructor Constructs a new session.
 *
 * @param seqNum Starting sequence number
 */
internal class Session(seqNum: UInt, private val serialNumber: String) {

    companion object {
        /**
         * If > than this threshold of device status packets are received, then
         * consider the log request stalled.
         */
        const val STALE_LOG_REQUEST_PACKET_COUNT = 20u
    }

    private val _logs: SortedMap<UInt, LoggedProbeDataPoint> = sortedMapOf()
    private var nextExpectedRecord = UInt.MAX_VALUE
    private var nextExpectedDeviceStatus = UInt.MAX_VALUE
    private var totalExpected = 0u
    private var transferCount = 0u
    private var logResponseDropCount = 0u
    private var deviceStatusDropCount = 0u
    private var staleLogRequestCount = STALE_LOG_REQUEST_PACKET_COUNT
    private val droppedRecords = mutableListOf<UInt>()
    private val minSequenceNumber: UInt get() = if(isEmpty) 0u else _logs.firstKey()

    val id = SessionId(seqNum)
    val isEmpty get() = _logs.isEmpty()
    val maxSequenceNumber: UInt get() = if(isEmpty) 0u else _logs.lastKey()

    val logRequestIsStalled: Boolean
        get() {
            val progress = UploadProgress(transferCount, logResponseDropCount, totalExpected)
            return !progress.isComplete && (staleLogRequestCount == 0u)
        }

    val dataPoints: List<LoggedProbeDataPoint>
        get() {
            return _logs.values.toList()
        }

    fun startLogRequest(range: RecordRange) : UploadProgress {
        // initialize tracking variables for pending record transfer
        nextExpectedRecord = range.minSeq
        nextExpectedDeviceStatus = range.maxSeq + 1u
        totalExpected = range.maxSeq - range.minSeq + 1u
        transferCount = 0u
        staleLogRequestCount = STALE_LOG_REQUEST_PACKET_COUNT

        return UploadProgress(transferCount, logResponseDropCount, totalExpected)
    }

    fun startLogBackfillRequest(range: RecordRange, currentDeviceStatusSequence: UInt) : UploadProgress {
        // initialize tracking variables for pending record transfer
        nextExpectedRecord = range.minSeq
        nextExpectedDeviceStatus = currentDeviceStatusSequence + 1u
        totalExpected = range.maxSeq - range.minSeq + 1u
        transferCount = 0u
        staleLogRequestCount = STALE_LOG_REQUEST_PACKET_COUNT

        return UploadProgress(transferCount, logResponseDropCount, totalExpected)
    }

    fun addFromLogResponse(logResponse: LogResponse) : UploadProgress {
        val loggedProbeDataPoint = LoggedProbeDataPoint.fromLogResponse(id, logResponse)

        // response received, reset the stalled counter.
        staleLogRequestCount = STALE_LOG_REQUEST_PACKET_COUNT

        // check to see if we dropped data
        if(logResponse.sequenceNumber > nextExpectedRecord) {
            logResponseDropCount += (logResponse.sequenceNumber - nextExpectedRecord)

            // track and log the dropped packet
            for(sequence in nextExpectedDeviceStatus..(logResponse.sequenceNumber-1u)) {
                droppedRecords.add(sequence)
                Log.w(LOG_TAG, "Detected device status data drop.  $serialNumber.$sequence")
            }

            // but still add this data and resync.  and remove any drops.
            _logs[loggedProbeDataPoint.sequenceNumber] = loggedProbeDataPoint
            nextExpectedRecord = loggedProbeDataPoint.sequenceNumber + 1u
            transferCount++
            droppedRecords.removeIf { dropped ->  dropped == loggedProbeDataPoint.sequenceNumber}
        }
        // check to see if we received duplicate data
        else if(logResponse.sequenceNumber < nextExpectedRecord) {
            if(_logs.containsKey(logResponse.sequenceNumber)) {
                Log.w(LOG_TAG,
                    "Received duplicate record? " +
                            "$serialNumber.${logResponse.sequenceNumber} ($nextExpectedRecord)")

                _logs.remove(logResponse.sequenceNumber)
                _logs[loggedProbeDataPoint.sequenceNumber] = loggedProbeDataPoint

                // don't change the next expected
            }
            else {
                Log.w(LOG_TAG,
                    "Received unexpected record? " +
                            "$serialNumber.${logResponse.sequenceNumber} ($nextExpectedRecord)")
            }
        }
        // happy path, add the record, update the next expected.
        else {
            _logs[loggedProbeDataPoint.sequenceNumber] = loggedProbeDataPoint
            nextExpectedRecord = loggedProbeDataPoint.sequenceNumber + 1u
            transferCount++
            droppedRecords.removeIf { dropped ->  dropped == loggedProbeDataPoint.sequenceNumber}
        }

        return UploadProgress(transferCount, logResponseDropCount, totalExpected)
    }

    fun addFromDeviceStatus(deviceStatus: DeviceStatus) : SessionStatus {
        val loggedProbeDataPoint = LoggedProbeDataPoint.fromDeviceStatus(id, deviceStatus)

        // decrement the stale log request counter
        staleLogRequestCount--

        // check to see if we dropped data
        if(deviceStatus.maxSequenceNumber > nextExpectedDeviceStatus) {
            deviceStatusDropCount += (deviceStatus.maxSequenceNumber - nextExpectedDeviceStatus)

            // track and log the dropped packet
            for(sequence in nextExpectedDeviceStatus..(deviceStatus.maxSequenceNumber-1u)) {
                droppedRecords.add(sequence)
                Log.w(LOG_TAG, "Detected device status data drop. $serialNumber.$sequence")
            }

            // but still add this data and resync.
            _logs[loggedProbeDataPoint.sequenceNumber] = loggedProbeDataPoint
            nextExpectedDeviceStatus = loggedProbeDataPoint.sequenceNumber + 1u
        }
        // check to see if we received duplicate data
        else if(deviceStatus.maxSequenceNumber < nextExpectedDeviceStatus) {
            if(_logs.containsKey(deviceStatus.maxSequenceNumber)) {
                Log.w(LOG_TAG,
                    "Tried to add duplicate device status? " +
                            "$serialNumber.${deviceStatus.maxSequenceNumber} " +
                            "($nextExpectedDeviceStatus)(${deviceStatus.maxSequenceNumber})")
            }
            else {
                Log.w(LOG_TAG,
                    "Received unexpected old record? " +
                            "$serialNumber.${deviceStatus.maxSequenceNumber} " +
                            "($nextExpectedDeviceStatus)(${deviceStatus.maxSequenceNumber})")
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
            logResponseDropCount,
            deviceStatusDropCount,
            droppedRecords
        )

        if(DebugSettings.DEBUG_LOG_SESSION_STATUS) {

            Log.d(LOG_TAG, "$status " +
                    "[${deviceStatus.minSequenceNumber.toInt()} - ${deviceStatus.maxSequenceNumber.toInt()}]"
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
            logResponseDropCount,
            deviceStatusDropCount,
            droppedRecords
        )
    }

    fun expectFutureLogRequest() {
        nextExpectedDeviceStatus = UInt.MAX_VALUE
    }
}
