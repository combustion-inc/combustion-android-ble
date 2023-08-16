/*
 * Project: Combustion Inc. Android Framework
 * File: DataClasses.kt
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

import inc.combustion.framework.service.ProbeUploadState

/**
 * Range range data object
 *
 * @property minSeq Start of range (inclusive)
 * @property maxSeq End of range (inclusive)
 */
internal data class RecordRange(val minSeq: UInt, val maxSeq: UInt) {
    companion object {
        val NULL_RECORD_RANGE = RecordRange(0u, 0u)
    }

    val size: UInt get() {
        return if(minSeq == maxSeq) 0u else maxSeq - minSeq + 1u
    }
}

/**
 * Upload progress data object
 *
 * @property transferred number of records received
 * @property expected number of records expected
 */
internal data class UploadProgress(val transferred: UInt, val expected: UInt) {
    companion object {
        val NULL_UPLOAD_PROGRESS = UploadProgress(0u, 0u)
    }

    // val isComplete: Boolean get() { return (transferred + drops) == expected }

    fun toProbeUploadState() : ProbeUploadState {
        return ProbeUploadState.ProbeUploadInProgress(
            transferred, expected
        )
    }
}

/**
 * Upload session status data object.
 *
 * @property id Session ID
 * @property sessionMinSequence Starting sequence number.
 * @property sessionMaxSequence Max sequence number.
 * @property totalRecords Total number of transferred record.
 */
internal data class SessionStatus(
    val id: String,
    val sessionMinSequence: UInt,
    val sessionMaxSequence: UInt,
    val totalRecords: UInt,
) {
    companion object {
        val NULL_SESSION_STATUS = SessionStatus(
            "", 0u, 0u, 0u,
        )
    }

    fun toProbeUploadState() : ProbeUploadState {
        return ProbeUploadState.ProbeUploadComplete(
            sessionMinSequence,
            sessionMaxSequence,
            totalRecords,
        )
    }

    override fun toString(): String {
        return String.format("%s: %d - %d [%d]",
            id, sessionMinSequence.toInt(), sessionMaxSequence.toInt(), totalRecords.toInt()
        )
    }
}
