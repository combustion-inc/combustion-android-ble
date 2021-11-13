package inc.combustion.framework.log

import android.util.Log
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.DeviceStatus
import inc.combustion.framework.ble.uart.LogResponse
import inc.combustion.framework.service.DebugSettings
import inc.combustion.framework.service.LoggedProbeDataPoint
import java.util.*

class ProbeTemperatureLog(private val _serialNumber: String) {

    private val _sessions: SortedMap<SessionId, Session> = sortedMapOf()
    var currentSessionId = SessionId.NULL_SESSION_ID
        private set

    private var _dataPoints = mutableListOf<LoggedProbeDataPoint>()
    val dataPoints: List<LoggedProbeDataPoint>
        get() {
            _dataPoints.clear()
            _sessions.forEach {
                _dataPoints.addAll(it.value.dataPoints)
            }
            return _dataPoints
        }

    val logRequestIsStalled: Boolean get() = _sessions[currentSessionId]?.logRequestIsStalled ?: false

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
        _sessions[currentSessionId]?.let { session ->
            if(!session.isEmpty) {
                val localMaxSequence = session.maxSequenceNumber

                // date was lost
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
        _sessions[currentSessionId]?.let { session ->
            // if the current session is empty, request everything
            if(session.isEmpty) {
                minSeq = deviceMinSequence
                maxSeq = deviceMaxSequence
            }
            // otherwise, request everything not yet uploaded to phone
            else {
                minSeq = session.maxSequenceNumber + 1u
                maxSeq = deviceMaxSequence
            }
        }

        // sanity check the output, log if there is a bug
        if(minSeq > maxSeq) {
            Log.w(LOG_TAG,
                "Sanitized range. $minSeq to $maxSeq for $_serialNumber $currentSessionId")
            minSeq = maxSeq
        }

        if(DebugSettings.DEBUG_LOG_TRANSFER) {
            Log.d(LOG_TAG, "Next Log Request: $minSeq to $maxSeq")
        }

        return RecordRange(minSeq, maxSeq)
    }

    fun startLogRequest(range: RecordRange) : UploadProgress =
        _sessions[currentSessionId]?.startLogRequest(range) ?: UploadProgress.NULL_UPLOAD_PROGRESS

    fun startLogBackfillRequest(range: RecordRange, currentDeviceStatusSequence: UInt) : UploadProgress =
        _sessions[currentSessionId]?.startLogBackfillRequest(range, currentDeviceStatusSequence)
            ?: UploadProgress.NULL_UPLOAD_PROGRESS

    fun addFromLogResponse(logResponse: LogResponse) : UploadProgress =
        _sessions[currentSessionId]?.addFromLogResponse(logResponse) ?: UploadProgress.NULL_UPLOAD_PROGRESS

    fun addFromDeviceStatus(deviceStatus: DeviceStatus) : SessionStatus =
        _sessions[currentSessionId]?.addFromDeviceStatus(deviceStatus) ?: SessionStatus.NULL_SESSION_STATUS

    fun completeLogRequest() : SessionStatus =
        _sessions[currentSessionId]?.completeLogRequest() ?: SessionStatus.NULL_SESSION_STATUS

    fun expectFutureLogRequest() =
        _sessions[currentSessionId]?.expectFutureLogRequest()

    private fun startNewSession(sequence: UInt) {
        // create new session and add to map
        val session = Session(sequence, _serialNumber)
        currentSessionId = session.id
        _sessions[session.id] = session
    }
}