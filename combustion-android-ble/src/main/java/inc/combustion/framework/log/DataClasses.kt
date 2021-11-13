package inc.combustion.framework.log

import inc.combustion.framework.service.ProbeUploadState
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Type representing a range of records.
 *
 * @property minSeq Low end of range (inclusive)
 * @property maxSeq High end of range (inclusive)
 */
data class RecordRange(val minSeq: UInt, val maxSeq: UInt) {
    companion object {
        val NULL_RECORD_RANGE = RecordRange(0u, 0u)
    }

    val size: UInt get() { return maxSeq - minSeq + 1u }
}

/**
 * Type representing upload progress
 *
 * @property transferred number of records received
 * @property drops number of dropped records
 * @property expected number of records expected
 */
data class UploadProgress(val transferred: UInt, val drops: UInt, val expected: UInt) {
    companion object {
        val NULL_UPLOAD_PROGRESS = UploadProgress(0u, 0u, 0u)
    }

    val isComplete: Boolean get() { return (transferred + drops) == expected }

    fun toProbeUploadState() : ProbeUploadState {
        return ProbeUploadState.ProbeUploadInProgress(
            transferred, drops, expected
        )
    }
}

data class SessionStatus(
    val id: String,
    val sessionMinSequence: UInt,
    val sessionMaxSequence: UInt,
    val totalRecords: UInt,
    val logResponseDropCount: UInt,
    val deviceStatusDropCount: UInt,
    val droppedRecords: List<UInt>
) {
    companion object {
        val NULL_SESSION_STATUS = SessionStatus(
            "", 0u, 0u,
            0u, 0u, 0u,
            listOf()
        )
    }

    fun toProbeUploadState() : ProbeUploadState {
        return ProbeUploadState.ProbeUploadComplete(
            sessionMinSequence,
            sessionMaxSequence,
            totalRecords,
            logResponseDropCount,
            deviceStatusDropCount
        )
    }

    override fun toString(): String {
        return String.format("%s: %d - %d [%d] [%d] [%d] [%d]",
            id, sessionMinSequence.toInt(), sessionMaxSequence.toInt(),
            totalRecords.toInt(), deviceStatusDropCount.toInt(),
            logResponseDropCount.toInt(),
            droppedRecords.size)
    }
}

/**
 * Type representing a unique and comparable session identifier.
 *
 * @property id the identifier
 */
data class SessionId(val seqNumber: UInt, val id: Long = create()) : Comparable<SessionId> {
    override fun compareTo(other: SessionId): Int {
        return when {
            this.id > other.id -> 1
            this.id < other.id -> -1
            else -> 0
        }
    }

    override fun toString(): String {
        // convert to date string in UTC
        val timestamp = formatter.format(
                LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(id),
                    ZoneId.of("UTC")
                )
            )
        return timestamp + "_$seqNumber"
    }

    companion object {
        val NULL_SESSION_ID = SessionId(0u, 0L, )
        private fun create(): Long {
            // use timestamp for session ID.
            return System.currentTimeMillis()
        }
        private val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    }
}

