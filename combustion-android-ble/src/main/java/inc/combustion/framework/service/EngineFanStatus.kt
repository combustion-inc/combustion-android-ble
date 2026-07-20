/*
 * Project: Combustion Inc. Android Framework
 * File: EngineFanStatus.kt
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

import inc.combustion.framework.ble.getLittleEndianUInt32At

/**
 * Fan status expressed in a packed 12-byte field.
 *
 * Bits  1– 8 : Fan state
 * Bits  9–16 : Duty cycle (0–100 %)
 * Bits 17–24 : Commanded speed (0–100 %)
 * Bits 25–32 : Measured speed (0–100 %)
 * Bits 33–64 : Fan off time in each time window (milliseconds)
 * Bits 65–96 : Fan on time in each time window (milliseconds)
 */
data class EngineFanStatus(
    val fanState: EngineFanState,
    val dutyCycle: UByte,
    val commandedSpeed: UByte,
    val measuredSpeed: UByte,
    val fanOffTimeMs: UInt,
    val fanOnTimeMs: UInt,
) {
    companion object {
        private const val RAW_SIZE = 12

        private const val IDX_FAN_STATE = 0
        private const val IDX_DUTY_CYCLE = 1
        private const val IDX_COMMANDED_SPEED = 2
        private const val IDX_MEASURED_SPEED = 3
        private const val IDX_FAN_OFF_TIME_MS = 4
        private const val IDX_FAN_ON_TIME_MS = 8

        fun fromRawData(data: UByteArray): EngineFanStatus? {
            if (data.size < RAW_SIZE) return null

            val engineFanState = EngineFanState.fromUByte(data[IDX_FAN_STATE])
            val dutyCycle = data[IDX_DUTY_CYCLE]
            val commandedSpeed = data[IDX_COMMANDED_SPEED]
            val measuredSpeed = data[IDX_MEASURED_SPEED]
            val fanOffTimeMs = data.getLittleEndianUInt32At(IDX_FAN_OFF_TIME_MS)
            val fanOnTimeMs = data.getLittleEndianUInt32At(IDX_FAN_ON_TIME_MS)

            return EngineFanStatus(
                fanState = engineFanState,
                dutyCycle = dutyCycle,
                commandedSpeed = commandedSpeed,
                measuredSpeed = measuredSpeed,
                fanOffTimeMs = fanOffTimeMs,
                fanOnTimeMs = fanOnTimeMs,
            )
        }
    }
}
