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
import inc.combustion.framework.ble.device.DeviceInformationBleDevice
import inc.combustion.framework.ble.device.EngineBleDevice
import inc.combustion.framework.ble.device.SimulatedEngineBleDevice
import inc.combustion.framework.ble.scanning.DeviceAdvertisingData
import inc.combustion.framework.ble.scanning.EngineAdvertisingData
import inc.combustion.framework.ble.uart.LogResponse
import inc.combustion.framework.service.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

internal class EngineManager(
    mac: String,
    serialNumber: String,
    scope: CoroutineScope,
    settings: DeviceManager.Settings,
    dfuDisconnectedNodeCallback: (DeviceID) -> Unit,
) : NodeHybridManager<EngineBleDevice, EngineAdvertisingData, Engine, SimulatedEngineBleDevice>(
    scope = scope,
    settings = settings,
    dfuDisconnectedNodeCallback = dfuDisconnectedNodeCallback,
) {

    override val arbitrator = EngineDataLinkArbitrator()

    override val _deviceFlow = MutableStateFlow(Engine.create(serialNumber = serialNumber, mac = mac))

    override val deviceFlow: StateFlow<Engine> = _deviceFlow.asStateFlow()

    override val device: Engine
        get() = _deviceFlow.value

    private val _logResponseFlow = MutableSharedFlow<LogResponse>(
        replay = 0, extraBufferCapacity = 50, BufferOverflow.SUSPEND
    )
    override val logResponseFlow: SharedFlow<LogResponse> = _logResponseFlow.asSharedFlow()

    private val _normalModeStatusFlow = MutableSharedFlow<SpecializedDeviceStatus>(
        replay = 0, extraBufferCapacity = 10, BufferOverflow.DROP_OLDEST
    )
    override val normalModeStatusFlow: SharedFlow<SpecializedDeviceStatus> =
        _normalModeStatusFlow.asSharedFlow()

    override var uploadState: ProbeUploadState
        get() = _deviceFlow.value.uploadState
        set(value) {
            if (value != _deviceFlow.value.uploadState) {
                _deviceFlow.value = _deviceFlow.value.copy(uploadState = value)
            }
        }
    override var recordsDownloaded: Int
        get() = _deviceFlow.value.recordsDownloaded
        set(value) {
            if (value != _deviceFlow.value.recordsDownloaded) {
                _deviceFlow.value = _deviceFlow.value.copy(recordsDownloaded = value)
            }
        }
    override var logUploadPercent: UInt
        get() = _deviceFlow.value.logUploadPercent
        set(value) {
            if (value != _deviceFlow.value.logUploadPercent) {
                _deviceFlow.value = _deviceFlow.value.copy(logUploadPercent = value)
            }
        }

    // Abstract method implementations

    override fun Engine.withBaseDevice(baseDevice: Device): Engine = copy(baseDevice = baseDevice)

    override fun Engine.withStatusNotificationsStale(stale: Boolean): Engine =
        copy(statusNotificationsStale = stale)

    override fun castToAdvertisementType(advertisement: DeviceAdvertisingData): EngineAdvertisingData? =
        advertisement as? EngineAdvertisingData

    override fun handleAdvertisingPackets(device: EngineBleDevice, advertisement: EngineAdvertisingData) {
        // TODO: update engine state from advertisement
        checkAutoConnect(device)
    }

    override fun updateDataFromSimulatedAdvertisement(
        simDevice: SimulatedEngineBleDevice,
        advertisement: EngineAdvertisingData,
        current: Engine,
    ): Engine = current // TODO: update engine state from advertisement

    override fun sendLogRequest(startSequenceNumber: UInt, endSequenceNumber: UInt) {
        TODO("Not yet implemented")
    }

    fun hasEngine(): Boolean = hasDevice()

    fun addEngine(
        engine: EngineBleDevice,
        baseDevice: DeviceInformationBleDevice,
        advertisement: EngineAdvertisingData,
    ) = addDevice(engine, baseDevice, advertisement)

    fun addSimulatedEngine(simEngine: SimulatedEngineBleDevice) = addSimulatedDevice(simEngine)
}
