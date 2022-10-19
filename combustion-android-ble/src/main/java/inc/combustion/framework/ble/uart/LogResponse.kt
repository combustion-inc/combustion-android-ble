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
import inc.combustion.framework.ble.shl
import inc.combustion.framework.service.ProbePredictionState
import inc.combustion.framework.service.ProbeTemperatures
import inc.combustion.framework.service.ProbeVirtualSensors

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
    val virtualSensors: ProbeVirtualSensors?,
    val predictionState: ProbePredictionState,
    success: Boolean,
    payLoadLength: UInt
) : Response(success, payLoadLength) {

    companion object {
        const val MIN_PAYLOAD_LENGTH: UInt = 17u

        fun fromData(data: UByteArray, success: Boolean, payloadLength: UInt): LogResponse? {
            if(payloadLength < MIN_PAYLOAD_LENGTH) {
                return null
            }

            val sequenceNumber: UInt = data.getLittleEndianUInt32At(HEADER_SIZE.toInt())

            val temperatures: ProbeTemperatures = ProbeTemperatures.fromRawData(
                data.sliceArray((HEADER_SIZE + 4u).toInt()..(HEADER_SIZE + MIN_PAYLOAD_LENGTH - 1u).toInt())
            )

            var sensors: ProbeVirtualSensors? = null
            var state: ProbePredictionState = ProbePredictionState.UNKNOWN
            if(payloadLength >= 19u) {
                val sensorsAndState = data[(HEADER_SIZE + 17u).toInt()].toUShort() or (data[(HEADER_SIZE + 18u).toInt()].toUShort() shl 8)
                sensors = ProbeVirtualSensors.fromLogResponse(sensorsAndState)
                state = ProbePredictionState.fromLogResponse(sensorsAndState)
            }

            return LogResponse(sequenceNumber, temperatures, sensors, state, success, payloadLength)
        }
    }
}