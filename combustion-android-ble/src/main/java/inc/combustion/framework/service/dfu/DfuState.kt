/*
 * Project: Combustion Inc. Android Framework
 * File: DfuState.kt
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

import inc.combustion.framework.service.Device

sealed class DfuState {
    abstract val device: Device
    abstract fun copy(device: Device): DfuState

    /**
     * A device is [NotReady] if it's seen in advertising but hasn't pulled down the necessary
     * information from the device information service.
     */
    data class NotReady(override val device: Device, val status: Status) : DfuState() {
        enum class Status {
            DISCONNECTED,
            OUT_OF_RANGE,
            CONNECTING,
            READING_DEVICE_INFORMATION,
            BLOCKED,
        }
        override fun copy(device: Device): DfuState = copy(device = device, status = status)
    }

    /**
     * A device is [Idle] if it's available to perform DFU, but the DFU operation isn't currently
     * in progress.
     */
    data class Idle(override val device: Device, val status: String = "") : DfuState() {
        override fun copy(device: Device): DfuState = copy(device = device, status = status)
    }

    /**
     * A device is [InProgress] if it's in the middle of a DFU operation.
     */
    data class InProgress(override val device: Device, val status: DfuProgress) : DfuState() {
        override fun copy(device: Device): DfuState = copy(device = device, status = status)
    }
}