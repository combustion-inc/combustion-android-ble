/*
 * Project: Combustion Inc. Android Framework
 * File: FoodSafeStatusTest.kt
 * Author: Nick Helseth <nick@sasq.io>
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

import org.junit.Assert.*
import org.junit.Test

class FoodSafeStatusTest {
    @Test
    fun `fromRaw State`() {
        val toState: (UByteArray) -> FoodSafeStatus.State = { value ->
            val foodSafeStatus = FoodSafeStatus.fromRawData(value)
            foodSafeStatus!!.state
        }

        val raw = ubyteArrayOf(0x00u, 0x00u, 0x00u, 0x00u)

        raw[0] = 0b1111_1000u
        assertEquals(FoodSafeStatus.State.NotSafe, toState(raw))

        raw[0] = 0b1111_1001u
        assertEquals(FoodSafeStatus.State.Safe, toState(raw))

        raw[0] = 0b1111_1010u
        assertEquals(FoodSafeStatus.State.SafetyImpossible, toState(raw))

        raw[0] = 0b1111_1101u
        assertNull(FoodSafeStatus.fromRawData(raw))
    }

    @Test
    fun `fromRaw Log Reduction`() {
        val raw = ubyteArrayOf(0x00u, 0x00u, 0x00u, 0x00u)

        raw[0] = 0b1010_1000u
        raw[1] = 0b1111_1010u
        assertEquals(8.5, FoodSafeStatus.fromRawData(raw)!!.logReduction, 0.1)

        raw[0] = 0b0101_0000u
        raw[1] = 0b1111_1101u
        assertEquals(17.0, FoodSafeStatus.fromRawData(raw)!!.logReduction, 0.1)
    }

    @Test
    fun `fromRaw Seconds Above Threshold`() {
        val raw = ubyteArrayOf(0x00u, 0x00u, 0x00u, 0x00u)

        raw[1] = 0b1_0101_111u
        raw[2] = 0b1_0101_010u
        raw[3] = 0b1_0101_010u
        assertEquals(21845u, FoodSafeStatus.fromRawData(raw)!!.secondsAboveThreshold)

        raw[1] = 0b0_1010_111u
        raw[2] = 0b0_1010_101u
        raw[3] = 0b0_1010_101u
        assertEquals(43690u, FoodSafeStatus.fromRawData(raw)!!.secondsAboveThreshold)
    }

    @Test
    fun `toRaw and fromRaw translate`() {
        val raw = ubyteArrayOf(0b1010_1010u, 0b0101_0010u, 0b0101_0101u, 0b0101_0101u)

        val foodSafeStatus = FoodSafeStatus(
            state = FoodSafeStatus.State.SafetyImpossible,
            logReduction = 8.5,
            secondsAboveThreshold = 43690u,
        )

        assertEquals(FoodSafeStatus.fromRawData(raw), foodSafeStatus)
    }
}