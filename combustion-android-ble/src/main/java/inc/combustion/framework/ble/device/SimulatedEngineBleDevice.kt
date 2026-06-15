/*
 * Project: Combustion Inc. Android Framework
 * File: SimulatedEngineBleDevice.kt
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

import inc.combustion.framework.ble.EngineStatus
import inc.combustion.framework.ble.SpecializedDeviceStatus
import inc.combustion.framework.ble.UartCapableEngine
import inc.combustion.framework.ble.scanning.DeviceAdvertisingData
import inc.combustion.framework.ble.scanning.EngineAdvertisingData
import inc.combustion.framework.service.*
import kotlinx.coroutines.*

internal class SimulatedEngineBleDevice(
    scope: CoroutineScope,
    mac: String = SimulatedBleDeviceValues.randomMac(),
    serialNumber: String = SimulatedBleDeviceValues.randomMac(),
    shouldConnect: Boolean = false,
    hopCount: UInt = 0u,
    private val appMode: Boolean = true,
) : SimulatedNodeHybridBleDevice(
    scope = scope,
    mac = mac,
    serialNumber = serialNumber,
    shouldConnect = shouldConnect,
    hopCount = hopCount,
), UartCapableEngine {

    override val productType: CombustionProductType = CombustionProductType.ENGINE

    private var sequenceNumber = 0u
    private var controlSerialNumber: String? = null
    private var controlDeviceType: CombustionProductType? = null
    private var temperatureSetPoint: SensorTemperature? = null

    override fun generateAdvertisement(): DeviceAdvertisingData =
        randomAdvertisement(mac, serialNumber)

    private fun randomAdvertisement(
        mac: String,
        serialNumber: String,
    ): EngineAdvertisingData {
        return EngineAdvertisingData(
            mac = mac,
            name = "Engine",
            rssi = SimulatedBleDeviceValues.randomRSSI(),
            isConnectable = true,
            serialNumber = serialNumber,
            engineTemperatureSetPoint = temperatureSetPoint ?: SensorTemperature.withRandomData(),
            engineStatusFlags = EngineStatusFlags(
                appMode = appMode,
                controlDeviceConnected = controlDeviceType != null,
            ),
            enginePreferences = EnginePreferences(),
        )
    }

    override fun generateStatus(): SpecializedDeviceStatus = EngineStatus(
        sessionInformation = SessionInformation(sessionID = 1u, samplePeriod = 1u),
        samplePeriod = 5000u,
        minSequenceNumber = 0u,
        maxSequenceNumber = sequenceNumber++,
        engineBatteryStatus = EngineBatteryStatus(),
        temperatureSetPoint = temperatureSetPoint ?: SensorTemperature.withRandomData(60.0, 300.0),
        controlTemperature = SensorTemperature.NO_DATA,
        controlDeviceType = controlDeviceType,
        controlSerialNumber = controlSerialNumber,
        engineStatusFlags = EngineStatusFlags(
            appMode = appMode,
            controlDeviceConnected = !controlSerialNumber.isNullOrEmpty(),
        ),
        engineFanStatus = EngineFanStatus(
            fanState = EngineFanState.FAN_ON,
            dutyCycle = 0u,
            commandedSpeed = randomPercent(),
            measuredSpeed = randomPercent(),
            fanOffTimeMs = 0u,
            fanOnTimeMs = 0u,
        ),
        hopCount = HopCount.HOP1,
        knobVoltageMillivolts = 0u,
        knobAngleTenthsDegrees = 0u,
    )

    override fun sendSetTemperatureSetPoint(
        temperature: SensorTemperature,
        reqId: UInt?,
        callback: ((Boolean, Any?) -> Unit)?,
    ) {
        GlobalScope.launch {
            delay(100)
            this@SimulatedEngineBleDevice.temperatureSetPoint = temperature
            withContext(Dispatchers.Main) {
                callback?.let { it(true, null) }
            }
        }
    }

    override fun sendSetControlDevice(
        controlDeviceType: CombustionProductType,
        controlSerialNumber: String,
        reqId: UInt?,
        callback: ((Boolean, Any?) -> Unit)?,
    ) {
        GlobalScope.launch {
            delay(100)
            this@SimulatedEngineBleDevice.controlSerialNumber =
                controlSerialNumber.takeIf { it.isNotEmpty() }
            this@SimulatedEngineBleDevice.controlDeviceType = controlDeviceType
            withContext(Dispatchers.Main) {
                callback?.let { it(true, null) }
            }
        }
    }

    private fun randomPercent(): UByte = (0..100).random().toUByte()
}
