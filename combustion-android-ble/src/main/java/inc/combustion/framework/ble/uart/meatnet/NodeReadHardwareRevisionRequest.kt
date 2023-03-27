/*
 * Project: Combustion Inc. Android Framework
 * File: NodeReadHardwareRevisionRequest.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2023. Combustion Inc.
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

internal class NodeReadHardwareRevisionRequest (
    serialNumber: String,
) : NodeRequest(
    populatePayload(serialNumber),
    NodeMessageType.PROBE_HARDWARE_REVISION
) {

    companion object {
        const val PAYLOAD_LENGTH: UByte = 4u

        /**
         * Helper function that builds up payload of request.
         */
        fun populatePayload(
            serialNumber: String
        ) : UByteArray {

            val payload = UByteArray(PAYLOAD_LENGTH.toInt()) { 0u }

            // Add serial number to payload
            payload.putLittleEndianUInt32At(0, serialNumber.toLong(radix = 16).toUInt())

            return payload
        }
    }
}