/*
 * Project: Combustion Inc. Android Framework
 * File: NodeEngineStatusRequestTest.kt
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

import inc.combustion.framework.ble.EngineStatus
import inc.combustion.framework.ble.putLittleEndianUInt32At
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeEngineStatusRequestTest {

    private val headerSize = NodeRequest.HEADER_SIZE.toInt()
    private val serialNumberLength = 10

    /**
     * Builds a raw packet with [headerSize] arbitrary header bytes (unvalidated by
     * [NodeEngineStatusRequest.fromRaw] itself -- that's [NodeRequest.requestFromData]'s job),
     * followed by a 10-byte UTF-8 serial number, followed by a valid (all-zero, but parseable)
     * [EngineStatus] payload with [sessionId] patched in so the embedded status can be told apart
     * from an all-zero one.
     */
    private fun buildRawRequest(serialNumber: String, sessionId: UInt = 0u): UByteArray {
        val data = UByteArray(headerSize + serialNumberLength + EngineStatus.RAW_SIZE)

        val serialBytes = serialNumber.encodeToByteArray().toUByteArray()
        serialBytes.copyInto(data, destinationOffset = headerSize)

        data.putLittleEndianUInt32At(headerSize + serialNumberLength, sessionId)

        return data
    }

    @Test
    fun `fromRaw returns null when payloadLength is below the minimum`() {
        val data = buildRawRequest("ABCDEF1234")
        val tooShortPayloadLength = (serialNumberLength + EngineStatus.RAW_SIZE - 1).toUByte()

        assertNull(NodeEngineStatusRequest.fromRaw(data, requestId = 1u, payloadLength = tooShortPayloadLength))
    }

    @Test
    fun `fromRaw returns null when the embedded engine status is too short`() {
        val data = UByteArray(headerSize + serialNumberLength + EngineStatus.RAW_SIZE - 1)
        val payloadLength = (serialNumberLength + EngineStatus.RAW_SIZE).toUByte()

        assertNull(NodeEngineStatusRequest.fromRaw(data, requestId = 1u, payloadLength = payloadLength))
    }

    @Test
    fun `fromRaw parses serial number and requestId`() {
        val data = buildRawRequest("abcdef1234")
        val payloadLength = (serialNumberLength + EngineStatus.RAW_SIZE).toUByte()

        val request = NodeEngineStatusRequest.fromRaw(data, requestId = 42u, payloadLength = payloadLength)

        // NodeEngineStatusRequest.fromRaw uppercases the parsed serial number.
        assertEquals("ABCDEF1234", request?.serialNumber)
        assertEquals(42u, request?.requestId)
    }

    @Test
    fun `fromRaw parses the embedded engine status`() {
        val data = buildRawRequest("ABCDEF1234", sessionId = 0xCAFEBABEu)
        val payloadLength = (serialNumberLength + EngineStatus.RAW_SIZE).toUByte()

        val request = NodeEngineStatusRequest.fromRaw(data, requestId = 1u, payloadLength = payloadLength)

        assertEquals(0xCAFEBABEu, request?.engineStatus?.sessionInformation?.sessionID)
    }
}
