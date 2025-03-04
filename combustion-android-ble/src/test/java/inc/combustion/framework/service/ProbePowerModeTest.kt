/*
 * Project: Combustion Inc. Android Framework
 * File: ProbePowerModeTest.kt
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
import junitparams.naming.TestCaseName
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(JUnitParamsRunner::class)
internal class ProbePowerModeTest {
    
    @Test
    @Parameters(method = "fromUByteParams")
    @TestCaseName("given byte {0} when get call fromUByte then expect powerMode {1}")
    fun `given byte when call fromUByte then expect correct powerMode`(
        givenByte: UByte?, // compiler require nullable for some reason
        expectedPowerMode: ProbePowerMode,
    ) {
        assertEquals(ProbePowerMode.fromUByte(givenByte!!), expectedPowerMode)
    }

    fun fromUByteParams() = arrayOf(
        arrayOf((0u).toUByte(), ProbePowerMode.NORMAL),
        arrayOf((12u).toUByte(), ProbePowerMode.NORMAL),
        arrayOf((1u).toUByte(), ProbePowerMode.ALWAYS_ON),
        arrayOf((53u).toUByte(), ProbePowerMode.ALWAYS_ON),
    )
}