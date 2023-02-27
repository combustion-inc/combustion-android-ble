/*
 * Project: Combustion Inc. Android Framework
 * File: PredictionLog.kt
 * Author: https://github.com/miwright2
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

package inc.combustion.framework.service

import inc.combustion.framework.ble.shl
import inc.combustion.framework.ble.shr

data class PredictionLog(
    val virtualSensors: ProbeVirtualSensors,
    val predictionState: ProbePredictionState,
    val predictionMode: ProbePredictionMode,
    val predictionType: ProbePredictionType,
    val predictionSetPointTemperature: Double,
    val predictionValueSeconds: UInt,
    val estimatedCoreTemperature: Double
) {
    companion object {
        fun fromRawData(data: UByteArray): PredictionLog {
            val firstWord = (data[1].toUShort() shl 8) or data[0].toUShort()
            val virtualSensors = ProbeVirtualSensors.fromLogResponse(firstWord)
            val predictionState = ProbePredictionState.fromLogResponse(firstWord)
            val predictionMode = ProbePredictionMode.fromLogResponse(firstWord)
            val predictionType = ProbePredictionType.fromLogResponse(firstWord)

            // 10 bit field
            val rawSetPoint = ((data[3].toUShort() and 0x01u) shl 9) or (data[2].toUShort() shl 1) or ((data[1].toUShort() and 0x80u) shr 7)
            val predictionSetPointTemperature = rawSetPoint.toDouble() * 0.1

            // 17 bit field
            val predictionValueSeconds = ((data[5].toUInt() and 0x03u) shl 15) or (data[4].toUInt() shl 7) or ((data[3].toUInt() and 0xFEu) shr 1)

            // 11 bit field
            val rawCore = ((data[6].toUShort() and 0x1Fu) shl 6) or ((data[5].toUShort() and 0xFCu) shr 2)
            val estimatedCoreTemperature = (rawCore.toDouble() * 0.1) - 20.0

            return PredictionLog(
                virtualSensors,
                predictionState,
                predictionMode,
                predictionType,
                predictionSetPointTemperature,
                predictionValueSeconds,
                estimatedCoreTemperature
            )
        }
    }
}
