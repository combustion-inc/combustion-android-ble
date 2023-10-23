/*
 * Project: Combustion Inc. Android Framework
 * File: FoodSafeStatus.kt
 * Author:
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
import kotlin.random.Random
import kotlin.random.nextUInt

data class FoodSafeStatus(
    val state: State,
    val logReduction: Double,
    val secondsAboveThreshold: UInt,
) {
    enum class State {
        NotSafe,
        Safe,
        SafetyImpossible,
        ;

        override fun toString(): String {
            return when (this) {
                NotSafe -> "Not Safe"
                SafetyImpossible -> "Safety Impossible"
                else -> name
            }
        }

        companion object {
            fun fromRaw(raw: UInt): State {
                return when(raw) {
                    0u -> NotSafe
                    1u -> Safe
                    2u -> SafetyImpossible
                    else -> throw IllegalArgumentException("Invalid state $raw")
                }
            }
        }
    }

    companion object {
        const val SIZE_BYTES = 4
        fun fromRawData(data: UByteArray): FoodSafeStatus? {
            if (data.size < SIZE_BYTES) {
                throw IllegalArgumentException("Invalid buffer")
            }

            val rawState = (data[0].toUShort() and 0b0000_0111u)
            val rawLogReduction =
                ((data[0].toUShort() and 0b1111_1000u) shr 3) or
                ((data[1].toUShort() and 0b0000_0111u) shl 5)
            val secondsAboveThreshold =
                ((data[1].toUShort() and 0b1111_1000u) shr 3) or
                ((data[2].toUShort() and 0b1111_1111u) shl 5) or
                ((data[3].toUShort() and 0b0000_0111u) shl 13)

            return try {
                FoodSafeStatus(
                    state = State.fromRaw(rawState.toUInt()),
                    logReduction = rawLogReduction.toDouble() * 0.1,
                    secondsAboveThreshold = secondsAboveThreshold.toUInt(),
                )
            } catch (e: Exception) {
                null
            }
        }

        val DEFAULT = FoodSafeStatus(
            state = State.NotSafe,
            logReduction = 0.0,
            secondsAboveThreshold = 0u,
        )

        val RANDOM = FoodSafeStatus(
            state = State.fromRaw(Random.nextUInt(until = 3u)),
            logReduction = Random.nextDouble(until = 150.0),
            secondsAboveThreshold = Random.nextUInt(until = 7u * 60u * 60u)
        )
    }
}