/*
 * Project: Combustion Inc. Android Framework
 * File: InstantReadFilterTest.kt
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

package inc.combustion.framework

import org.junit.Assert.*

import org.junit.Test

class InstantReadFilterTest {
    private val instantReadFilter = InstantReadFilter()

    @Test
    fun `Instant read values are null if input is null`() {
        instantReadFilter.addReading(null)

        assertEquals(null, instantReadFilter.values)
    }

    @Test
    fun `Instant read values are rounded to whole numbers on first input`() {
        instantReadFilter.addReading(20.0)

        assertEquals(Pair(20.0, 68.0), instantReadFilter.values)
    }

    @Test
    fun `Instant read values are rounded to whole numbers on identical inputs`() {
        instantReadFilter.addReading(20.0)
        assertEquals(Pair(20.0, 68.0), instantReadFilter.values)

        instantReadFilter.addReading(20.0)
        assertEquals(Pair(20.0, 68.0), instantReadFilter.values)
    }

    @Test
    fun `Instant read values do not change when deadband is not exceeded`() {
        instantReadFilter.addReading(20.0)
        assertEquals(Pair(20.0, 68.0), instantReadFilter.values)

        instantReadFilter.addReading(20.2)
        assertEquals(Pair(20.0, 68.0), instantReadFilter.values)

        instantReadFilter.addReading(19.68)
        assertEquals(Pair(20.0, 68.0), instantReadFilter.values)
    }

    @Test
    fun `Only Fahrenheit changes with small swings in input temperature`() {
        instantReadFilter.addReading(20.0)
        assertEquals(Pair(20.0, 68.0), instantReadFilter.values)

        instantReadFilter.addReading(20.2)
        assertEquals(Pair(20.0, 68.0), instantReadFilter.values)

        instantReadFilter.addReading(19.6)
        assertEquals(Pair(20.0, 67.0), instantReadFilter.values)
    }

    @Test
    fun `Fahrenheit and Celsius change with large swings in input temperature`() {
        instantReadFilter.addReading(20.0)
        assertEquals(Pair(20.0, 68.0), instantReadFilter.values)

        instantReadFilter.addReading(28.2)
        assertEquals(Pair(28.0, 83.0), instantReadFilter.values)
    }
}