/*
 * Project: Combustion Inc. Android Framework
 * File: NodeResponse.kt
 * Author: https://github.com/jmaha
 *
 * MIT License
 *
 * Copyright (c) 2022. Combustion Inc.
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

import inc.combustion.framework.ble.getCRC16CCITT
import inc.combustion.framework.ble.getLittleEndianUInt32At
import inc.combustion.framework.ble.getLittleEndianUShortAt

/**
 * Baseclass for UART response messages
 */
internal open class NodeResponse(
    val success: Boolean,
    val requestId: UInt,
    val responseId: UInt,
    val payloadLength: UByte
) : NodeUARTMessage() {

    companion object {
        const val HEADER_SIZE : UByte = 15u

        // NodeResponse messages have the leftmost bit in the 'message type' field set to 1.
        private const val RESPONSE_TYPE_FLAG : UByte = 0x80u

        /**
         * Factory method for parsing a request from data.
         *
         * @param data Raw data to parse
         * @return Instant of request if found or null if one could not be parsed from the data
         */
        fun responseFromData(data : UByteArray) : NodeResponse? {

            // Sync bytes
            val syncBytes = data.slice(0..1)
            val expectedSync = listOf<UByte>(202u, 254u) // 0xCA, 0xFE
            if(syncBytes != expectedSync) {
                return null
            }

            // Message type
            val typeRaw = data[4]

            // Verify that this is a Response by checking the response type flag
            if(typeRaw and RESPONSE_TYPE_FLAG != RESPONSE_TYPE_FLAG) {
                // If that 'response type' bit isn't set, this is probably a Request.
                return null
            }

            val messageType = NodeMessageType.fromUByte((typeRaw and RESPONSE_TYPE_FLAG.inv()))
                ?: return null

            // Request ID
            val requestId = data.getLittleEndianUInt32At(5)

            // Response ID
            val responseId = data.getLittleEndianUInt32At(9)

            // Success/Fail
            val success = data[13] > 0u

            // Payload Length
            val payloadLength = data[14]

            // CRC
            val crc = data.getLittleEndianUShortAt(2)

            val crcDataLength = 11 + payloadLength.toInt()

            var crcData = data.drop(4).toUByteArray()
            crcData = crcData.dropLast(crcData.size - crcDataLength).toUByteArray()

            val calculatedCRC = crcData.getCRC16CCITT()

            if(crc != calculatedCRC) {
                return null
            }

            val responseLength = payloadLength.toInt() + HEADER_SIZE.toInt()

            // Invalid number of bytes
            if(data.size < responseLength) {
                return null
            }

            when(messageType) {
//                NodeMessageType.LOG -> {
//                    return NodeLogResponse.fromRaw(
//                        data,
//                        success,
//                        payloadLength
//                    )
//                }

//                NodeMessageType.SET_ID -> {
//                    return NodeSetIDResponse(success, payloadLength.toInt())
//                }

//                NodeMessageType.SET_COLOR -> {
//                    return NodeSetColorResponse(
//                        success,
//                        payloadLength
//                    )
//                }

//                NodeMessageType.SESSION_INFO -> {
//                    return NodeSessionInfoResponse.fromRaw(
//                        data,
//                        success,
//                        payloadLength
//                    )
//                }

                NodeMessageType.SET_PREDICTION -> {
                    return NodeSetPredictionResponse(
                        success,
                        requestId,
                        responseId,
                        payloadLength
                    )
                }

//                NodeMessageType.READ_OVER_TEMPERATURE -> {
//                    return NodeReadOverTemperatureResponse(
//                        data,
//                        success,
//                        payloadLength
//                    )
//                }

                else -> {
                    return null
                }
            }
        }
    }

}