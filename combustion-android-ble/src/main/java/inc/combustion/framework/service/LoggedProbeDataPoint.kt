package inc.combustion.framework.service

import inc.combustion.framework.ble.DeviceStatus
import inc.combustion.framework.ble.uart.LogResponse
import inc.combustion.framework.log.SessionId

/**
 * Contains a single temperature log acquired by a probe.
 *
 * @property sessionId Session ID for the data log.
 * @property sequenceNumber Sequence number for the data log
 * @property temperatures Temperature measurements
 */
data class LoggedProbeDataPoint (
    val sessionId: SessionId,
    val sequenceNumber: UInt,
    val temperatures: ProbeTemperatures
) : Comparable<LoggedProbeDataPoint> {

    override fun compareTo(other: LoggedProbeDataPoint): Int {
        return when {
            this.sequenceNumber > other.sequenceNumber -> 1
            this.sequenceNumber < other.sequenceNumber -> -1
            else -> 0
        }
    }

    fun toCSV(): String {
        return String.format("%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%s",
            sequenceNumber.toInt(),
            temperatures.values[0],
            temperatures.values[1],
            temperatures.values[2],
            temperatures.values[3],
            temperatures.values[4],
            temperatures.values[5],
            temperatures.values[6],
            temperatures.values[7],
            sessionId
        )
    }

    companion object{
        fun fromDeviceStatus(sessionId: SessionId, status: DeviceStatus): LoggedProbeDataPoint {
            return LoggedProbeDataPoint(sessionId, status.maxSequenceNumber, status.temperatures)
        }

        fun fromLogResponse(sessionId: SessionId, response: LogResponse): LoggedProbeDataPoint {
            return LoggedProbeDataPoint(sessionId, response.sequenceNumber, response.temperatures)
        }

        fun csvHeader(): String {
            return "SequenceNumber,T1,T2,T3,T4,T5,T6,T7,T8,SessionID"
        }
    }
}