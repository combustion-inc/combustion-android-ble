/*
 * Project: Combustion Inc. Android Framework
 * File: NodeSetHighLowAlarmRequest.kt
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

import inc.combustion.framework.service.HighLowAlarmStatus

internal class NodeSetHighLowAlarmRequest(
    serialNumber: String,
    private val highLowAlarmStatus: HighLowAlarmStatus,
    requestId: UInt? = null
) : NodeRequest(
    populatePayload(serialNumber, highLowAlarmStatus),
    NodeMessageType.SET_HIGH_LOW_ALARM,
    requestId,
    serialNumber,
) {
    override fun toString(): String {
        return "${super.toString()} $serialNumber $highLowAlarmStatus"
    }

    companion object {
        private const val PAYLOAD_LENGTH: UByte = 14u
        private const val MAX_SERIAL_LENGTH = 10

        /**
         * Helper function that builds up payload of request.
         */
        fun populatePayload(
            serialNumber: String,
            highLowAlarmStatus: HighLowAlarmStatus,
        ): UByteArray {

            val payload = UByteArray(PAYLOAD_LENGTH.toInt())

            // Add serial number to payload
            val serialBytes = serialNumber.encodeToByteArray().toUByteArray() // UTF-8 by default
            serialBytes.copyInto(
                destination = payload,
                startIndex = 0,
                endIndex = minOf(serialBytes.size, MAX_SERIAL_LENGTH),
                destinationOffset = 0
            )

            // Encode alarm status in payload
            val rawHighLowAlarmStatus = highLowAlarmStatus.toRawData()
            rawHighLowAlarmStatus.copyInto(
                destination = payload,
                destinationOffset = payload.size - rawHighLowAlarmStatus.size
            )

            return payload
        }
    }
}