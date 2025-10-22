/*
 * Project: Combustion Inc. Android Framework
 * File: NodeSilenceAllAlarmsResponse.kt
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

import inc.combustion.framework.ble.getLittleEndianUInt32At
import inc.combustion.framework.getUtf8SerialNumber
import inc.combustion.framework.service.CombustionProductType

internal class NodeSilenceAlarmsResponse(
    val serialNumber: String?,
    val productType: CombustionProductType?,
    success: Boolean,
    requestId: UInt,
    responseId: UInt,
    payloadLength: UByte,
) : NodeResponse(
    success,
    requestId,
    responseId,
    payloadLength,
    NodeMessageType.SILENCE_ALARMS,
) {
    override fun toString(): String {
        return "${super.toString()} $success $serialNumber $productType"
    }

    companion object {
        private const val PAYLOAD_LENGTH: UByte = 15u
        private val IDX_RAW_PAYLOAD_START = HEADER_SIZE.toInt()
        private val IDX_PAYLOAD_PRODUCT_TYPE = IDX_RAW_PAYLOAD_START
        private val IDX_PAYLOAD_PROBE_SERIAL_NUMBER = IDX_RAW_PAYLOAD_START + 1
        private val IDX_PAYLOAD_NON_PROBE_SERIAL_NUMBER = IDX_RAW_PAYLOAD_START + 5

        fun fromData(
            payload: UByteArray,
            success: Boolean,
            requestId: UInt,
            responseId: UInt,
            payloadLength: UByte,
        ): NodeSilenceAlarmsResponse? {
            if (payloadLength < PAYLOAD_LENGTH) {
                return null
            }

            return if (success) {
                val productType =
                    CombustionProductType.fromUByte(payload[IDX_PAYLOAD_PRODUCT_TYPE])

                val serialNumber = when (productType) {
                    CombustionProductType.PROBE -> {
                        val serialUInt =
                            payload.getLittleEndianUInt32At(IDX_PAYLOAD_PROBE_SERIAL_NUMBER)
                        Integer.toHexString(serialUInt.toInt()).uppercase()
                    }

                    else -> payload.getUtf8SerialNumber(IDX_PAYLOAD_NON_PROBE_SERIAL_NUMBER)
                }
                NodeSilenceAlarmsResponse(
                    serialNumber = serialNumber,
                    productType = productType,
                    success = true,
                    requestId,
                    responseId,
                    payloadLength
                )
            } else {
                NodeSilenceAlarmsResponse(
                    serialNumber = null,
                    productType = null,
                    success = false,
                    requestId,
                    responseId,
                    payloadLength
                )
            }
        }
    }
}