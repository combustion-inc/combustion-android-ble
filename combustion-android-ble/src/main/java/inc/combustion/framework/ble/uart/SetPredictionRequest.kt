/*
 * Project: Combustion Inc. Android Example
 * File: SetPredictionRequest.kt
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

import inc.combustion.framework.service.ProbePredictionMode

internal class SetPredictionRequest(
   setPointTemperatureC: Double,
   mode: ProbePredictionMode
) : Request(PAYLOAD_LENGTH, MessageType.SET_PREDICTION) {

    companion object {
        const val PAYLOAD_LENGTH: UByte = 2u
    }

    init {
        val converted = (setPointTemperatureC * 10.0).toUInt()
        val clamped = if(converted > 0x3FFu) 0x3FFu else converted

        val predictionData = (clamped and 0x3FFu) or (mode.uByte.toUInt() shl 10)
        data[(HEADER_SIZE).toInt() + 0] = ((predictionData and 0x00FFu) shr 0x0).toUByte()
        data[(HEADER_SIZE).toInt() + 1] = ((predictionData and 0xFF00u) shr 0x8).toUByte()
        setCRC()
    }
}