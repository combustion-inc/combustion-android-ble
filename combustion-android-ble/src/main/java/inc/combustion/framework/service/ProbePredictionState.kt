/*
 * Project: Combustion Inc. Android Example
 * File: ProbePredictionState.kt
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

enum class ProbePredictionState {
    PROBE_NOT_INSERTED,
    PROBE_INSERTED,
    COOKING,
    PREDICTING,
    REMOVAL_PREDICTION_DONE,
    RESERVED_STATE_5,
    RESERVED_STATE_6,
    RESERVED_STATE_7,
    RESERVED_STATE_8,
    RESERVED_STATE_9,
    RESERVED_STATE_10,
    RESERVED_STATE_11,
    RESERVED_STATE_12,
    RESERVED_STATE_13,
    RESERVED_STATE_14,
    UNKNOWN;

    override fun toString(): String {
        return when(this) {
            PROBE_NOT_INSERTED -> "Probe Not Inserted"
            PROBE_INSERTED -> "Probe Inserted"
            COOKING -> "Cooking"
            PREDICTING -> "Predicting"
            REMOVAL_PREDICTION_DONE -> "Removal Prediction Done"
            RESERVED_STATE_5 -> "Reserved State 5"
            RESERVED_STATE_6 -> "Reserved State 6"
            RESERVED_STATE_7 -> "Reserved State 7"
            RESERVED_STATE_8 -> "Reserved State 8"
            RESERVED_STATE_9 -> "Reserved State 9"
            RESERVED_STATE_10 -> "Reserved State 10"
            RESERVED_STATE_11 -> "Reserved State 11"
            RESERVED_STATE_12 -> "Reserved State 12"
            RESERVED_STATE_13 -> "Reserved State 13"
            RESERVED_STATE_14 -> "Reserved State 14"
            UNKNOWN -> "Unknown"
        }
    }

    companion object {
        private const val DEVICE_STATUS_MASK = 0x000F
        private const val DEVICE_STATUS_SHIFT = 0x0000
        private const val LOG_RESPONSE_MASK = 0x0780
        private const val LOG_RESPONSE_SHIFT = 0x07

        fun fromPredictionStatus(byte: UByte) : ProbePredictionState {
            return fromUShortWorker(byte.toUShort(), DEVICE_STATUS_MASK.toUShort(), DEVICE_STATUS_SHIFT)
        }

        fun fromLogResponse(word: UShort) : ProbePredictionState {
            return fromUShortWorker(word, LOG_RESPONSE_MASK.toUShort(), LOG_RESPONSE_SHIFT)
        }

        private fun fromUShortWorker(ushort: UShort, mask: UShort, shift: Int) : ProbePredictionState {
            val raw = ((ushort and mask) shr shift).toUInt()
            return fromRaw(raw)
        }

        private fun fromRaw(raw: UInt) : ProbePredictionState {
            return when(raw) {
                0x00u -> PROBE_NOT_INSERTED
                0x01u -> PROBE_INSERTED
                0x02u -> COOKING
                0x03u -> PREDICTING
                0x04u -> REMOVAL_PREDICTION_DONE
                0x05u -> RESERVED_STATE_5
                0x06u -> RESERVED_STATE_6
                0x07u -> RESERVED_STATE_7
                0x08u -> RESERVED_STATE_8
                0x09u -> RESERVED_STATE_9
                0x0Au -> RESERVED_STATE_10
                0x0Bu -> RESERVED_STATE_11
                0x0Cu -> RESERVED_STATE_12
                0x0Du -> RESERVED_STATE_13
                0x0Eu -> RESERVED_STATE_14
                0x0Fu -> UNKNOWN
                else -> UNKNOWN
            }
        }
    }
}