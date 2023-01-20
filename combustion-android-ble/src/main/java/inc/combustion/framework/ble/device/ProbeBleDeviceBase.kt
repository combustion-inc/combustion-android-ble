/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeBleDeviceBase.kt
 * Author: http://github.com/miwright2
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
package inc.combustion.framework.ble.device

import inc.combustion.framework.ble.ProbeStatus
import inc.combustion.framework.ble.scanning.ProbeAdvertisingData
import inc.combustion.framework.ble.scanning.RepeaterAdvertisingData
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

typealias LinkID = String

internal interface IProbeBleDeviceBase {
    val probeStatusFlow: SharedFlow<ProbeStatus>
}

internal open class ProbeBleDeviceBase() : IProbeBleDeviceBase {

    companion object {
        fun makeLinkId(repeaterAdvertisement: RepeaterAdvertisingData): LinkID {
            return "${repeaterAdvertisement.id}_${repeaterAdvertisement.probeSerialNumber}"
        }

        fun makeLinkId(probeAdvertisement: ProbeAdvertisingData): LinkID {
            return "${probeAdvertisement.id}_${probeAdvertisement.probeSerialNumber}"
        }
    }

    val mutableProbeStatusFlow = MutableSharedFlow<ProbeStatus>(
        replay = 0, extraBufferCapacity = 10, BufferOverflow.DROP_OLDEST)
    override val probeStatusFlow = mutableProbeStatusFlow.asSharedFlow()
}