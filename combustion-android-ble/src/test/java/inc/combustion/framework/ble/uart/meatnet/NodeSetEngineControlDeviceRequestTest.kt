/*
 * Project: Combustion Inc. Android Framework
 * File: NodeSetEngineControlDeviceRequestTest.kt
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

import inc.combustion.framework.ble.getLittleEndianUInt32At
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.utf8StringFromRange
import org.junit.Assert.assertEquals
import org.junit.Test

class NodeSetEngineControlDeviceRequestTest {

    private val engineSerialRange = 0..9
    private val controlDeviceTypeIdx = 10
    private val controlSerialIdx = 11

    @Test
    fun `populatePayload has the expected fixed length`() {
        val payload = NodeSetEngineControlDeviceRequest.populatePayload(
            serialNumber = "ABCDEF1234",
            controlDeviceType = CombustionProductType.PROBE,
            controlSerialNumber = "1000DA4C",
        )

        assertEquals(23, payload.size)
    }

    @Test
    fun `populatePayload encodes the engine serial number and control device type`() {
        val payload = NodeSetEngineControlDeviceRequest.populatePayload(
            serialNumber = "ABCDEF1234",
            controlDeviceType = CombustionProductType.GAUGE,
            controlSerialNumber = "GHIJKL6789",
        )

        assertEquals("ABCDEF1234", payload.utf8StringFromRange(engineSerialRange))
        assertEquals(CombustionProductType.GAUGE.type, payload[controlDeviceTypeIdx])
    }

    @Test
    fun `populatePayload encodes a probe control serial number as a little-endian UInt32`() {
        val payload = NodeSetEngineControlDeviceRequest.populatePayload(
            serialNumber = "ABCDEF1234",
            controlDeviceType = CombustionProductType.PROBE,
            controlSerialNumber = "1000DA4C",
        )

        assertEquals(
            0x1000DA4Cu,
            payload.getLittleEndianUInt32At(controlSerialIdx),
        )
    }

    @Test
    fun `populatePayload encodes a non-probe control serial number as UTF-8`() {
        val payload = NodeSetEngineControlDeviceRequest.populatePayload(
            serialNumber = "ABCDEF1234",
            controlDeviceType = CombustionProductType.GAUGE,
            controlSerialNumber = "GHIJKL6789",
        )

        assertEquals(
            "GHIJKL6789",
            payload.utf8StringFromRange(controlSerialIdx until (controlSerialIdx + 10)),
        )
    }

    @Test
    fun `populatePayload zeroes the control serial range for an invalid probe serial`() {
        val payload = NodeSetEngineControlDeviceRequest.populatePayload(
            serialNumber = "ABCDEF1234",
            controlDeviceType = CombustionProductType.PROBE,
            controlSerialNumber = "not-hex",
        )

        assertEquals(0u, payload.getLittleEndianUInt32At(controlSerialIdx))
    }
}
