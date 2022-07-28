/*
 * Project: Combustion Inc. Android Framework
 * File: LogResponse.kt
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

import inc.combustion.framework.ble.getLittleEndianUInt32At
import inc.combustion.framework.service.ProbeTemperatures

/**
 * Response message to Log Request
 *
 * @constructor
 * Constructs the response from the byte array received over BLE from the UART service.
 *
 * @param data Data received over BLUE
 * @param success Base response status code.
 */
internal class LogResponse(
    val sequenceNumber: UInt,
    val temperatures: ProbeTemperatures,
    success: Boolean,
    payLoadLength: UInt
) : Response(success, payLoadLength) {

    companion object {
        const val PAYLOAD_LENGTH: UInt = 17u

        fun fromData(data: UByteArray, success: Boolean): LogResponse {
            val sequenceNumber: UInt = data.getLittleEndianUInt32At(HEADER_SIZE.toInt())

            val temperatures: ProbeTemperatures = ProbeTemperatures.fromRawData(
                data.sliceArray((HEADER_SIZE + 4u).toInt()..(HEADER_SIZE + PAYLOAD_LENGTH - 1u).toInt())
            )

            return LogResponse(sequenceNumber, temperatures, success, PAYLOAD_LENGTH)
        }
    }
}