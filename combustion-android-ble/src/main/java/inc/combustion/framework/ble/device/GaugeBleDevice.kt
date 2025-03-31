/*
 * Project: Combustion Inc. Android Framework
 * File: GaugeBleDevice.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2025. Combustion Inc.
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

import androidx.lifecycle.lifecycleScope
import inc.combustion.framework.ble.scanning.DeviceAdvertisingData
import inc.combustion.framework.ble.scanning.GaugeAdvertisingData
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.DeviceConnectionState
import inc.combustion.framework.service.FirmwareVersion
import inc.combustion.framework.service.ModelInformation
import kotlinx.coroutines.launch

internal class GaugeBleDevice(
    override val nodeParent: NodeBleDevice,
    private var gaugeAdvertisingData: GaugeAdvertisingData,
    private val uart: UartBleDevice,
) : UartCapableGauge, NodeHybridDevice {
    override val id: DeviceID = gaugeAdvertisingData.mac
    override val serialNumber: String = gaugeAdvertisingData.serialNumber
    override val productType: CombustionProductType = CombustionProductType.GAUGE

    // instance used for connection/disconnection
    val baseDevice: DeviceInformationBleDevice
        get() = uart

    override val isSimulated: Boolean = false
    override val isRepeater: Boolean = false

    override val mac: String
        get() = uart.mac
    override val hopCount: UInt = 0u
    override var shouldAutoReconnect: Boolean = false

    override var isInDfuMode: Boolean
        get() = uart.isInDfuMode
        set(value) {
            uart.isInDfuMode = value
        }

    // ble properties
    override val rssi: Int
        get() {
            return uart.rssi
        }
    override val connectionState: DeviceConnectionState
        get() {
            return uart.connectionState
        }
    override val isConnected: Boolean
        get() {
            return uart.isConnected.get()
        }
    override val isDisconnected: Boolean
        get() {
            return uart.isDisconnected.get()
        }
    override val isInRange: Boolean
        get() {
            return uart.isInRange.get()
        }
    override val isConnectable: Boolean
        get() {
            return uart.isConnectable.get()
        }

    // device information service values from the probe
    override val deviceInfoSerialNumber: String?
        get() {
            return uart.serialNumber
        }
    override val deviceInfoFirmwareVersion: FirmwareVersion?
        get() {
            return uart.firmwareVersion
        }
    override val deviceInfoHardwareRevision: String?
        get() {
            return uart.hardwareRevision
        }
    override val deviceInfoModelInformation: ModelInformation?
        get() {
            return uart.modelInformation
        }

    // connection management
    override fun connect() = uart.connect()
    override fun disconnect() = uart.disconnect()

    // device information service
    override suspend fun readSerialNumber() = uart.readSerialNumber()
    override suspend fun readFirmwareVersion() = uart.readFirmwareVersion()
    override suspend fun readHardwareRevision() = uart.readHardwareRevision()
    override suspend fun readModelInformation() = uart.readModelInformation()

    override fun readFirmwareVersionAsync(callback: (FirmwareVersion) -> Unit) {
        uart.owner.lifecycleScope.launch {
            readFirmwareVersion()
        }.invokeOnCompletion {
            deviceInfoFirmwareVersion?.let {
                callback(it)
            }
        }
    }

    override fun readHardwareRevisionAsync(callback: (String) -> Unit) {
        uart.owner.lifecycleScope.launch {
            readHardwareRevision()
        }.invokeOnCompletion {
            deviceInfoHardwareRevision?.let {
                callback(it)
            }
        }
    }

    override fun readModelInformationAsync(callback: (ModelInformation) -> Unit) {
        uart.owner.lifecycleScope.launch {
            readModelInformation()
        }.invokeOnCompletion {
            deviceInfoModelInformation?.let {
                callback(it)
            }
        }
    }

    // connection state updates
    override fun observeRemoteRssi(callback: (suspend (rssi: Int) -> Unit)?) =
        uart.observeRemoteRssi(callback)

    override fun observeOutOfRange(timeout: Long, callback: (suspend () -> Unit)?) =
        uart.observeOutOfRange(timeout, callback)

    override fun observeConnectionState(callback: (suspend (newConnectionState: DeviceConnectionState) -> Unit)?) =
        uart.observeConnectionState(callback)

    // advertising updates
    override fun observeAdvertisingPackets(
        serialNumberFilter: String,
        macFilter: String,
        callback: (suspend (advertisement: DeviceAdvertisingData) -> Unit)?,
    ) {
        uart.observeAdvertisingPackets(
            jobKey = serialNumberFilter,
            filter = { advertisement ->
                macFilter == advertisement.mac && advertisement.serialNumber == serialNumberFilter
            },
        ) { advertisement ->
            if (advertisement is GaugeAdvertisingData) {
                callback?.let {
                    gaugeAdvertisingData = advertisement
                    it(advertisement)
                }
            }
        }
    }
}