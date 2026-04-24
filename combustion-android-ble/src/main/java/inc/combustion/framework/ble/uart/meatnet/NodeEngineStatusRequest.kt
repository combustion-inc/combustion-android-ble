/*
 * Project: Combustion Inc. Android Framework
 * File: NodeEngineStatusRequest.kt
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
import inc.combustion.framework.utf8StringFromRange

internal class NodeEngineStatusRequest(
    requestId: UInt,
    payloadLength: UByte,
    serialNumber: String,
    val engineStatus: EngineStatus,
) : NodeRequest(requestId, payloadLength, NodeMessageType.ENGINE_STATUS, serialNumber) {
    companion object {
        private const val SERIAL_NUMBER_LENGTH = 10
        private val MINIMUM_PAYLOAD_LENGTH = SERIAL_NUMBER_LENGTH + EngineStatus.RAW_SIZE

        private val PAYLOAD_START_INDEX = HEADER_SIZE.toInt()
        private val SERIAL_NUMBER_INDEX = PAYLOAD_START_INDEX
        private val SERIAL_RANGE =
            SERIAL_NUMBER_INDEX until (SERIAL_NUMBER_INDEX + SERIAL_NUMBER_LENGTH)
        private val ENGINE_STATUS_INDEX = SERIAL_NUMBER_INDEX + SERIAL_NUMBER_LENGTH

        /**
         * Factory function that creates an instance of this object from raw message data.
         */
        fun fromRaw(
            data: UByteArray,
            requestId: UInt,
            payloadLength: UByte
        ): NodeEngineStatusRequest? {
            if (payloadLength < MINIMUM_PAYLOAD_LENGTH.toUInt()) {
                return null
            }
            val serialNumber = data.utf8StringFromRange(SERIAL_RANGE)

            val engineStatusRange = ENGINE_STATUS_INDEX until data.size

            val engineStatus: EngineStatus =
                EngineStatus.fromRawData(data.slice(engineStatusRange).toUByteArray())
                    ?: return null

            return NodeEngineStatusRequest(
                requestId,
                payloadLength,
                serialNumber.uppercase(),
                engineStatus,
            )
        }
    }
}