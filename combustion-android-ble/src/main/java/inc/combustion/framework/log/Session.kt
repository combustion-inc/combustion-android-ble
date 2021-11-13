package inc.combustion.framework.log

import android.util.Log
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.DeviceStatus
import inc.combustion.framework.ble.uart.LogResponse
import inc.combustion.framework.service.DebugSettings
import inc.combustion.framework.service.LoggedProbeDataPoint
import java.util.*

class Session(seqNum: UInt, private val _serialNumber: String) {

    companion object {
        /**
         * If > than this threshold of device status packets are received, then
         * consider the log request stalled.
         */
        const val STALE_LOG_REQUEST_PACKET_COUNT = 20u
    }

    private val _logs: SortedMap<UInt, LoggedProbeDataPoint> = sortedMapOf()

    private var _nextExpectedRecord = UInt.MAX_VALUE
    private var _nextExpectedDeviceStatus = UInt.MAX_VALUE
    private var _totalExpected = 0u
    private var _transferCount = 0u
    private var _logResponseDropCount = 0u
    private var _deviceStatusDropCount = 0u
    private var _staleLogRequestCount = STALE_LOG_REQUEST_PACKET_COUNT
    private val _droppedRecords = mutableListOf<UInt>()

    val id = SessionId(seqNum)
    val isEmpty get() = _logs.isEmpty()
    val minSequenceNumber: UInt get() = if(isEmpty) 0u else _logs.firstKey()
    val maxSequenceNumber: UInt get() = if(isEmpty) 0u else _logs.lastKey()
    val logRequestIsStalled: Boolean
        get() {
            val progress = UploadProgress(_transferCount, _logResponseDropCount, _totalExpected)
            return !progress.isComplete && (_staleLogRequestCount == 0u)
        }
    val dataPoints: List<LoggedProbeDataPoint>
        get() {
            return _logs.values.toList()
        }

    fun startLogRequest(range: RecordRange) : UploadProgress {
        // initialize tracking variables for pending record transfer
        _nextExpectedRecord = range.minSeq
        _nextExpectedDeviceStatus = range.maxSeq + 1u
        _totalExpected = range.maxSeq - range.minSeq + 1u
        _transferCount = 0u
        _staleLogRequestCount = STALE_LOG_REQUEST_PACKET_COUNT

        return UploadProgress(_transferCount, _logResponseDropCount, _totalExpected)
    }

    fun startLogBackfillRequest(range: RecordRange, currentDeviceStatusSequence: UInt) : UploadProgress {
        // initialize tracking variables for pending record transfer
        _nextExpectedRecord = range.minSeq
        _nextExpectedDeviceStatus = currentDeviceStatusSequence + 1u
        _totalExpected = range.maxSeq - range.minSeq + 1u
        _transferCount = 0u
        _staleLogRequestCount = STALE_LOG_REQUEST_PACKET_COUNT

        return UploadProgress(_transferCount, _logResponseDropCount, _totalExpected)
    }

