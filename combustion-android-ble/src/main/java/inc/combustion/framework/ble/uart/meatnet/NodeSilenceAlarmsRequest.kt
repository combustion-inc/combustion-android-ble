/*
 * Project: Combustion Inc. Android Framework
 * File: NodeSilenceAllAlarmsRequest.kt
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
import inc.combustion.framework.service.CombustionProductType

internal class NodeSilenceAlarmsRequest(
    serialNumber: String?,
    global: Boolean,
    productType: CombustionProductType = CombustionProductType.UNKNOWN,
    requestId: UInt? = null,
) : NodeRequest(
    outgoingPayload = populatePayload(
        serialNumber = serialNumber,
        global = global,
        productType = productType,
    ),
    messageType = NodeMessageType.SILENCE_ALARMS,
    requestId = requestId,
    serialNumber = serialNumber,
) {
    override fun toString(): String {
        return "${super.toString()} $serialNumber $requestId"
    }

    companion object {
        private const val PAYLOAD_LENGTH: UByte = 16u
        private const val IDX_GLOBAL = 0
        private const val IDX_PRODUCT_TYPE = 1
        private const val IDX_SERIAL_NUMBER = 2

        private const val PADDING_BYTE: UByte = 0u

        /**
         * Helper function that builds up payload of request.
         */
        fun populatePayload(
            serialNumber: String?,
            global: Boolean,
            productType: CombustionProductType,
        ): UByteArray {
            val payload = UByteArray(PAYLOAD_LENGTH.toInt()) { PADDING_BYTE }
            if (global) {
                payload[IDX_GLOBAL] = 1u
            }
            payload[IDX_PRODUCT_TYPE] = productType.type

            if (!global) {
                val productSerialNumber =
                    requireNotNull(serialNumber) { "Silencing non-global alarms require non-null serialNumber" }
                if (productType == CombustionProductType.PROBE) {
                    payload.putLittleEndianUInt32At(
                        IDX_SERIAL_NUMBER,
                        productSerialNumber.toLong(radix = 16).toUInt(),
                    )
                } else {
                    payload.copyInUtf8SerialNumber(productSerialNumber, IDX_SERIAL_NUMBER)
                }
            }

            return payload
        }
    }
}