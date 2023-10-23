/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeTemperatures.kt
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

/**
 * Data class for probe temperature reading.
 *
 * @property values Probe temperatures in Celsius.
 */
data class ProbeTemperatures(
    val values: List<Double>
) {
    internal val overheatingSensors: List<Int>
        get() {
            val overheatingSensors = mutableListOf<Int>()

            for (i in 0..1) {
                if (this.values[i] >= OVERHEATING_T1_T2_THRESHOLD) {
                    overheatingSensors.add(i)
                }
            }

            if (this.values[2] >= OVERHEATING_T3_THRESHOLD) {
                overheatingSensors.add(2)
            }

            if (this.values[3] >= OVERHEATING_T4_THRESHOLD) {
                overheatingSensors.add(3)
            }

            for (i in 4..7) {
                if (this.values[i] >= OVERHEATING_T5_T8_THRESHOLD) {
                    overheatingSensors.add(i)
                }
            }

            return overheatingSensors.toList()
        }

    companion object {
        /// Overheating thresholds (in degrees C) for T1 and T2
        private const val OVERHEATING_T1_T2_THRESHOLD = 105.0
        /// Overheating thresholds (in degrees C) for T3
        private const val OVERHEATING_T3_THRESHOLD = 115.0
        /// Overheating thresholds (in degrees C) for T4
        private const val OVERHEATING_T4_THRESHOLD = 125.0
        /// Overheating thresholds (in degrees C) for T5-T8
        private const val OVERHEATING_T5_T8_THRESHOLD = 300.0

        // deserializes temperature data from reversed set of bytes
        private fun fromReversed(bytes: UByteArray): ProbeTemperatures {
            var rawCounts = mutableListOf<UShort>();

            rawCounts.add(0, ((bytes[0]  and 0xFF.toUByte()).toUShort() shl 5) or ((bytes[1]   and 0xF8.toUByte()).toUShort() shr 3))
            rawCounts.add(0, ((bytes[1]  and 0x07.toUByte()).toUShort() shl 10) or ((bytes[2]  and 0xFF.toUByte()).toUShort() shl 2) or ((bytes[3]  and 0xC0.toUByte()).toUShort() shr 6))
            rawCounts.add(0, ((bytes[3]  and 0x3F.toUByte()).toUShort() shl  7) or ((bytes[4]  and 0xFE.toUByte()).toUShort() shr 1))
            rawCounts.add(0, ((bytes[4]  and 0x01.toUByte()).toUShort() shl 12) or ((bytes[5]  and 0xFF.toUByte()).toUShort() shl 4) or ((bytes[6]  and 0xF0.toUByte()).toUShort() shr 4))
            rawCounts.add(0, ((bytes[6]  and 0x0F.toUByte()).toUShort() shl  9) or ((bytes[7]  and 0xFF.toUByte()).toUShort() shl 1) or ((bytes[8]  and 0x80.toUByte()).toUShort() shr 7))
            rawCounts.add(0, ((bytes[8]  and 0x7F.toUByte()).toUShort() shl  6) or ((bytes[9]  and 0xFC.toUByte()).toUShort() shr 2))
            rawCounts.add(0, ((bytes[9]  and 0x03.toUByte()).toUShort() shl 11) or ((bytes[10] and 0xFF.toUByte()).toUShort() shl 3) or ((bytes[11] and 0xE0.toUByte()).toUShort() shr 5))
            rawCounts.add(0, ((bytes[11] and 0x1F.toUByte()).toUShort() shl  8) or ((bytes[12] and 0xFF.toUByte()).toUShort() shr 0))

            val temperatures = rawCounts.map{ (it.toDouble() * 0.05) - 20.0 }
            return ProbeTemperatures(temperatures)
        }

        // deserializes temperature data from raw data buffer
        internal fun fromRawData(bytes: UByteArray): ProbeTemperatures {
            return fromReversed(bytes.reversedArray())
        }

        internal fun withRandomData(): ProbeTemperatures {
            val temperatures = mutableListOf<Double>()
            for (i in 0..7) {
                temperatures.add(Random.nextDouble(45.0, 60.0))
            }
            return ProbeTemperatures(temperatures)
        }

        /**
         * Extension function that takes a 13-bit packed temperature and converts it to a
         * floating-point value according to the Combustion BLE specification.
         */
        internal fun UShort.fromRawCount(): Double {
            return ((this.toDouble() * 0.05) - 20.0)
        }
    }
}