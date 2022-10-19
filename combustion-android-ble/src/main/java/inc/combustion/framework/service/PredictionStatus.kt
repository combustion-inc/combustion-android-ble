/*
 * Project: Combustion Inc. Android Example
 * File: PredictionStatus.kt
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

package inc.combustion.framework.service

import inc.combustion.framework.ble.shl
import inc.combustion.framework.ble.shr
import kotlin.random.Random
import kotlin.random.nextUInt

data class PredictionStatus(
    val predictionState: ProbePredictionState,
    val predictionMode: ProbePredictionMode,
    val predictionType: ProbePredictionType,
    val setPointTemperature: Double,
    val heatStartTemperature: Double,
    val predictionValueSeconds: UInt,
    val estimatedCoreTemperature: Double
) {
    companion object {
        const val SIZE_BYTES = 7

        fun fromRawData(data: UByteArray): PredictionStatus? {
            if (data.size < SIZE_BYTES) return null

            // enums
            val stateModeType   = data[0]
            val predictionState = ProbePredictionState.fromPredictionStatus(stateModeType)
            val predictionMode  = ProbePredictionMode.fromPredictionStatus(stateModeType)
            val predictionType  = ProbePredictionType.fromPredictionStatus(stateModeType)

            // raw bit fields
            val rawSetPoint   =   data[1].toUShort()                   or ((data[2].toUShort() and 0x03u) shl 8)
            val rawHeatStart  = ((data[2].toUShort() and 0xFCu) shr 2) or ((data[3].toUShort() and 0x0Fu) shl 6)
            val rawPrediction = ((data[3].toUInt()   and 0xF0u) shr 4) or (data[4].toUInt() shl 4)               or ((data[5].toUInt() and 0x1Fu) shl 12)
            val rawCoreTemp   = ((data[5].toUShort() and 0xE0u) shr 5) or (data[6].toUShort() shl 3)

            // converted raw values
            val setPoint = rawSetPoint.toDouble() * 0.1
            val heatStart = rawHeatStart.toDouble() * 0.1
            val coreTemp = rawCoreTemp.toDouble() * 0.1 - 20.0

            return PredictionStatus(
                predictionState,
                predictionMode,
                predictionType,
                setPoint,
                heatStart,
                rawPrediction,
                coreTemp
            )
        }

        internal fun withRandomData() : PredictionStatus {
            val prediction = Random.nextUInt(65u, 45u)
            val estimatedCore = Random.nextDouble(60.0, 63.0)
            return PredictionStatus(
                ProbePredictionState.PREDICTING,
                ProbePredictionMode.TIME_TO_REMOVAL,
                ProbePredictionType.REMOVAL,
                65.0,
                10.0,
                prediction,
                estimatedCore
            )
        }
    }
}
