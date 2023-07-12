/*
 * Project: Combustion Inc. Android Framework
 * File: NodeUARTMessage.kt
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
import inc.combustion.framework.service.DebugSettings

/**
 * Representation of Combustion BLE Node UART request. This top-level representation
 * is useful for decoding multiple UART messages from a single notification that can
 * be both NodeRequest and NodeResponse types.
 */
internal open class NodeUARTMessage {
    companion object {
        /**
         * Returns an array of NodeUARTMessage objects (both responses and requests)
         * from the given data from a UART notification.
         */
        fun fromData(data : UByteArray) : List<NodeUARTMessage> {
            val messages = mutableListOf<NodeUARTMessage>()

            var numberBytesRead = 0

            while(numberBytesRead < data.size) {
                val bytesToDecode = data.copyOfRange(numberBytesRead, data.size)

                // Try to parse a NodeResponse
                NodeResponse.responseFromData(bytesToDecode)?.let {
                    messages.add(it)
                    numberBytesRead += (it.payloadLength + NodeResponse.HEADER_SIZE).toInt()

                } ?: run {

                    // If NodeResponse parsing failed, try to decode a NodeRequest.
                    NodeRequest.requestFromData(bytesToDecode)?.let {
                        messages.add(it)
                        numberBytesRead += (it.payloadLength + NodeRequest.HEADER_SIZE).toInt()
                    } ?: run {

                        // drop data here, and return out what we have parsed so far
                        // Log.w(LOG_TAG, "MeatNet: Parsed invalid or unknown data! Dropping bytes $numberBytesRead to ${data.size}")
                        return messages
                    }
                }
            }

            return messages
        }
    }
}
