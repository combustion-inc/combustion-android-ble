/*
 * Project: Combustion Inc. Android Framework
 * File: NodeReadGaugeLogsRequest.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2025. Combustion Inc.
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
import inc.combustion.framework.copyInUtf8SerialNumber

internal class NodeReadGaugeLogsRequest(
    serialNumber: String,
    minSequence: UInt,
    maxSequence: UInt,
    requestId: UInt? = null,
) : NodeRequest(
    populatePayload(
        serialNumber,
        minSequence,
        maxSequence,
    ),
    NodeMessageType.GAUGE_LOG,
    requestId,
    serialNumber,
) {
    companion object {
        /// payload length 18 = serial number (10 bytes) + min sequence (4 bytes) + max sequence (4 bytes)
        private const val PAYLOAD_LENGTH: UByte = 18u

        private const val MAX_SERIAL_LENGTH = 10
        private const val SEQUENCE_NUMBER_LENGTH = 4

        /**
         * Helper function that builds up payload of request.
         */
        fun populatePayload(
            serialNumber: String,
            minSequence: UInt,
            maxSequence: UInt
        ): UByteArray {
            val payload = UByteArray(PAYLOAD_LENGTH.toInt()) { 0u }

            payload.copyInUtf8SerialNumber(serialNumber, 0)
            payload.putLittleEndianUInt32At(MAX_SERIAL_LENGTH, minSequence)
            payload.putLittleEndianUInt32At(MAX_SERIAL_LENGTH + SEQUENCE_NUMBER_LENGTH, maxSequence)
            return payload
        }
    }
}