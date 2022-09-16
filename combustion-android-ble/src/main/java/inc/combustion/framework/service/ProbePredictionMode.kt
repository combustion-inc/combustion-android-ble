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

    companion object {
        private const val MASK = 0x30
        private const val SHIFT = 0x04

        fun fromPredictionStatus(byte: UByte) : ProbePredictionMode {
            val raw = ((byte.toUShort() and MASK.toUShort()) shr SHIFT).toUInt()
            return fromRaw(raw)
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