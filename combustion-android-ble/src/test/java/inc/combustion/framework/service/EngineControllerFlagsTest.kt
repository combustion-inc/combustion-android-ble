/*
 * Project: Combustion Inc. Android Framework
 * File: EngineControllerFlagsTest.kt
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

class EngineControllerFlagsTest {
    @Test
    fun `fromRawByte with no bits set`() {
        assertEquals(
            EngineControllerFlags(reachedSetpoint = false, maintenanceMode = false),
            EngineControllerFlags.fromRawByte(0b0000_0000u),
        )
    }

    @Test
    fun `fromRawByte with only reachedSetpoint set`() {
        assertEquals(
            EngineControllerFlags(reachedSetpoint = true, maintenanceMode = false),
            EngineControllerFlags.fromRawByte(0b0000_0001u),
        )
    }

    @Test
    fun `fromRawByte with only maintenanceMode set`() {
        assertEquals(
            EngineControllerFlags(reachedSetpoint = false, maintenanceMode = true),
            EngineControllerFlags.fromRawByte(0b0000_0010u),
        )
    }

    @Test
    fun `fromRawByte with both bits set`() {
        assertEquals(
            EngineControllerFlags(reachedSetpoint = true, maintenanceMode = true),
            EngineControllerFlags.fromRawByte(0b0000_0011u),
        )
    }

    @Test
    fun `fromRawByte ignores unrelated high bits`() {
        assertEquals(
            EngineControllerFlags(reachedSetpoint = false, maintenanceMode = false),
            EngineControllerFlags.fromRawByte(0b1111_1100u),
        )
    }
}