    fun addFromLogResponse(logResponse: LogResponse) : UploadProgress {
        val loggedProbeDataPoint = LoggedProbeDataPoint.fromLogResponse(id, logResponse)

        // response received, reset the stalled counter.
        _staleLogRequestCount = STALE_LOG_REQUEST_PACKET_COUNT

        // check to see if we dropped data
        if(logResponse.sequenceNumber > _nextExpectedRecord) {
            _logResponseDropCount += (logResponse.sequenceNumber - _nextExpectedRecord)

            // track and log the dropped packet
            for(sequence in _nextExpectedDeviceStatus..(logResponse.sequenceNumber-1u)) {
                _droppedRecords.add(sequence)
                Log.w(LOG_TAG, "Detected device status data drop.  $_serialNumber.$sequence")
            }

            // but still add this data and resync.  and remove any drops.
            _logs[loggedProbeDataPoint.sequenceNumber] = loggedProbeDataPoint
            _nextExpectedRecord = loggedProbeDataPoint.sequenceNumber + 1u
            _transferCount++
            _droppedRecords.removeIf { dropped ->  dropped == loggedProbeDataPoint.sequenceNumber}
        }
        // check to see if we received duplicate data
        else if(logResponse.sequenceNumber < _nextExpectedRecord) {
            if(_logs.containsKey(logResponse.sequenceNumber)) {
                Log.w(LOG_TAG,
                    "Received duplicate record? " +
                            "$_serialNumber.${logResponse.sequenceNumber} ($_nextExpectedRecord)")

                _logs.remove(logResponse.sequenceNumber)
                _logs[loggedProbeDataPoint.sequenceNumber] = loggedProbeDataPoint

                // don't change the next expected
            }
            else {
                Log.w(LOG_TAG,
                    "Received unexpected record? " +
                            "$_serialNumber.${logResponse.sequenceNumber} ($_nextExpectedRecord)")
            }
        }
        // happy path, add the record, update the next expected.
        else {
            _logs[loggedProbeDataPoint.sequenceNumber] = loggedProbeDataPoint
            _nextExpectedRecord = loggedProbeDataPoint.sequenceNumber + 1u
            _transferCount++
            _droppedRecords.removeIf { dropped ->  dropped == loggedProbeDataPoint.sequenceNumber}
        }

        return UploadProgress(_transferCount, _logResponseDropCount, _totalExpected)
    }

    fun addFromDeviceStatus(deviceStatus: DeviceStatus) : SessionStatus {
        val loggedProbeDataPoint = LoggedProbeDataPoint.fromDeviceStatus(id, deviceStatus)

        // decrement the stale log request counter
        _staleLogRequestCount--

        // check to see if we dropped data
        if(deviceStatus.maxSequenceNumber > _nextExpectedDeviceStatus) {
            _deviceStatusDropCount += (deviceStatus.maxSequenceNumber - _nextExpectedDeviceStatus)

            // track and log the dropped packet
            for(sequence in _nextExpectedDeviceStatus..(deviceStatus.maxSequenceNumber-1u)) {
                _droppedRecords.add(sequence)
                Log.w(LOG_TAG, "Detected device status data drop. $_serialNumber.$sequence")
            }

            // but still add this data and resync.
            _logs[loggedProbeDataPoint.sequenceNumber] = loggedProbeDataPoint
            _nextExpectedDeviceStatus = loggedProbeDataPoint.sequenceNumber + 1u
        }
        // check to see if we received duplicate data
        else if(deviceStatus.maxSequenceNumber < _nextExpectedDeviceStatus) {
            if(_logs.containsKey(deviceStatus.maxSequenceNumber)) {
                Log.w(LOG_TAG,
                    "Tried to add duplicate device status? " +
                            "$_serialNumber.${deviceStatus.maxSequenceNumber} " +
                            "($_nextExpectedDeviceStatus)(${deviceStatus.maxSequenceNumber})")
            }
            else {
                Log.w(LOG_TAG,
                    "Received unexpected old record? " +
                            "$_serialNumber.${deviceStatus.maxSequenceNumber} " +
                            "($_nextExpectedDeviceStatus)(${deviceStatus.maxSequenceNumber})")
            }
        }
        // happy path, add the device status, update the next expected.
        else {
            _logs[loggedProbeDataPoint.sequenceNumber] = loggedProbeDataPoint
            _nextExpectedDeviceStatus = loggedProbeDataPoint.sequenceNumber + 1u
        }

        val status = SessionStatus(
            id.toString(),
            minSequenceNumber,
            maxSequenceNumber,
            _logs.size.toUInt(),
            _logResponseDropCount,
            _deviceStatusDropCount,
            _droppedRecords
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
        _nextExpectedRecord = UInt.MAX_VALUE

        return SessionStatus(
            id.toString(),
            minSequenceNumber,
            maxSequenceNumber,
            _logs.size.toUInt(),
            _logResponseDropCount,
            _deviceStatusDropCount,
            _droppedRecords
        )
    }

    fun expectFutureLogRequest() {
        _nextExpectedDeviceStatus = UInt.MAX_VALUE
    }
}
