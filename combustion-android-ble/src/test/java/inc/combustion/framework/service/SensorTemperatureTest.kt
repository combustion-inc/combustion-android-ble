/*
 * Project: Combustion Inc. Android Framework
 * File: TemperatureTest.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2025. Combustion Inc.
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

import junitparams.JUnitParamsRunner
import junitparams.Parameters
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(JUnitParamsRunner::class)
class SensorTemperatureTest {

    @Test
    @Parameters(method = "tempRawDataParams")
    fun `raw value is same as conversion from raw and then back to raw`(givenTempValue: Double) {
        val givenTemp = SensorTemperature(givenTempValue)
        assertEquals(SensorTemperature.fromRawDataEnd(givenTemp.toRawDataEnd()), givenTemp)
    }

    fun tempRawDataParams() = arrayOf(
        arrayOf(-20.0),
        arrayOf(100.0)
    )

    @Test
    fun `verify no data bytes parses to no data value`() {
        val tempFromRawDataStart = SensorTemperature.fromRawDataStart(UByteArray(2) { 0x0u })
        assertEquals(-20.0, tempFromRawDataStart.value)

        val tempFromRawDataEnd = SensorTemperature.fromRawDataEnd(UByteArray(2) { 0x0u })
        assertEquals(-20.0, tempFromRawDataEnd.value)
    }
}