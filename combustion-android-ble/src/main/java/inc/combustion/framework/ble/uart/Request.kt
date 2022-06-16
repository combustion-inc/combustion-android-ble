/*
 * Project: Combustion Inc. Android Framework
 * File: Request.kt
 * Author: https://github.com/miwright2
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
package inc.combustion.framework.ble.uart

import inc.combustion.framework.ble.getCRC16CCITT
import inc.combustion.framework.ble.putLittleEndianUShortAt

/**
 * Baseclass for UART request messages
 *
 * @constructor
 * Create request message of the specified type and length.
 *
 * @param payloadLength length of payload
 * @param type type of request message
 */
internal open class Request(
    payloadLength: UByte,
    type: MessageType
) {
    companion object {
        const val HEADER_SIZE: UByte = 6u
    }

    val data = UByteArray((HEADER_SIZE + payloadLength).toInt()) { 0u }
    val sData get() = data.toByteArray()

    init {
        // Sync Bytes { 0xCA, 0xFE }
        data[0] = 0xCAu
        data[1] = 0xFEu

        // CRC needs to be set after the payload's been added

        // Message type
        data[4] = type.value

        // Payload length
        data[5] = payloadLength
    }

    /**
     * Calculates the CRC16 over the message type, payload length, and payload and inserts the
     * result in the correct location in the packet.
     */
    fun setCRC() {
        // TODO: Uncomment this when it's time to add the CRC to outgoing messages
        // data.putLittleEndianUShortAt(2, data.drop(4).toUByteArray().getCRC16CCITT())
    }
}