/*
 * Project: Combustion Inc. Android Framework
 * File: LogRequest.kt
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

import inc.combustion.framework.ble.putLittleEndianUInt32At

/**
 * Request temperature logs message
 *
 * @constructor
 * Constructs the request message
 *
 * @param minSequence minimum sequence number
 * @param maxSequence maximum sequence number
 */
internal class LogRequest(
    minSequence: UInt,
    maxSequence: UInt
) : Request(PAYLOAD_LENGTH, MessageType.LOG) {

    companion object {
        const val PAYLOAD_LENGTH: UByte = 8u
        const val MAX_LOG_MESSAGES: UInt = 500u
    }

    init {
        data.putLittleEndianUInt32At((HEADER_SIZE + 0u).toInt(), minSequence)
        data.putLittleEndianUInt32At((HEADER_SIZE + 4u).toInt(), maxSequence)

        setCRC()
    }
}