/*
 * Project: Combustion Inc. Android Example
 * File: NodeSetPredictionRequest.kt
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

import inc.combustion.framework.ble.putLittleEndianUInt32At
import inc.combustion.framework.service.ProbePredictionMode

internal class NodeSetPredictionRequest(
    serialNumber: UInt,
    setPointTemperatureC: Double,
    mode: ProbePredictionMode

) : NodeRequest(populatePayload(serialNumber, setPointTemperatureC, mode),
                NodeMessageType.SET_PREDICTION) {

    companion object {
        const val PAYLOAD_LENGTH: UByte = 6u

        /**
         * Helper function that builds up payload of request.
         */
        fun populatePayload(
            serialNumber: UInt,
            setPointTemperatureC: Double,
            mode: ProbePredictionMode
        ) : UByteArray {

            val payload = UByteArray(PAYLOAD_LENGTH.toInt())

            // Add serial number to payload
            payload.putLittleEndianUInt32At(0, serialNumber)

            // Encode prediction info in payload
            val converted = (setPointTemperatureC * 10.0).toUInt()
            val clamped = if(converted > 0x3FFu) 0x3FFu else converted

            val predictionData = (clamped and 0x3FFu) or (mode.uByte.toUInt() shl 10)

            payload[4] = ((predictionData and 0x00FFu) shr 0x0).toUByte()
            payload[5] = ((predictionData and 0xFF00u) shr 0x8).toUByte()

            return payload
        }
    }

}