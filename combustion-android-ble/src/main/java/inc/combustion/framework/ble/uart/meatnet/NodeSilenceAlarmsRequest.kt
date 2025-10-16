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

import inc.combustion.framework.ble.getLittleEndianUInt32At
import inc.combustion.framework.ble.putLittleEndianUInt32At
import inc.combustion.framework.copyInUtf8SerialNumber
import inc.combustion.framework.getUtf8SerialNumber
import inc.combustion.framework.service.CombustionProductType

internal class NodeSilenceAlarmsRequest(
    serialNumber: String?,
    val global: Boolean,
    val productType: CombustionProductType = CombustionProductType.UNKNOWN,
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
        return "${super.toString()} $serialNumber $global $productType"
    }

    companion object {
        private const val PAYLOAD_LENGTH: UByte = 16u
        private val IDX_RAW_PAYLOAD_START = HEADER_SIZE.toInt()
        private const val IDX_PAYLOAD_GLOBAL = 0
        private const val IDX_PAYLOAD_PRODUCT_TYPE = 1
        private const val IDX_PAYLOAD_PROBE_SERIAL_NUMBER = 2
        private const val IDX_PAYLOAD_NON_PROBE_SERIAL_NUMBER = 6

        private const val BYTE_PADDING: UByte = 0u
        private const val BYTE_TRUE: UByte = 1u

        /**
         * Helper function that builds up payload of request.
         */
        fun populatePayload(
            serialNumber: String?,
            global: Boolean,
            productType: CombustionProductType,
        ): UByteArray {
            val payload = UByteArray(PAYLOAD_LENGTH.toInt()) { BYTE_PADDING }
            payload[IDX_PAYLOAD_PRODUCT_TYPE] = productType.type

            if (global) {
                payload[IDX_PAYLOAD_GLOBAL] = BYTE_TRUE
            } else {
                val productSerialNumber =
                    requireNotNull(serialNumber) { "Silencing non-global alarms require non-null serialNumber" }
                when (productType) {
                    CombustionProductType.PROBE -> payload.putLittleEndianUInt32At(
                        IDX_PAYLOAD_PROBE_SERIAL_NUMBER,
                        productSerialNumber.toLong(radix = 16).toUInt(),
                    )

                    else -> payload.copyInUtf8SerialNumber(
                        productSerialNumber,
                        IDX_PAYLOAD_NON_PROBE_SERIAL_NUMBER,
                    )
                }
            }

            return payload
        }

        /**
         * Factory function that creates an instance of this object from raw message data.
         */
        fun fromRaw(
            data: UByteArray,
            requestId: UInt,
            payloadLength: UByte,
        ): NodeSilenceAlarmsRequest? {
            if (payloadLength < PAYLOAD_LENGTH.toUInt()) {
                return null
            }
            val productType =
                CombustionProductType.fromUByte(data[IDX_RAW_PAYLOAD_START + IDX_PAYLOAD_PRODUCT_TYPE])
            return if (data[IDX_RAW_PAYLOAD_START + IDX_PAYLOAD_GLOBAL] == BYTE_TRUE) {
                NodeSilenceAlarmsRequest(
                    serialNumber = null,
                    global = true,
                    productType = productType,
                    requestId = requestId,
                )
            } else {
                val serialNumber = when (productType) {
                    CombustionProductType.PROBE -> {
                        val serialUInt = data.getLittleEndianUInt32At(
                            IDX_RAW_PAYLOAD_START + IDX_PAYLOAD_PROBE_SERIAL_NUMBER,
                        )
                        Integer.toHexString(serialUInt.toInt()).uppercase()
                    }

                    else -> data.getUtf8SerialNumber(IDX_RAW_PAYLOAD_START + IDX_PAYLOAD_NON_PROBE_SERIAL_NUMBER)
                }
                NodeSilenceAlarmsRequest(
                    serialNumber = serialNumber,
                    global = false,
                    productType = productType,
                    requestId = requestId,
                )
            }
        }
    }
}