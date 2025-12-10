/*
 * Project: Combustion Inc. Android Framework
 * File: SimulatedGaugeBleDevice.kt
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

import inc.combustion.framework.ble.scanning.DeviceAdvertisingData
import inc.combustion.framework.ble.scanning.GaugeAdvertisingData
import inc.combustion.framework.ble.uart.meatnet.NodeReadGaugeLogsResponse
import inc.combustion.framework.service.*
import inc.combustion.framework.service.dfu.DfuProductType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.concurrent.fixedRateTimer
import kotlin.random.Random

internal class SimulatedGaugeBleDevice(
    private val scope: CoroutineScope,
    override val mac: String = "%02X:%02X:%02X:%02X:%02X:%02X".format(
        Random.nextBytes(1).first(),
        Random.nextBytes(1).first(),
        Random.nextBytes(1).first(),
        Random.nextBytes(1).first(),
        Random.nextBytes(1).first(),
        Random.nextBytes(1).first(),
    ),
    override val serialNumber: String = "%08X".format(Random.nextInt()),
    var shouldConnect: Boolean = false,
    override val hopCount: UInt = 0u,
) : UartCapableGauge {

    companion object {
        fun randomRSSI(): Int {
            return Random.nextInt(-80, -40)
        }

        fun randomAdvertisement(
            mac: String,
            serialNumber: String,
        ): GaugeAdvertisingData {
            return GaugeAdvertisingData(
                mac = mac,
                name = "Gauge",
                rssi = randomRSSI(),
                isConnectable = true,
                serialNumber = serialNumber,
                gaugeTemperature = SensorTemperature.withRandomData(),
                gaugeStatusFlags = GaugeStatusFlags(
                    sensorPresent = true,
                    sensorOverheating = true,
                    lowBattery = true
                ),
                highLowAlarmStatus = HighLowAlarmStatus(
                    HighLowAlarmStatus.AlarmStatus(
                        set = false,
                        tripped = false,
                        alarming = false,
                        temperature = SensorTemperature(100.0),
                    ),
                    HighLowAlarmStatus.AlarmStatus(
                        set = false,
                        tripped = false,
                        alarming = false,
                        temperature = SensorTemperature(100.0),
                    )
                ),
            )
        }
    }

    override val id: DeviceID = mac

    override val isSimulated: Boolean = true

    override val productType: CombustionProductType = CombustionProductType.GAUGE

    override val isRepeater: Boolean = false

    override var shouldAutoReconnect: Boolean = false

    override var rssi: Int = randomRSSI()
        private set

    override var connectionState: DeviceConnectionState =
        DeviceConnectionState.ADVERTISING_CONNECTABLE
        private set

    override var isConnected: Boolean = false
        private set

    override var isDisconnected: Boolean = true
        private set

    override val isInRange: Boolean = true

    override val isConnectable: Boolean = true

    override var isInDfuMode: Boolean = false

    override var deviceInfoSerialNumber: String? = null
        private set

    override var deviceInfoFirmwareVersion: FirmwareVersion? = null
        private set

    override var deviceInfoHardwareRevision: String? = null
        private set

    override var deviceInfoModelInformation: ModelInformation? = null
        private set

    private var observeAdvertisingCallback: (suspend (advertisement: GaugeAdvertisingData) -> Unit)? =
        null

    private var observeRemoteRssiCallback: (suspend (rssi: Int) -> Unit)? = null

    private var observeConnectionStateCallback: (suspend (newConnectionState: DeviceConnectionState) -> Unit)? =
        null

    init {
        fixedRateTimer(name = "SimAdvertising", initialDelay = 1000, period = 1000) {
            scope.launch {
                observeAdvertisingCallback?.let {
                    if (!isConnected) {
                        it(
                            randomAdvertisement(
                                mac = mac,
                                serialNumber = serialNumber,
                            )
                        )
                    }
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
            productType = CombustionProductType.GAUGE,
            dfuProductType = DfuProductType.GAUGE,
            sku = "ABCDEF",
            manufacturingLot = "98765"
        )
        observeConnectionStateCallback?.let {
            scope.launch {
                it(connectionState)
            }
        }
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

    override suspend fun readSerialNumber() {
        // nothing to do -- handled on connect
    }

    override suspend fun readFirmwareVersion() {
        // nothing to do -- handled on connect
    }

    override suspend fun readHardwareRevision() {
        // nothing to do -- handled on connect
    }

    override suspend fun readModelInformation() {
        // nothing to do -- handled on connect
    }

    override fun readFirmwareVersionAsync(callback: (FirmwareVersion) -> Unit) {
        // nothing to do -- handled on connect
    }

    override fun readHardwareRevisionAsync(callback: (String) -> Unit) {
        // nothing to do -- handled on connect
    }

    override fun readModelInformationAsync(callback: (ModelInformation) -> Unit) {
        // nothing to do -- handled on connect
    }

    override fun observeAdvertisingPackets(
        serialNumberFilter: String,
        macFilter: String,
        callback: (suspend (advertisement: DeviceAdvertisingData) -> Unit)?
    ) {
        observeAdvertisingCallback = callback
    }

    override fun observeRemoteRssi(callback: (suspend (rssi: Int) -> Unit)?) {
        observeRemoteRssiCallback = callback
    }

    override fun observeOutOfRange(timeout: Long, callback: (suspend () -> Unit)?) {
        // simulated probe does not go out of range
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

    override fun sendSetHighLowAlarmStatus(
        highLowAlarmStatus: HighLowAlarmStatus,
        reqId: UInt?,
        callback: ((Boolean, Any?) -> Unit)?
    ) {
        callback?.let { it(true, null) }
    }

    override fun sendGaugeLogRequest(
        minSequence: UInt,
        maxSequence: UInt,
        reqId: UInt?,
        callback: suspend (NodeReadGaugeLogsResponse) -> Unit,
    ) {
        // do nothing
    }
}