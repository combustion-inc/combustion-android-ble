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

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import inc.combustion.framework.ble.scanning.DeviceAdvertisingData
import inc.combustion.framework.ble.scanning.GaugeAdvertisingData
import inc.combustion.framework.service.GaugeStatus
import inc.combustion.framework.service.HighLowAlarmStatus
import inc.combustion.framework.service.Temperature
import kotlinx.coroutines.launch
import kotlin.concurrent.fixedRateTimer
import kotlin.random.Random

internal class SimulatedGaugeBleDevice(
    private val owner: LifecycleOwner,
    val mac: String = "%02X:%02X:%02X:%02X:%02X:%02X".format(
        Random.nextBytes(1).first(),
        Random.nextBytes(1).first(),
        Random.nextBytes(1).first(),
        Random.nextBytes(1).first(),
        Random.nextBytes(1).first(),
        Random.nextBytes(1).first()
    ),
    override val serialNumber: String = "%08X".format(Random.nextInt()),
    var shouldConnect: Boolean = false
) : GaugeBleBase() {

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
                gaugeTemperature = Temperature.withRandomData(),
                gaugeStatus = GaugeStatus(
                    sensorPresent = true,
                    sensorOverheating = true,
                    lowBattery = true
                ),
                batteryPercentage = 50,
                highLowAlarmStatus = HighLowAlarmStatus(
                    HighLowAlarmStatus.AlarmStatus(
                        set = false,
                        tripped = false,
                        alarming = false,
                        temperature = Temperature(100.0),
                    ),
                    HighLowAlarmStatus.AlarmStatus(
                        set = false,
                        tripped = false,
                        alarming = false,
                        temperature = Temperature(100.0),
                    )
                ),
            )
        }
    }

    override val id: DeviceID
        get() = mac

    override var isConnected: Boolean = false
        private set

    private var observeAdvertisingCallback: (suspend (advertisement: GaugeAdvertisingData) -> Unit)? =
        null

    init {
        fixedRateTimer(name = "SimAdvertising", initialDelay = 1000, period = 1000) {
            owner.lifecycleScope.launch {
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
//        isDisconnected = false
        isConnected = true
//        connectionState = DeviceConnectionState.CONNECTED
//        deviceInfoSerialNumber = probeSerialNumber
//        deviceInfoFirmwareVersion = FirmwareVersion(1, 2, 3, null, null)
//        deviceInfoHardwareRevision = "v2.3.4"
//        deviceInfoModelInformation = ModelInformation(
//            productType = CombustionProductType.PROBE,
//            dfuProductType = DfuProductType.PROBE,
//            sku = "ABCDEF",
//            manufacturingLot = "98765"
//        )
//        observeConnectionStateCallback?.let {
//            owner.lifecycleScope.launch {
//                it(connectionState)
//            }
//        }
//        publishConnectionState()
    }

    override fun disconnect() {
//        isDisconnected = true
        isConnected = false
//        deviceInfoSerialNumber = null
//        deviceInfoFirmwareVersion = null
//        deviceInfoHardwareRevision = null
//        deviceInfoModelInformation = null
//        connectionState = DeviceConnectionState.ADVERTISING_CONNECTABLE
//        publishConnectionState()
    }

    override fun observeAdvertisingPackets(
        serialNumberFilter: String,
        macFilter: String,
        callback: (suspend (advertisement: DeviceAdvertisingData) -> Unit)?
    ) {
        observeAdvertisingCallback = callback
    }
}