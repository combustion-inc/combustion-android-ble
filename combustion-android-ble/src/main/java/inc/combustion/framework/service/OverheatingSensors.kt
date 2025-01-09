/*
 * Project: Combustion Inc. Android Framework
 * File: OverheatingSensors.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2024. Combustion Inc.
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

data class OverheatingSensors(val values: List<Int>) {

    public fun isAnySensorOverheating(): Boolean {
        return values.isNotEmpty()
    }

    companion object {
        internal const val SIZE_BYTES = 1

        /// Overheating thresholds (in degrees C) for T1 and T2
        private const val OVERHEATING_T1_T2_THRESHOLD = 105.0

        /// Overheating thresholds (in degrees C) for T3
        private const val OVERHEATING_T3_THRESHOLD = 115.0

        /// Overheating thresholds (in degrees C) for T4
        private const val OVERHEATING_T4_THRESHOLD = 125.0

        /// Overheating thresholds (in degrees C) for T5-T8
        private const val OVERHEATING_T5_T8_THRESHOLD = 315.56


        fun fromRawByte(byte: UByte): OverheatingSensors {
            val overheatingSensors = mutableListOf<Int>()
            for (i in 0 until 8) {
                if (byte.toInt() and (1 shl i) != 0) {
                    overheatingSensors.add(i)
                }
            }
            return OverheatingSensors(overheatingSensors)
        }

        fun fromTemperatures(temperatures: ProbeTemperatures): OverheatingSensors {
            val overheatingSensors = mutableListOf<Int>()

            for (i in 0..1) {
                if (temperatures.values[i] >= OVERHEATING_T1_T2_THRESHOLD) {
                    overheatingSensors.add(i)
                }
            }

            if (temperatures.values[2] >= OVERHEATING_T3_THRESHOLD) {
                overheatingSensors.add(2)
            }

            if (temperatures.values[3] >= OVERHEATING_T4_THRESHOLD) {
                overheatingSensors.add(3)
            }

            for (i in 4..7) {
                if (temperatures.values[i] >= OVERHEATING_T5_T8_THRESHOLD) {
                    overheatingSensors.add(i)
                }
            }

            return OverheatingSensors(overheatingSensors.toList())
        }
    }
}