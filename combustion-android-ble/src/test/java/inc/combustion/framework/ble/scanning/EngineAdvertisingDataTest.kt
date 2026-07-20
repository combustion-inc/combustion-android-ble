/*
 * Project: Combustion Inc. Android Framework
 * File: EngineAdvertisingDataTest.kt
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

package inc.combustion.framework.ble.scanning

import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.SensorTemperature
import org.junit.Assert.assertEquals
import org.junit.Test

class EngineAdvertisingDataTest {

    private fun buildManufacturerData(
        serialNumber: String = "AB12CD34EF",
        temperatureSetPoint: SensorTemperature = SensorTemperature(150.0),
        statusFlags: UByte = 0b0000_0011u, // appMode + controlDeviceConnected
        preferences: UByte = 0b0000_0001u, // highRadioPower
    ): UByteArray {
        val data = UByteArray(15)

        val serialBytes = serialNumber.encodeToByteArray().toUByteArray()
        serialBytes.copyInto(data, destinationOffset = 1)

        temperatureSetPoint.toRawDataStart().copyInto(data, destinationOffset = 11)

        data[13] = statusFlags
        data[14] = preferences

        return data
    }

    @Test
    fun `create parses serial number`() {
        val advertisingData = EngineAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Engine",
            rssi = -50,
            isConnectable = true,
            manufacturerData = buildManufacturerData(serialNumber = "AB12CD34EF"),
        )

        assertEquals("AB12CD34EF", advertisingData.serialNumber)
    }

    @Test
    fun `create parses temperature set point`() {
        val advertisingData = EngineAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Engine",
            rssi = -50,
            isConnectable = true,
            manufacturerData = buildManufacturerData(
                temperatureSetPoint = SensorTemperature(200.0),
            ),
        )

        assertEquals(200.0, advertisingData.engineTemperatureSetPoint.value, 0.05)
    }

    @Test
    fun `create parses status flags`() {
        val advertisingData = EngineAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Engine",
            rssi = -50,
            isConnectable = true,
            manufacturerData = buildManufacturerData(statusFlags = 0b0000_0100u), // lidOpen only
        )

        assertEquals(false, advertisingData.engineStatusFlags.appMode)
        assertEquals(false, advertisingData.engineStatusFlags.controlDeviceConnected)
        assertEquals(true, advertisingData.engineStatusFlags.lidOpen)
        assertEquals(false, advertisingData.engineStatusFlags.fixedSpeed)
    }

    @Test
    fun `create parses preferences`() {
        val advertisingData = EngineAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Engine",
            rssi = -50,
            isConnectable = true,
            manufacturerData = buildManufacturerData(preferences = 0b0000_0000u),
        )

        assertEquals(false, advertisingData.enginePreferences.highRadioPower)
    }

    @Test
    fun `create sets base advertising data fields`() {
        val advertisingData = EngineAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Engine",
            rssi = -42,
            isConnectable = false,
            manufacturerData = buildManufacturerData(),
        )

        assertEquals("AA:BB:CC:DD:EE:FF", advertisingData.mac)
        assertEquals("Engine", advertisingData.name)
        assertEquals(-42, advertisingData.rssi)
        assertEquals(false, advertisingData.isConnectable)
        assertEquals(CombustionProductType.ENGINE, advertisingData.productType)
    }
}
