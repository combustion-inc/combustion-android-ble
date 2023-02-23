/*
 * Project: Combustion Inc. Android Example
 * File: ProbePredictionMode.kt
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

import inc.combustion.framework.ble.shr

enum class ProbePredictionMode(val uByte: UByte) {
    NONE(0x00u),
    TIME_TO_REMOVAL(0x01u),
    REMOVAL_AND_RESTING(0x02u),
    RESERVED(0x04u);

    override fun toString(): String {
        return when(this) {
            NONE -> "None"
            TIME_TO_REMOVAL -> "Time to Removal"
            REMOVAL_AND_RESTING -> "Remove and Resting"
            RESERVED -> "Reserved"
        }
    }

    companion object {
        private const val DEVICE_STATUS_MASK = 0x30
        private const val DEVICE_STATUS_SHIFT = 0x04
        private const val LOG_RESPONSE_MASK = 0x1800
        private const val LOG_RESPONSE_SHIFT = 0x0B

        fun fromPredictionStatus(byte: UByte) : ProbePredictionMode {
            return fromUShortWorker(byte.toUShort(), DEVICE_STATUS_MASK.toUShort(), DEVICE_STATUS_SHIFT)
        }

        fun fromLogResponse(word: UShort) : ProbePredictionMode {
            return fromUShortWorker(word, LOG_RESPONSE_MASK.toUShort(), LOG_RESPONSE_SHIFT)
        }

        private fun fromUShortWorker(ushort: UShort, mask: UShort, shift: Int) : ProbePredictionMode {
            val raw = ((ushort and mask) shr shift).toUInt()
            return ProbePredictionMode.fromRaw(raw)
        }

        private fun fromRaw(raw: UInt) : ProbePredictionMode {
            return when(raw) {
                0x00u -> NONE
                0x01u -> TIME_TO_REMOVAL
                0x02u -> REMOVAL_AND_RESTING
                0x04u -> RESERVED
                else -> NONE
            }
        }
    }
}