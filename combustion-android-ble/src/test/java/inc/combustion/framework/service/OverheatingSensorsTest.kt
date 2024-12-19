/*
 * Project: Combustion Inc. Android Framework
 * File: OverheatingSensorsTest.kt
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

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class OverheatingSensorsTest {

    @Test
    fun `Check single overheating sensor`() {
        val none = ProbeTemperatures(
            values = listOf(
                20.0, 20.0, 20.0, 20.0, 20.0, 20.0, 20.0, 20.0,
            )
        )

        assertTrue(OverheatingSensors.fromTemperatures(none).values.isEmpty())

        val t1 = ProbeTemperatures(
            values = listOf(
                106.0, 20.0, 20.0, 20.0, 20.0, 20.0, 20.0, 20.0,
            )
        )

        assertEquals(listOf(0), OverheatingSensors.fromTemperatures(t1).values)

        val t2 = ProbeTemperatures(
            values = listOf(
                20.0, 106.0, 20.0, 20.0, 20.0, 20.0, 20.0, 20.0,
            )
        )

        assertEquals(listOf(1), OverheatingSensors.fromTemperatures(t2).values)

        val t3NotOverheated = ProbeTemperatures(
            values = listOf(
                20.0, 20.0, 106.0, 20.0, 20.0, 20.0, 20.0, 20.0,
            )
        )

        assertEquals(listOf(), OverheatingSensors.fromTemperatures(t3NotOverheated).values)

        val t3Overheated = ProbeTemperatures(
            values = listOf(
                20.0, 20.0, 116.0, 20.0, 20.0, 20.0, 20.0, 20.0,
            )
        )

        assertEquals(listOf(2), OverheatingSensors.fromTemperatures(t3Overheated).values)

        val t4NotOverheated = ProbeTemperatures(
            values = listOf(
                20.0, 20.0, 20.0, 116.0, 20.0, 20.0, 20.0, 20.0,
            )
        )

        assertEquals(listOf(), OverheatingSensors.fromTemperatures(t4NotOverheated).values)

        val t4Overheated = ProbeTemperatures(
            values = listOf(
                20.0, 20.0, 20.0, 126.0, 20.0, 20.0, 20.0, 20.0,
            )
        )

        assertEquals(listOf(3), OverheatingSensors.fromTemperatures(t4Overheated).values)

        val t5NotOverheated = ProbeTemperatures(
            values = listOf(
                20.0, 20.0, 20.0, 20.0, 126.0, 20.0, 20.0, 20.0,
            )
        )

        assertEquals(listOf(), OverheatingSensors.fromTemperatures(t5NotOverheated).values)

        val t5Overheated = ProbeTemperatures(
            values = listOf(
                20.0, 20.0, 20.0, 20.0, 320.0, 20.0, 20.0, 20.0,
            )
        )

        assertEquals(listOf(4), OverheatingSensors.fromTemperatures(t5Overheated).values)
    }

    @Test
    fun `Check multiple overheating sensors`() {
        val overheated = ProbeTemperatures(
            values = listOf(
                20.0, 106.0, 20.0, 126.0, 20.0, 318.0, 20.0, 340.0,
            )
        )

        assertEquals(listOf(1, 3, 5, 7), OverheatingSensors.fromTemperatures(overheated).values)

        val allOverheated = ProbeTemperatures(
            values = listOf(
                200.0, 200.0, 200.0, 200.0, 318.0, 318.0, 325.0, 340.0,
            )
        )

        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 6, 7),
            OverheatingSensors.fromTemperatures(allOverheated).values,
        )
    }
}