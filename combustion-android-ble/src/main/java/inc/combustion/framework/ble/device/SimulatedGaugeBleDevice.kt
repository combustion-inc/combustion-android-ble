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

import inc.combustion.framework.ble.GaugeStatus
import inc.combustion.framework.ble.SpecializedDeviceStatus
import inc.combustion.framework.ble.scanning.DeviceAdvertisingData
import inc.combustion.framework.ble.scanning.GaugeAdvertisingData
import inc.combustion.framework.ble.uart.meatnet.NodeReadGaugeLogsResponse
import inc.combustion.framework.service.*
import kotlinx.coroutines.CoroutineScope
import kotlin.random.Random

internal class SimulatedGaugeBleDevice(
    scope: CoroutineScope,
    mac: String = SimulatedBleDeviceValues.randomMac(),
    serialNumber: String = SimulatedBleDeviceValues.randomSerialNumber(),
    shouldConnect: Boolean = false,
    hopCount: UInt = 0u,
) : SimulatedNodeHybridBleDevice(
    scope = scope,
    mac = mac,
    serialNumber = serialNumber,
    shouldConnect = shouldConnect,
    hopCount = hopCount,
), UartCapableGauge {

    companion object {
        fun randomAdvertisement(
            mac: String,
            serialNumber: String,
        ): GaugeAdvertisingData {
            return GaugeAdvertisingData(
                mac = mac,
                name = "Gauge",
                rssi = SimulatedBleDeviceValues.randomRSSI(),
                isConnectable = true,
                serialNumber = serialNumber,
                gaugeTemperature = SensorTemperature.withRandomData(),
                gaugeStatusFlags = GaugeStatusFlags(
                    sensorPresent = true,
                    sensorOverheating = true,
                    lowBattery = true,
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
                    ),
                ),
            )
        }
    }

    override val productType: CombustionProductType = CombustionProductType.GAUGE

    override fun generateAdvertisement(): DeviceAdvertisingData = randomAdvertisement(mac, serialNumber)

    override fun generateStatus(): SpecializedDeviceStatus = GaugeStatus(
        sessionInformation = SessionInformation(sessionID = 1u, samplePeriod = 1u),
        samplePeriod = 1u,
        temperature = SensorTemperature.withRandomData(),
        gaugeStatusFlags = GaugeStatusFlags(
            sensorPresent = true,
            sensorOverheating = false,
            lowBattery = false,
        ),
        minSequenceNumber = 0u,
        maxSequenceNumber = 0u,
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
            ),
        ),
        isNewRecord = true,
        hopCount = HopCount.HOP1,
    )

    override fun sendSetHighLowAlarmStatus(
        highLowAlarmStatus: HighLowAlarmStatus,
        reqId: UInt?,
        callback: ((Boolean, Any?) -> Unit)?,
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
