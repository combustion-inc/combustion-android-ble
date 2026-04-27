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

import inc.combustion.framework.ble.UartCapableEngine
import inc.combustion.framework.ble.scanning.DeviceAdvertisingData
import inc.combustion.framework.ble.scanning.EngineAdvertisingData
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.EnginePreferences
import inc.combustion.framework.service.EngineStatusFlags
import inc.combustion.framework.service.SensorTemperature
import kotlinx.coroutines.CoroutineScope
import kotlin.random.Random

internal class SimulatedEngineBleDevice(
    scope: CoroutineScope,
    mac: String = randomMac(),
    serialNumber: String = "%08X".format(Random.nextInt()),
    shouldConnect: Boolean = false,
    hopCount: UInt = 0u,
) : SimulatedNodeHybridBleDevice(
    scope = scope,
    mac = mac,
    serialNumber = serialNumber,
    shouldConnect = shouldConnect,
    hopCount = hopCount,
), UartCapableEngine {

    companion object {
        fun randomAdvertisement(
            mac: String,
            serialNumber: String,
        ): EngineAdvertisingData {
            return EngineAdvertisingData(
                mac = mac,
                name = "Engine",
                rssi = randomRSSI(),
                isConnectable = true,
                serialNumber = serialNumber,
                engineTemperature = SensorTemperature.withRandomData(),
                engineStatusFlags = EngineStatusFlags(),
                enginePreferences = EnginePreferences(),
            )
        }
    }

    override val productType: CombustionProductType = CombustionProductType.ENGINE

    override fun generateAdvertisement(): DeviceAdvertisingData =
        randomAdvertisement(mac, serialNumber)

    override fun sendSetTemperatureSetPoint(
        temperature: SensorTemperature,
        reqId: UInt?,
        callback: ((Boolean, Any?) -> Unit)?,
    ) {
        callback?.let { it(true, null) }
    }

    override fun sendSetControlDevice(
        controlDeviceType: CombustionProductType,
        controlSerialNumber: String,
        reqId: UInt?,
        callback: ((Boolean, Any?) -> Unit)?,
    ) {
        callback?.let { it(true, null) }
    }
}
