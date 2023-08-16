/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeUploadState.kt
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
package inc.combustion.framework.service

/**
 * Enumerates upload states of probe data
 */
sealed class ProbeUploadState {

    /**
     * Upload is not available (e.g. not connected)
     */
    object Unavailable : ProbeUploadState()

    /**
     * Upload is needed (e.g. device has records to be transferred)
     */
    object ProbeUploadNeeded : ProbeUploadState()

    /**
     * Upload is in progress.
     *
     * @property recordsTransferred Number of records transferred statistic
     * @property recordsRequested Number of records requested for transfer statistic
     */
    data class ProbeUploadInProgress(
        val recordsTransferred: UInt,
        val recordsRequested: UInt
    ) : ProbeUploadState()

    /**
     * Upload has been completed.  Data is being synchronized in real-time.
     *
     * @property sessionMinSequence Min sequence number on phone for current session.
     * @property sessionMaxSequence Max sequence number on phone for current session.
     * @property totalRecords Total number of records on the phone for current session.
     */
    data class ProbeUploadComplete(
        val sessionMinSequence: UInt,
        val sessionMaxSequence: UInt,
        val totalRecords: UInt,
    ) : ProbeUploadState()
}