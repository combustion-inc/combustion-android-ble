/*
 * Project: Combustion Inc. Android Framework
 * File: NodeReadModelInfoResponse.kt
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

import inc.combustion.framework.ble.getLittleEndianUInt32At

internal class NodeReadModelInfoResponse (
    serialNumber: UInt,
    modelInfo: String,
    success: Boolean,
    requestId: UInt,
    responseId: UInt,
    payloadLength: UByte
) : NodeResponse(
    success,
    requestId,
    responseId,
    payloadLength
) {

    companion object {

        // payload is 54 bytes = serial number (4 bytes) + model info (50 bytes)
        const val PAYLOAD_LENGTH: UByte = 54u

        fun fromData(
            payload: UByteArray,
            success: Boolean,
            requestId: UInt,
            responseId: UInt,
            payloadLength: UByte
        ): NodeReadModelInfoResponse? {
            if (payloadLength < PAYLOAD_LENGTH) {
                return null
            }

            val serialNumber = payload.getLittleEndianUInt32At(PAYLOAD_LENGTH.toInt())
            val modelInfoRaw = payload.copyOfRange(HEADER_SIZE.toInt() + 4, HEADER_SIZE.toInt() + 54)
            val modelInfo = String(modelInfoRaw.toByteArray(), Charsets.UTF_8).trim('\u0000')

            return NodeReadModelInfoResponse(
                serialNumber,
                modelInfo,
                success,
                requestId,
                responseId,
                payloadLength
            )
        }
    }
}