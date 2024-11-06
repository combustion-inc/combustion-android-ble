/*
 * Project: Combustion Inc. Android Framework
 * File: NodeRequest.kt
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

import android.util.Log
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.*
import inc.combustion.framework.ble.getCRC16CCITT
import inc.combustion.framework.ble.getLittleEndianUInt32At
import inc.combustion.framework.ble.putLittleEndianUInt32At
import inc.combustion.framework.ble.putLittleEndianUShortAt
import inc.combustion.framework.ble.uart.MessageType

/**
 * Baseclass for UART request messages
 */
internal open class NodeRequest(
    messageId: NodeMessage,
    payloadLength: UByte
) : NodeUARTMessage(
    messageId,
    payloadLength
) {
    override fun toString(): String {
        return "REQ  $messageId (REQID: ${String.format("%08x", requestId.toInt())})"
    }

    companion object {
        const val HEADER_SIZE: UByte = 10u

        /**
         * Factory method for parsing a request from data.
         *
         * @param data Raw data to parse
         * @return Instant of request if found or null if one could not be parsed from the data
         */
        fun requestFromData(data : UByteArray) : NodeRequest? {
            // Sync bytes
            val syncBytes = data.slice(0..1)
            val expectedSync = listOf<UByte>(202u, 254u) // 0xCA, 0xFE
            if(syncBytes != expectedSync) {
                return null
            }

            // Message type
            val typeByte = data[4]

            val messageType = NodeMessageType.fromUByte(typeByte) ?: return null

            // Request ID
            val requestId = data.getLittleEndianUInt32At(5)

            // Payload Length
            val payloadLength = data[9]

            // CRC
            val crc = data.getLittleEndianUShortAt(2)

            val crcDataLength = 6 + payloadLength.toInt()

            var crcData = data.drop(4).toUByteArray()
            crcData = crcData.dropLast(crcData.size - crcDataLength).toUByteArray()

            val calculatedCRC = crcData.getCRC16CCITT()

            if(crc != calculatedCRC) {
                Log.w(LOG_TAG, "Invalid CRC")
                return null
            }

            val requestLength = payloadLength.toInt() + HEADER_SIZE.toInt()

            // Invalid number of bytes
            if(data.size < requestLength) {
                Log.w(LOG_TAG, "Invalid data length")
                return null
            }

            return when(messageType) {
                NodeMessageType.PROBE_STATUS -> {
                    NodeProbeStatusRequest.fromRaw(
                        data,
                        requestId,
                        payloadLength
                    )
                }
                NodeMessageType.HEARTBEAT -> {
                    NodeHeartbeatRequest.fromRaw(
                        data,
                        requestId,
                        payloadLength
                    )
                }
                else -> null
            }
        }

    }

    // Contents of message
    var data = UByteArray(0)

    val sData get() = data.toByteArray()

    // Random ID for this request, for tracking request-response pairs
    var requestId: UInt = 0u

    /**
     * Constructor for generating a new outgoing Request object.
     *
     * @param outgoingPayload data containing outgoing payload
     * @param type type of request message
     * @param requestId optional request id
     */
    constructor(
        outgoingPayload: UByteArray,
        messageType: NodeMessage,
        requestId: UInt? = null
    ) : this(
        messageType,
        outgoingPayload.size.toUByte()
    ) {
        // Sync Bytes { 0xCA, 0xFE }
        data += 0xCAu
        data += 0xFEu

        // Calculate CRC over Message Type, request ID, payload length, payload
        var crcData = UByteArray(0)

        // Message type
        crcData += messageType.value

        // Request ID
        this.requestId = requestId ?: (0u..UInt.MAX_VALUE).random()

        crcData += UByteArray(4)
        crcData.putLittleEndianUInt32At(1, this.requestId)

        // Payload length
        crcData += outgoingPayload.size.toUByte()

        // Payload
        crcData += outgoingPayload

        // Calculate CRC and add to main data array
        this.data += UByteArray(2)
        this.data.putLittleEndianUShortAt(2, crcData.getCRC16CCITT())

        // Message Type, payload length, payload
        this.data += crcData
    }

    /**
     * Constructor for an incoming Request object (from MeatNet).
     *
     * @param requestId Request ID of this message from the Network
     * @param payloadLength Length of this message's payload
     */
    constructor(
        requestId: UInt,
        payloadLength: UByte,
        messageId: NodeMessage
    ) : this(
        messageId,
        payloadLength
    ) {
        this.requestId = requestId
    }

    /**
     * Calculates the CRC16 over the message type, payload length, and payload and inserts the
     * result in the correct location in the packet.
     */
    fun setCRC() {
        data.putLittleEndianUShortAt(2, data.drop(4).toUByteArray().getCRC16CCITT())
    }
}