/*
 * Project: Combustion Inc. Android Framework
 * File: NodeGaugeStatusRequest.kt
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

import inc.combustion.framework.ble.GaugeStatus
import inc.combustion.framework.utf8StringFromRange

internal class NodeGaugeStatusRequest(
    requestId: UInt,
    payloadLength: UByte,
    serialNumber: String,
    val gaugeStatus: GaugeStatus,
) : NodeRequest(requestId, payloadLength, NodeMessageType.GAUGE_STATUS, serialNumber) {
    companion object {
        private const val SERIAL_NUMBER_LENGTH = 10
        private val MINIMUM_PAYLOAD_LENGTH = SERIAL_NUMBER_LENGTH + GaugeStatus.RAW_SIZE

        private val PAYLOAD_START_INDEX = HEADER_SIZE.toInt()
        private val SERIAL_NUMBER_INDEX = PAYLOAD_START_INDEX
        private val SERIAL_RANGE =
            SERIAL_NUMBER_INDEX until (SERIAL_NUMBER_INDEX + SERIAL_NUMBER_LENGTH)
        private val GAUGE_STATUS_INDEX = SERIAL_NUMBER_INDEX + SERIAL_NUMBER_LENGTH

        /**
         * Factory function that creates an instance of this object from raw message data.
         */
        fun fromRaw(
            data: UByteArray,
            requestId: UInt,
            payloadLength: UByte
        ): NodeGaugeStatusRequest? {
            if (payloadLength < MINIMUM_PAYLOAD_LENGTH.toUInt()) {
                return null
            }
            val serialNumber = data.utf8StringFromRange(SERIAL_RANGE)

            val gaugeStatusRange = GAUGE_STATUS_INDEX until data.size

            val gaugeStatus: GaugeStatus =
                GaugeStatus.fromRawData(data.slice(gaugeStatusRange).toUByteArray()) ?: return null

            return NodeGaugeStatusRequest(
                requestId,
                payloadLength,
                serialNumber.uppercase(),
                gaugeStatus,
            )
        }
    }
}