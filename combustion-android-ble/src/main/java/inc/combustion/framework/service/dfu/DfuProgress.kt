/*
 * Project: Combustion Inc. Android Framework
 * File: DfuProgress.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2023. Combustion Inc.
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

package inc.combustion.framework.service.dfu

sealed class DfuProgress {
    data class Initializing(val status: Status) : DfuProgress() {
        enum class Status {
            INITIALIZING,
            CONNECTING_TO_DEVICE,
            CONNECTED_TO_DEVICE,
            STARTING_DFU_PROCESS,
            STARTED_DFU_PROCESS,
            ENABLING_DFU_MODE,
            ENTERING_BOOTLOADER,
            VALIDATING_FIRMWARE,
        }
    }

    data class Uploading(
        val progress: Int = 0,
        val avgSpeed: Float = 0f,
        val currentPart: Int = 0,
        val partsTotal: Int = 0
    ) : DfuProgress()

    data class Finishing(val status: Status) : DfuProgress() {
        enum class Status {
            DISCONNECTING_FROM_DEVICE,
            DISCONNECTED_FROM_DEVICE,
            COMPLETE_RESTARTING_DEVICE,
        }
    }
    object Aborted : DfuProgress()

    /**
     * Indicates an error with the DFU operation. [type] is mapped directly to types out of the
     * Nordic library as indicated in the comments for [ErrorType]. [message] is an English-language
     * description of the error.
     */
    data class Error(val type: ErrorType, val message: String) : DfuProgress() {
        enum class ErrorType {
            COMMUNICATION_STATE, // DfuBaseService.ERROR_TYPE_COMMUNICATION_STATE
            COMMUNICATION, // DfuBaseService.ERROR_TYPE_COMMUNICATION
            DFU_OPERATION, // DfuBaseService.ERROR_TYPE_DFU_REMOTE
            OTHER, // DfuBaseService.ERROR_TYPE_OTHER
        }
    }
}