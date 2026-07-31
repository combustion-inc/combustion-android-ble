/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeAdvertisingDataTest.kt
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
import inc.combustion.framework.service.HopCount
import inc.combustion.framework.service.ProbeBatteryStatus
import inc.combustion.framework.service.ProbeColor
import inc.combustion.framework.service.ProbeID
import inc.combustion.framework.service.ProbeMode
import inc.combustion.framework.service.ProbePowerMode
import inc.combustion.framework.service.ProbeVirtualSensors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeAdvertisingDataTest {

    companion object {
        // All-zero raw counts decode (via the (count * 0.05) - 20.0 formula) to -20.0C on every
        // channel -- below every overheating threshold.
        private val COLD_RAW_TEMPERATURES = UByteArray(13)

        // Same bytes used in ProbeStatusTest -- decodes to values where T3 (258.4C) and T4
        // (160.55C) exceed their overheating thresholds (115C and 125C respectively).
        private val HOT_RAW_TEMPERATURES = ubyteArrayOf(
            0x89u, 0x00u, 0xDAu, 0x00u, 0xD7u,
            0x0Du, 0x07u, 0x33u, 0x05u, 0xD5u,
            0x18u, 0x74u, 0x1Au,
        )
    }

    private fun buildManufacturerData(
        serialBytes: UByteArray = ubyteArrayOf(0x78u, 0x56u, 0x34u, 0x12u), // little-endian
        rawTemperatures: UByteArray = COLD_RAW_TEMPERATURES,
        modeColorId: UByte = 0b101_110_01u, // COLOR7 / ID6 / INSTANT_READ
        deviceStatus: UByte = 0b10_11_101_1u, // LOW_BATTERY / T6 core / T7 surface / T7 ambient
        networkInfo: UByte = 0b1000_0000u, // HOP3
        overheatByte: UByte = 0b0000_0000u,
        preferences: UByte = 0b0000_0100u, // NORMAL power mode, highRadioPower set
        size: Int = 23,
    ): UByteArray {
        val data = UByteArray(size)
        serialBytes.copyInto(data, destinationOffset = 1)
        rawTemperatures.copyInto(data, destinationOffset = 5)
        if (size > 18) data[18] = modeColorId
        if (size > 19) data[19] = deviceStatus
        if (size > 20) data[20] = networkInfo
        if (size > 21) data[21] = overheatByte
        if (size > 22) data[22] = preferences
        return data
    }

    @Test
    fun `create parses serial number`() {
        val advertisingData = ProbeAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Probe",
            rssi = -50,
            isConnectable = true,
            manufacturerData = buildManufacturerData(),
            type = CombustionProductType.PROBE,
        )

        assertEquals("12345678", advertisingData.serialNumber)
    }

    @Test
    fun `create passes through a zero serial number unpadded`() {
        val advertisingData = ProbeAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Node",
            rssi = -50,
            isConnectable = true,
            manufacturerData = buildManufacturerData(serialBytes = ubyteArrayOf(0u, 0u, 0u, 0u)),
            type = CombustionProductType.NODE,
        )

        assertEquals("0", advertisingData.serialNumber)
    }

    @Test
    fun `create parses temperatures`() {
        val advertisingData = ProbeAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Probe",
            rssi = -50,
            isConnectable = true,
            manufacturerData = buildManufacturerData(rawTemperatures = HOT_RAW_TEMPERATURES),
            type = CombustionProductType.PROBE,
        )

        assertEquals(258.4, advertisingData.probeTemperatures.values[2], 0.05)
        assertEquals(160.55, advertisingData.probeTemperatures.values[3], 0.05)
    }

    @Test
    fun `create parses mode color and id`() {
        val advertisingData = ProbeAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Probe",
            rssi = -50,
            isConnectable = true,
            manufacturerData = buildManufacturerData(),
            type = CombustionProductType.PROBE,
        )

        assertEquals(ProbeMode.INSTANT_READ, advertisingData.mode)
        assertEquals(ProbeColor.COLOR7, advertisingData.color)
        assertEquals(ProbeID.ID6, advertisingData.probeID)
    }

    @Test
    fun `create defaults mode color and id when byte is not present`() {
        val advertisingData = ProbeAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Probe",
            rssi = -50,
            isConnectable = true,
            manufacturerData = buildManufacturerData(size = 18),
            type = CombustionProductType.PROBE,
        )

        assertEquals(ProbeMode.NORMAL, advertisingData.mode)
        assertEquals(ProbeColor.COLOR1, advertisingData.color)
        assertEquals(ProbeID.ID1, advertisingData.probeID)
    }

    @Test
    fun `create parses battery status and virtual sensors`() {
        val advertisingData = ProbeAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Probe",
            rssi = -50,
            isConnectable = true,
            manufacturerData = buildManufacturerData(),
            type = CombustionProductType.PROBE,
        )

        assertEquals(ProbeBatteryStatus.LOW_BATTERY, advertisingData.batteryStatus)
        assertEquals(
            ProbeVirtualSensors.VirtualCoreSensor.T6,
            advertisingData.virtualSensors.virtualCoreSensor,
        )
        assertEquals(
            ProbeVirtualSensors.VirtualSurfaceSensor.T7,
            advertisingData.virtualSensors.virtualSurfaceSensor,
        )
        assertEquals(
            ProbeVirtualSensors.VirtualAmbientSensor.T7,
            advertisingData.virtualSensors.virtualAmbientSensor,
        )
    }

    @Test
    fun `create defaults battery status and virtual sensors when byte is not present`() {
        val advertisingData = ProbeAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Probe",
            rssi = -50,
            isConnectable = true,
            manufacturerData = buildManufacturerData(size = 19),
            type = CombustionProductType.PROBE,
        )

        assertEquals(ProbeBatteryStatus.OK, advertisingData.batteryStatus)
        assertEquals(ProbeVirtualSensors.DEFAULT, advertisingData.virtualSensors)
    }

    @Test
    fun `create parses hop count for a repeater`() {
        val advertisingData = ProbeAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Node",
            rssi = -50,
            isConnectable = true,
            manufacturerData = buildManufacturerData(networkInfo = 0b1000_0000u), // HOP3
            type = CombustionProductType.NODE,
        )

        assertEquals(HopCount.HOP3.hopCount, advertisingData.hopCount)
    }

    @Test
    fun `create ignores hop count for a probe`() {
        val advertisingData = ProbeAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Probe",
            rssi = -50,
            isConnectable = true,
            manufacturerData = buildManufacturerData(networkInfo = 0b1000_0000u), // HOP3
            type = CombustionProductType.PROBE,
        )

        assertEquals(0u, advertisingData.hopCount)
    }

    @Test
    fun `create ignores the raw overheating byte, even when it disagrees with hot temperatures`() {
        val advertisingData = ProbeAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Probe",
            rssi = -50,
            isConnectable = true,
            manufacturerData = buildManufacturerData(
                rawTemperatures = HOT_RAW_TEMPERATURES, // T3 (index 2) and T4 (index 3) exceed thresholds
                overheatByte = 0b0000_0101u, // raw byte disagrees (reports T1 and T3) -- ignored
            ),
            type = CombustionProductType.PROBE,
        )

        assertEquals(listOf(2, 3), advertisingData.overheatingSensors.values)
    }

    @Test
    fun `create ignores the raw overheating byte when no temperature exceeds its threshold`() {
        val advertisingData = ProbeAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Probe",
            rssi = -50,
            isConnectable = true,
            manufacturerData = buildManufacturerData(
                rawTemperatures = COLD_RAW_TEMPERATURES, // nothing exceeds a threshold
                overheatByte = 0b1111_1111u, // simulated repeater firmware bug: all flagged
            ),
            type = CombustionProductType.PROBE,
        )

        assertTrue(advertisingData.overheatingSensors.values.isEmpty())
    }

    @Test
    fun `create falls back to temperature-derived overheating when byte is not present`() {
        val advertisingData = ProbeAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Probe",
            rssi = -50,
            isConnectable = true,
            manufacturerData = buildManufacturerData(
                rawTemperatures = HOT_RAW_TEMPERATURES,
                size = 21,
            ),
            type = CombustionProductType.PROBE,
        )

        assertEquals(listOf(2, 3), advertisingData.overheatingSensors.values)
    }

    @Test
    fun `create parses thermometer preferences`() {
        val advertisingData = ProbeAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Probe",
            rssi = -50,
            isConnectable = true,
            manufacturerData = buildManufacturerData(preferences = 0b0000_0101u), // ALWAYS_ON + highRadioPower
            type = CombustionProductType.PROBE,
        )

        assertEquals(ProbePowerMode.ALWAYS_ON, advertisingData.thermometerPreferences?.powerMode)
        assertEquals(true, advertisingData.thermometerPreferences?.highRadioPower)
    }

    @Test
    fun `create leaves thermometer preferences null when byte is not present`() {
        val advertisingData = ProbeAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Probe",
            rssi = -50,
            isConnectable = true,
            manufacturerData = buildManufacturerData(size = 22),
            type = CombustionProductType.PROBE,
        )

        assertNull(advertisingData.thermometerPreferences)
    }

    @Test
    fun `create sets base advertising data fields`() {
        val advertisingData = ProbeAdvertisingData.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Probe",
            rssi = -42,
            isConnectable = false,
            manufacturerData = buildManufacturerData(),
            type = CombustionProductType.PROBE,
        )

        assertEquals("AA:BB:CC:DD:EE:FF", advertisingData.mac)
        assertEquals("Probe", advertisingData.name)
        assertEquals(-42, advertisingData.rssi)
        assertEquals(false, advertisingData.isConnectable)
        assertEquals(CombustionProductType.PROBE, advertisingData.productType)
    }
}
