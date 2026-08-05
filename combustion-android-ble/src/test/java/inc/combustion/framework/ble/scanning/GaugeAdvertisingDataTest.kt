/*
 * Project: Combustion Inc. Android Framework
 * File: GaugeAdvertisingDataTest.kt
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
import inc.combustion.framework.service.HighLowAlarmStatus
import inc.combustion.framework.service.SensorTemperature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GaugeAdvertisingDataTest {

    private fun buildManufacturerData(
        serialNumber: String = "AB12CD34EF",
        temperature: SensorTemperature = SensorTemperature(150.0),
        statusFlags: UByte = 0b0000_0001u, // sensorPresent
        highLowAlarmStatus: HighLowAlarmStatus = HighLowAlarmStatus.DEFAULT,
        preferences: UByte = 0b0000_0001u, // highRadioPower
        size: Int = 20,
    ): UByteArray {
        val data = UByteArray(size)

        val serialBytes = serialNumber.encodeToByteArray().toUByteArray()
        serialBytes.copyInto(data, destinationOffset = 1)

        temperature.toRawDataStart().copyInto(data, destinationOffset = 11)

        data[13] = statusFlags
        highLowAlarmStatus.toRawData().copyInto(data, destinationOffset = 15)

        if (size > 19) data[19] = preferences

        return data
    }

    @Test
    fun `create parses serial number`() {
        val advertisingData = GaugeAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Gauge",
            rssi = -50,
            isConnectable = true,
            manufacturerData = buildManufacturerData(serialNumber = "AB12CD34EF"),
        )

        assertEquals("AB12CD34EF", advertisingData.serialNumber)
    }

    @Test
    fun `create parses temperature`() {
        val advertisingData = GaugeAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Gauge",
            rssi = -50,
            isConnectable = true,
            manufacturerData = buildManufacturerData(temperature = SensorTemperature(200.0)),
        )

        assertEquals(200.0, advertisingData.gaugeTemperature.value, 0.05)
    }

    @Test
    fun `create parses status flags`() {
        val advertisingData = GaugeAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Gauge",
            rssi = -50,
            isConnectable = true,
            manufacturerData = buildManufacturerData(statusFlags = 0b0000_0110u), // overheating + lowBattery
        )

        assertEquals(false, advertisingData.gaugeStatusFlags.sensorPresent)
        assertEquals(true, advertisingData.gaugeStatusFlags.sensorOverheating)
        assertEquals(true, advertisingData.gaugeStatusFlags.lowBattery)
    }

    @Test
    fun `create parses high low alarm status`() {
        val highLowAlarmStatus = HighLowAlarmStatus(
            highStatus = HighLowAlarmStatus.AlarmStatus(
                set = true,
                tripped = true,
                alarming = false,
                temperature = SensorTemperature(210.0),
            ),
            lowStatus = HighLowAlarmStatus.AlarmStatus(
                set = true,
                tripped = false,
                alarming = false,
                temperature = SensorTemperature(40.0),
            ),
        )

        val advertisingData = GaugeAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Gauge",
            rssi = -50,
            isConnectable = true,
            manufacturerData = buildManufacturerData(highLowAlarmStatus = highLowAlarmStatus),
        )

        assertEquals(true, advertisingData.highLowAlarmStatus.highStatus.set)
        assertEquals(true, advertisingData.highLowAlarmStatus.highStatus.tripped)
        assertEquals(210.0, advertisingData.highLowAlarmStatus.highStatus.temperature.value, 0.1)
        assertEquals(true, advertisingData.highLowAlarmStatus.lowStatus.set)
        assertEquals(false, advertisingData.highLowAlarmStatus.lowStatus.tripped)
        assertEquals(40.0, advertisingData.highLowAlarmStatus.lowStatus.temperature.value, 0.1)
    }

    @Test
    fun `create parses preferences`() {
        val advertisingData = GaugeAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Gauge",
            rssi = -50,
            isConnectable = true,
            manufacturerData = buildManufacturerData(preferences = 0b0000_0001u),
        )

        assertEquals(true, advertisingData.gaugePreferences?.highRadioPower)
    }

    @Test
    fun `create leaves preferences null when byte is not present`() {
        val advertisingData = GaugeAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Gauge",
            rssi = -50,
            isConnectable = true,
            manufacturerData = buildManufacturerData(size = 19),
        )

        assertNull(advertisingData.gaugePreferences)
    }

    @Test
    fun `create sets base advertising data fields`() {
        val advertisingData = GaugeAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Gauge",
            rssi = -42,
            isConnectable = false,
            manufacturerData = buildManufacturerData(),
        )

        assertEquals("AA:BB:CC:DD:EE:FF", advertisingData.mac)
        assertEquals("Gauge", advertisingData.name)
        assertEquals(-42, advertisingData.rssi)
        assertEquals(false, advertisingData.isConnectable)
        assertEquals(CombustionProductType.GAUGE, advertisingData.productType)
    }
}
