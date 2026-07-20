/*
 * Project: Combustion Inc. Android Framework
 * File: EngineControllerStatus.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2026. Combustion Inc.
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

import inc.combustion.framework.ble.getLittleEndianUInt16At

/**
 * Controller status expressed in a packed 8-byte field.
 *
 * Bits  1– 8 : Controller state
 * Bits  9–16 : Response coefficient (raw × 500, i.e. raw / 500.0)
 * Bits 17–24 : Cycles completed
 * Bits 25–32 : Flags
 * Bits 33–48 : Smoothed temperature (°C × 10, i.e. raw / 10.0)
 * Bits 49–56 : Time to peak, seconds
 * Bits 57–64 : Drift rate, signed (°C/s × 1000, i.e. raw / 1000.0)
 */
data class EngineControllerStatus(
    val state: EngineControllerState,
    val responseCoefficient: Float,
    val cyclesCompleted: UByte,
    val flags: EngineControllerFlags,
    val smoothedTemperatureCelsius: Float,
    val timeToPeakSeconds: UByte,
    val driftRateCelsiusPerSecond: Float,
) {
    companion object {
        const val RAW_SIZE = 8

        private const val IDX_STATE = 0
        private const val IDX_RESPONSE_COEFFICIENT = 1
        private const val IDX_CYCLES_COMPLETED = 2
        private const val IDX_FLAGS = 3
        private const val IDX_SMOOTHED_TEMPERATURE = 4
        private const val IDX_TIME_TO_PEAK = 6
        private const val IDX_DRIFT_RATE = 7

        fun fromRawData(data: UByteArray): EngineControllerStatus? {
            if (data.size < RAW_SIZE) return null

            val state = EngineControllerState.fromUByte(data[IDX_STATE])
            val responseCoefficient = data[IDX_RESPONSE_COEFFICIENT].toFloat() / 500.0f
            val cyclesCompleted = data[IDX_CYCLES_COMPLETED]
            val flags = EngineControllerFlags.fromRawByte(data[IDX_FLAGS])
            val smoothedTemperature =
                data.getLittleEndianUInt16At(IDX_SMOOTHED_TEMPERATURE).toFloat() / 10.0f
            val timeToPeakSeconds = data[IDX_TIME_TO_PEAK]
            val driftRate = data[IDX_DRIFT_RATE].toByte().toFloat() / 1000.0f

            return EngineControllerStatus(
                state = state,
                responseCoefficient = responseCoefficient,
                cyclesCompleted = cyclesCompleted,
                flags = flags,
                smoothedTemperatureCelsius = smoothedTemperature,
                timeToPeakSeconds = timeToPeakSeconds,
                driftRateCelsiusPerSecond = driftRate,
            )
        }
    }
}
