/*
 * Project: Combustion Inc. Android Framework
 * File: NodeProbeStatusRequestTest.kt
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

import inc.combustion.framework.service.HopCount
import inc.combustion.framework.service.SessionInformation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NodeProbeStatusRequestTest {

    @Test
    fun `fromRaw parses session information relayed by newer node firmware`() {
        val request = NodeProbeStatusRequest.fromRaw(
            GOLDEN_REQUEST,
            requestId = 0xA1B2C3D4u,
            payloadLength = FULL_PAYLOAD_LENGTH,
        )

        assertEquals("12345678", request?.serialNumber)
        assertEquals(HopCount.HOP1, request?.hopCount)
        assertEquals(0x05060708u, request?.probeStatus?.maxSequenceNumber)
        assertNotNull(request?.probeStatus?.probeHighLowAlarmStatus)
        assertEquals(
            SessionInformation(sessionID = 0xDEADBEEFu, samplePeriod = 5000u),
            request?.probeStatus?.sessionInformation,
        )
    }

    @Test
    fun `fromRaw parses null session information when relayed by older node firmware`() {
        // A node on older shared firmware re-encodes the request without the trailing session info.
        val data = GOLDEN_REQUEST.copyOfRange(0, HEADER_SIZE + STRIPPED_PAYLOAD_LENGTH.toInt())

        val request = NodeProbeStatusRequest.fromRaw(
            data,
            requestId = 0xA1B2C3D4u,
            payloadLength = STRIPPED_PAYLOAD_LENGTH,
        )

        assertNotNull(request?.probeStatus?.probeHighLowAlarmStatus)
        assertNull(request?.probeStatus?.sessionInformation)
    }

    companion object {
        private val HEADER_SIZE = NodeRequest.HEADER_SIZE.toInt()
        private const val FULL_PAYLOAD_LENGTH: UByte = 105u
        private const val STRIPPED_PAYLOAD_LENGTH: UByte = 99u

        /**
         * The Probe Status (0x45) node request from combustion-fw-shared's
         * tests/test_ProbeStatusSessionInfo.cpp (GOLDEN_REQUEST): a 10-byte header then a 105-byte
         * payload, hop count ONE, session ID 0xDEADBEEF and sample period 5000 ms at the tail.
         */
        private val GOLDEN_REQUEST = ubyteArrayOf(
            0xCAu, 0xFEu, 0xC3u, 0x82u, 0x45u, 0xD4u, 0xC3u, 0xB2u,
            0xA1u, 0x69u, 0x78u, 0x56u, 0x34u, 0x12u, 0x04u, 0x03u,
            0x02u, 0x01u, 0x08u, 0x07u, 0x06u, 0x05u, 0x23u, 0xC1u,
            0x8Au, 0x24u, 0x1Eu, 0x5Eu, 0xF5u, 0xDEu, 0x6Au, 0x82u,
            0x91u, 0xB8u, 0x1Au, 0x89u, 0xA5u, 0x53u, 0x71u, 0x4Au,
            0x03u, 0xE1u, 0xA0u, 0x55u, 0x08u, 0x20u, 0xB0u, 0xC4u,
            0x12u, 0xE0u, 0x15u, 0x0Au, 0x60u, 0x04u, 0x09u, 0xA2u,
            0x91u, 0x78u, 0x6Fu, 0x5Eu, 0x4Du, 0x04u, 0x00u, 0xA5u,
            0x05u, 0x05u, 0x08u, 0x0Bu, 0x08u, 0x15u, 0x08u, 0x1Bu,
            0x08u, 0x25u, 0x08u, 0x2Bu, 0x08u, 0x35u, 0x08u, 0x3Bu,
            0x08u, 0x45u, 0x08u, 0x4Bu, 0x08u, 0x55u, 0x08u, 0x03u,
            0x10u, 0x0Eu, 0x10u, 0x13u, 0x10u, 0x1Eu, 0x10u, 0x23u,
            0x10u, 0x2Eu, 0x10u, 0x33u, 0x10u, 0x3Eu, 0x10u, 0x43u,
            0x10u, 0x4Eu, 0x10u, 0x53u, 0x10u, 0xEFu, 0xBEu, 0xADu,
            0xDEu, 0x88u, 0x13u,
        )
    }
}
