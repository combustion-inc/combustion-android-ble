/*
 * Project: Combustion Inc. Android Framework
 * File: NodeHeartbeatRequestTest.kt
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

package inc.combustion.framework.ble.uart.meatnet

import inc.combustion.framework.ble.putLittleEndianUInt32At
import inc.combustion.framework.service.CombustionProductType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Layout per the MeatNet Node BLE specification's "Heartbeat Message (0x49)": a 19-byte header
 * followed by four 13-byte connection detail records.
 */
class NodeHeartbeatRequestTest {

    @Test
    fun `heartbeat header and connection records are parsed`() {
        val payload = UByteArray(71)
        "DS10001234".encodeToByteArray().toUByteArray().copyInto(payload, 0)
        ubyteArrayOf(0xAAu, 0xBBu, 0xCCu, 0x01u, 0x02u, 0x03u).copyInto(payload, 10)
        payload[16] = CombustionProductType.DISPLAY.type
        payload[17] = 2u // hop count
        payload[18] = 1u // inbound

        // Probe: serial number as a little-endian UInt32 in bytes 0-3.
        record(payload, 0, CombustionProductType.PROBE, rssi = -60) { it.putLittleEndianUInt32At(0, 0x1000DA4Cu) }
        // Booster: 10-character serial number.
        record(payload, 1, CombustionProductType.CHARGER, rssi = -85) {
            "CR10007379".encodeToByteArray().toUByteArray().copyInto(it)
        }
        // Slot 2 left empty (attributes bit 0 clear). Slot 3: a shorter serial number padded with \0.
        record(payload, 3, CombustionProductType.DISPLAY, rssi = 5) {
            "DS1".encodeToByteArray().toUByteArray().copyInto(it)
        }

        val request = NodeHeartbeatRequest.fromRaw(UByteArray(10) + payload, requestId = 1u, payloadLength = 71u)!!

        assertEquals("DS10001234", request.serialNumber)
        assertEquals("AA:BB:CC:01:02:03", request.macAddress)
        assertEquals(CombustionProductType.DISPLAY, request.productType)
        assertEquals(2u, request.hopCount)
        assertEquals(true, request.inbound)

        val (probe, booster, empty, display) = request.connectionDetails
        assertEquals("1000DA4C", probe.serialNumber)
        assertEquals(CombustionProductType.PROBE, probe.productType)
        assertEquals(-60, probe.rssi)
        assertEquals("CR10007379", booster.serialNumber)
        assertEquals(CombustionProductType.CHARGER, booster.productType)
        assertEquals(-85, booster.rssi)
        assertFalse(empty.present)
        assertEquals("DS1", display.serialNumber)
        assertEquals(5, display.rssi)
    }

    private fun record(
        payload: UByteArray,
        index: Int,
        productType: CombustionProductType,
        rssi: Int,
        writeSerialNumber: (UByteArray) -> Unit,
    ) {
        val record = UByteArray(13)
        writeSerialNumber(record)
        record[10] = productType.type
        record[11] = 0x01u // populated
        record[12] = rssi.toByte().toUByte()
        record.copyInto(payload, 19 + index * 13)
    }
}
