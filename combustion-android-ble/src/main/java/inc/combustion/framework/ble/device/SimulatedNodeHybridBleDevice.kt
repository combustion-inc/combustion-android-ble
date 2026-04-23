/*
 * Project: Combustion Inc. Android Framework
 * File: SimulatedNodeHybridBleDevice.kt
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

package inc.combustion.framework.ble.device

import inc.combustion.framework.ble.scanning.DeviceAdvertisingData
import inc.combustion.framework.service.*
import inc.combustion.framework.service.dfu.DfuProductType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.concurrent.fixedRateTimer
import kotlin.random.Random

internal abstract class SimulatedNodeHybridBleDevice(
    private val scope: CoroutineScope,
    override val mac: String = randomMac(),
    override val serialNumber: String = "%08X".format(Random.nextInt()),
    var shouldConnect: Boolean = false,
    override val hopCount: UInt = 0u,
) : UartCapableSpecializedDevice {

    companion object {
        fun randomRSSI(): Int = Random.nextInt(-80, -40)

        fun randomMac(): String = "%02X:%02X:%02X:%02X:%02X:%02X".format(
            Random.nextBytes(1).first(),
            Random.nextBytes(1).first(),
            Random.nextBytes(1).first(),
            Random.nextBytes(1).first(),
            Random.nextBytes(1).first(),
            Random.nextBytes(1).first(),
        )
    }

    abstract override val productType: CombustionProductType

    protected abstract fun generateAdvertisement(): DeviceAdvertisingData

    override val id: DeviceID = mac
    override val isSimulated: Boolean = true
    override val isRepeater: Boolean = false
    override var shouldAutoReconnect: Boolean = false

    override var rssi: Int = randomRSSI()
        protected set

    override var connectionState: DeviceConnectionState =
        DeviceConnectionState.ADVERTISING_CONNECTABLE
        protected set

    override var isConnected: Boolean = false
        protected set

    override var isDisconnected: Boolean = true
        protected set

    override val isInRange: Boolean = true
    override val isConnectable: Boolean = true
    override var isInDfuMode: Boolean = false

    override var deviceInfoSerialNumber: String? = null
        protected set

    override var deviceInfoFirmwareVersion: FirmwareVersion? = null
        protected set

    override var deviceInfoHardwareRevision: String? = null
        protected set

    override var deviceInfoModelInformation: ModelInformation? = null
        protected set

    private var observeAdvertisingCallback: (suspend (advertisement: DeviceAdvertisingData) -> Unit)? =
        null

    private var observeRemoteRssiCallback: (suspend (rssi: Int) -> Unit)? = null

    private var observeConnectionStateCallback: (suspend (newConnectionState: DeviceConnectionState) -> Unit)? =
        null

    init {
        fixedRateTimer(name = "SimAdvertising", initialDelay = 1000, period = 1000) {
            scope.launch {
                if (!isConnected) {
                    observeAdvertisingCallback?.invoke(generateAdvertisement())
                }
            }
        }
    }

    override fun connect() {
        isDisconnected = false
        isConnected = true
        connectionState = DeviceConnectionState.CONNECTED
        deviceInfoSerialNumber = serialNumber
        deviceInfoFirmwareVersion = FirmwareVersion(1, 2, 3, null, null)
        deviceInfoHardwareRevision = "v2.3.4"
        deviceInfoModelInformation = ModelInformation(
            productType = productType,
            dfuProductType = DfuProductType.fromCombustionProductType(productType),
            sku = "ABCDEF",
            manufacturingLot = "98765",
        )
        publishConnectionState()
    }

    override fun disconnect() {
        isDisconnected = true
        isConnected = false
        deviceInfoSerialNumber = null
        deviceInfoFirmwareVersion = null
        deviceInfoHardwareRevision = null
        deviceInfoModelInformation = null
        connectionState = DeviceConnectionState.ADVERTISING_CONNECTABLE
        publishConnectionState()
    }

    override suspend fun readSerialNumber() { }
    override suspend fun readFirmwareVersion() { }
    override suspend fun readHardwareRevision() { }
    override suspend fun readModelInformation() { }
    override fun readFirmwareVersionAsync(callback: (FirmwareVersion) -> Unit) { }
    override fun readHardwareRevisionAsync(callback: (String) -> Unit) { }
    override fun readModelInformationAsync(callback: (ModelInformation) -> Unit) { }

    override fun observeAdvertisingPackets(
        serialNumberFilter: String,
        macFilter: String,
        callback: (suspend (advertisement: DeviceAdvertisingData) -> Unit)?,
    ) {
        observeAdvertisingCallback = callback
    }

    override fun observeRemoteRssi(callback: (suspend (rssi: Int) -> Unit)?) {
        observeRemoteRssiCallback = callback
    }

    override fun observeOutOfRange(timeout: Long, callback: (suspend () -> Unit)?) {
        // simulated device does not go out of range
    }

    override fun observeConnectionState(callback: (suspend (newConnectionState: DeviceConnectionState) -> Unit)?) {
        observeConnectionStateCallback = callback
        publishConnectionState()
    }

    private fun publishConnectionState() {
        observeConnectionStateCallback?.let {
            scope.launch {
                it(connectionState)
            }
        }
    }
}
