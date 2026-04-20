/*
 * Project: Combustion Inc. Android Framework
 * File: EngineManager.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2026. Combustion Inc.
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

package inc.combustion.framework.ble

import inc.combustion.framework.ble.device.DeviceID
import inc.combustion.framework.ble.uart.LogResponse
import inc.combustion.framework.service.DeviceManager
import inc.combustion.framework.service.Gauge
import inc.combustion.framework.service.ProbeUploadState
import inc.combustion.framework.service.SpecializedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

internal class EngineManager(
    mac: String,
    serialNumber: String,
    val scope: CoroutineScope,
    private val settings: DeviceManager.Settings,
    private val dfuDisconnectedNodeCallback: (DeviceID) -> Unit,
) : BleManager() {
    // holds the current state and data for this gauge
    private val _deviceFlow = MutableStateFlow(Gauge.create(serialNumber = serialNumber, mac = mac))

    override val serialNumber: String
        get() {
            return _deviceFlow.value.serialNumber
        }

    override val device: SpecializedDevice
        get() = TODO("Not yet implemented")
    override val deviceFlow: StateFlow<SpecializedDevice>
        get() = TODO("Not yet implemented")
    override val normalModeStatusFlow: SharedFlow<SpecializedDeviceStatus>
        get() = TODO("Not yet implemented")
    override val minSequenceNumber: UInt?
        get() = TODO("Not yet implemented")
    override val maxSequenceNumber: UInt?
        get() = TODO("Not yet implemented")
    override val logResponseFlow: SharedFlow<LogResponse>
        get() = TODO("Not yet implemented")
    override val arbitrator: DataLinkArbitrator<*, *>
        get() = TODO("Not yet implemented")
    override var uploadState: ProbeUploadState
        get() = TODO("Not yet implemented")
        set(value) {}
    override var recordsDownloaded: Int
        get() = TODO("Not yet implemented")
        set(value) {}
    override var logUploadPercent: UInt
        get() = TODO("Not yet implemented")
        set(value) {}

    override fun sendLogRequest(startSequenceNumber: UInt, endSequenceNumber: UInt) {
        TODO("Not yet implemented")
    }
}