/*
 * Project: Combustion Inc. Android Framework
 * File: NodeHeartbeatResponse.kt
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

import android.util.Log
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.getLittleEndianUInt32At
import inc.combustion.framework.service.CombustionProductType

internal class NodeHeartbeatRequest (
    serialNumber: String,
    val macAddress: String,
    val productType: CombustionProductType,
    val hopCount: UInt,
    val inbound: Boolean,
    val connectionDetails: List<ConnectionDetail>,
    requestId: UInt,
    payloadLength: UByte
) : NodeRequest(requestId, payloadLength, NodeMessageType.HEARTBEAT, serialNumber) {
    class ConnectionDetail(
        val present: Boolean,
        val serialNumber: String = "",
        val productType: CombustionProductType = CombustionProductType.UNKNOWN,
        val rssi: Int = 0,
    ) {
        override fun toString(): String {
            return if (present) "[$serialNumber | $productType | $rssi]" else "[]"
        }

        companion object {
            private const val SERIAL_NUMBER_INDEX = 0
            private const val NODE_SERIAL_NUMBER_SIZE = 10
            private const val PRODUCT_TYPE_INDEX = 10
            private const val ATTRIBUTES_INDEX = 11
            private const val RSSI_INDEX = 12

            const val PAYLOAD_SIZE = 13

            /**
             * Expects that [data] contains at least 13 bytes and starts at the beginning of a
             * connection detail record.
             */
            fun fromRaw(data: UByteArray): ConnectionDetail {
                assert(data.size == 13)

                val attributes = data[ATTRIBUTES_INDEX]

                // If this value isn't set, ignore the rest of it.
                if ((attributes and 0x01u) != 0x01u.toUByte()) {
                    return ConnectionDetail(present = false)
                }

                val pt = CombustionProductType.fromUByte(data[PRODUCT_TYPE_INDEX])

                val sn = when (pt) {
                    CombustionProductType.PROBE ->
                        Integer.toHexString(data.getLittleEndianUInt32At(SERIAL_NUMBER_INDEX).toInt()).uppercase()
                    CombustionProductType.NODE ->
                        data.trimmedStringFromRange(SERIAL_NUMBER_INDEX until SERIAL_NUMBER_INDEX + NODE_SERIAL_NUMBER_SIZE)
                    CombustionProductType.GAUGE -> TODO()
                    CombustionProductType.UNKNOWN -> {
                        Log.w(LOG_TAG, "Unknown product type ($pt) encountered")
                        ""
                    }
                }

                return ConnectionDetail(
                    present = true,
                    serialNumber = sn,
                    productType = pt,
                    rssi = data[RSSI_INDEX].toByte().toInt()
                )
            }
        }
    }

    companion object {
        // payload is 71 bytes = header (10 + 6 + 1 + 1 + 1) + (4 * details (10 + 1 + 1 + 1))
        const val PAYLOAD_LENGTH: UByte = 71u

        private val PAYLOAD_START_INDEX = HEADER_SIZE.toInt()
        private val SERIAL_NUMBER_RANGE = PAYLOAD_START_INDEX until PAYLOAD_START_INDEX + 10
        private val MAC_ADDRESS_RANGE = PAYLOAD_START_INDEX + 10 until PAYLOAD_START_INDEX + 16
        private val PRODUCT_TYPE_INDEX = PAYLOAD_START_INDEX + 16
        private val HOP_COUNT_INDEX = PAYLOAD_START_INDEX + 17
        private val INBOUND_INDEX = PAYLOAD_START_INDEX + 18
        private val CONNECTION_DETAIL_INDEX = PAYLOAD_START_INDEX + 19

        fun fromRaw(
            payload: UByteArray,
            requestId: UInt,
            payloadLength: UByte
        ): NodeHeartbeatRequest? {
            if (payloadLength < PAYLOAD_LENGTH) {
                return null
            }

            val serialNumber = payload.trimmedStringFromRange(SERIAL_NUMBER_RANGE)
            val macAddress = payload.slice(MAC_ADDRESS_RANGE).joinToString(":") {
                // Seems like "%02X".format(it) should work here, but I get an exception.
                it.toString(16).padStart(2, '0').uppercase()
            }
            val productType = CombustionProductType.fromUByte(payload[PRODUCT_TYPE_INDEX])
            val hopCount = payload[HOP_COUNT_INDEX]
            val inbound = (payload[INBOUND_INDEX].toUInt() != 0u)
            val connectionDetails: MutableList<ConnectionDetail> = mutableListOf()

            for (i in 0 until 4) {
                val startIndex = CONNECTION_DETAIL_INDEX + i * ConnectionDetail.PAYLOAD_SIZE

                connectionDetails.add(
                    ConnectionDetail.fromRaw(
                        payload.slice(startIndex until startIndex + ConnectionDetail.PAYLOAD_SIZE).toUByteArray()
                    )
                )
            }

            return NodeHeartbeatRequest(
                serialNumber = serialNumber,
                macAddress = macAddress,
                productType = productType,
                hopCount = hopCount.toUInt(),
                inbound = inbound,
                connectionDetails = connectionDetails,
                requestId = requestId,
                payloadLength = payloadLength,
            )
        }
    }

    override fun toString(): String {
        val inboundString = if (inbound) "ib" else "ob"
        return "${super.toString()} $inboundString $hopCount; ${connectionDetails.joinToString()}"
    }
}

private fun UByteArray.trimmedStringFromRange(range: IntRange): String {
    return String(
        this.slice(range).toUByteArray().toByteArray(), Charsets.UTF_8
    ).trim('\u0000')
}
