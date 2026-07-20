/*
 * Project: Combustion Inc. Android Framework
 * File: ThermometerPreferencesTest.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2026. Combustion Inc.
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

import org.junit.Assert.assertEquals
import org.junit.Test

class ThermometerPreferencesTest {
    @Test
    fun `fromRawByte with no bits set`() {
        assertEquals(
            ThermometerPreferences(powerMode = ProbePowerMode.NORMAL, highRadioPower = false),
            ThermometerPreferences.fromRawByte(0b0000_0000u),
        )
    }

    @Test
    fun `fromRawByte with highRadioPower set`() {
        // Bit 2, not bit 0, since bits 0-1 are ProbePowerMode.
        assertEquals(
            ThermometerPreferences(powerMode = ProbePowerMode.NORMAL, highRadioPower = true),
            ThermometerPreferences.fromRawByte(0b0000_0100u),
        )
    }

    @Test
    fun `fromRawByte with power mode always on`() {
        assertEquals(
            ThermometerPreferences(powerMode = ProbePowerMode.ALWAYS_ON, highRadioPower = false),
            ThermometerPreferences.fromRawByte(0b0000_0001u),
        )
    }

    @Test
    fun `fromRawByte with power mode and highRadioPower both set`() {
        assertEquals(
            ThermometerPreferences(powerMode = ProbePowerMode.ALWAYS_ON, highRadioPower = true),
            ThermometerPreferences.fromRawByte(0b0000_0101u),
        )
    }

    @Test
    fun `fromRawByte ignores unrelated high bits`() {
        assertEquals(
            ThermometerPreferences(powerMode = ProbePowerMode.NORMAL, highRadioPower = false),
            ThermometerPreferences.fromRawByte(0b1111_1000u),
        )
    }

    @Test
    fun `DEFAULT is normal power mode with high radio power off`() {
        assertEquals(
            ThermometerPreferences(powerMode = ProbePowerMode.NORMAL, highRadioPower = false),
            ThermometerPreferences.DEFAULT,
        )
    }
}
