/*
 * Project: Combustion Inc. Android Framework
 * File: NodeReadFirmwareRevisionResponse.kt
 * Author: https://github.com/angrygorilla
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

import inc.combustion.framework.ble.getLittleEndianUInt32At

internal class NodeReadFirmwareRevisionResponse (
    val serialNumber: String,
    val firmwareRevision: String,
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

        // payload is 24 bytes = serial number (4 bytes) + firmware revision (20 bytes)
        const val PAYLOAD_LENGTH: UByte = 24u

        fun fromData(
            payload: UByteArray,
            success: Boolean,
            requestId: UInt,
            responseId: UInt,
            payloadLength: UByte
        ) : NodeReadFirmwareRevisionResponse? {

            if (payloadLength < PAYLOAD_LENGTH) {
                return null
            }

            val serialNumber = payload.getLittleEndianUInt32At(HEADER_SIZE.toInt()).toString(radix = 16).uppercase()
            val firmwareRevisionRaw = payload.copyOfRange(HEADER_SIZE.toInt() + 4, HEADER_SIZE.toInt() + 24)
            val firmwareRevision = String(firmwareRevisionRaw.toByteArray(), Charsets.UTF_8).trim('\u0000')

            return NodeReadFirmwareRevisionResponse(
                serialNumber,
                firmwareRevision,
                success,
                requestId,
                responseId,
                payloadLength
            )
        }
    }
}