/*
 * Project: Combustion Inc. Android Framework
 * File: NodeSetEngineTemperatureSetPointRequestTest.kt
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

import inc.combustion.framework.service.SensorTemperature
import inc.combustion.framework.utf8StringFromRange
import org.junit.Assert.assertEquals
import org.junit.Test

class NodeSetEngineTemperatureSetPointRequestTest {

    private val serialRange = 0..9

    @Test
    fun `populatePayload has the expected fixed length`() {
        val payload = NodeSetEngineTemperatureSetPointRequest.populatePayload(
            serialNumber = "ABCDEF1234",
            temperature = SensorTemperature(150.0),
        )

        assertEquals(12, payload.size)
    }

    @Test
    fun `populatePayload encodes the serial number at the start`() {
        val payload = NodeSetEngineTemperatureSetPointRequest.populatePayload(
            serialNumber = "ABCDEF1234",
            temperature = SensorTemperature(150.0),
        )

        assertEquals("ABCDEF1234", payload.utf8StringFromRange(serialRange))
    }

    @Test
    fun `populatePayload encodes the temperature at the end, round-tripping through SensorTemperature`() {
        val temperature = SensorTemperature(72.5)

        val payload = NodeSetEngineTemperatureSetPointRequest.populatePayload(
            serialNumber = "ABCDEF1234",
            temperature = temperature,
        )

        val tempBytes = payload.copyOfRange(payload.size - 2, payload.size)
        assertEquals(temperature, SensorTemperature.fromRawDataStart(tempBytes))
    }

    @Test
    fun `populatePayload with a different serial number and temperature`() {
        val temperature = SensorTemperature(-15.0)

        val payload = NodeSetEngineTemperatureSetPointRequest.populatePayload(
            serialNumber = "1000DA4C",
            temperature = temperature,
        )

        assertEquals("1000DA4C", payload.utf8StringFromRange(serialRange))
        val tempBytes = payload.copyOfRange(payload.size - 2, payload.size)
        assertEquals(temperature, SensorTemperature.fromRawDataStart(tempBytes))
    }
}
