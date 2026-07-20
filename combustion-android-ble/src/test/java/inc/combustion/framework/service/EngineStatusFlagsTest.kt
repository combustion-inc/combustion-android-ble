/*
 * Project: Combustion Inc. Android Framework
 * File: EngineStatusFlagsTest.kt
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

class EngineStatusFlagsTest {
    @Test
    fun `fromRawByte with no bits set`() {
        val flags = EngineStatusFlags.fromRawByte(0b0000_0000u)

        assertEquals(
            EngineStatusFlags(
                appMode = false,
                controlDeviceConnected = false,
                lidOpen = false,
                fixedSpeed = false,
            ),
            flags,
        )
    }

    @Test
    fun `fromRawByte with all four bits set`() {
        val flags = EngineStatusFlags.fromRawByte(0b0000_1111u)

        assertEquals(
            EngineStatusFlags(
                appMode = true,
                controlDeviceConnected = true,
                lidOpen = true,
                fixedSpeed = true,
            ),
            flags,
        )
    }

    @Test
    fun `fromRawByte with only appMode set`() {
        val flags = EngineStatusFlags.fromRawByte(0b0000_0001u)

        assertEquals(
            EngineStatusFlags(
                appMode = true,
                controlDeviceConnected = false,
                lidOpen = false,
                fixedSpeed = false,
            ),
            flags,
        )
    }

    @Test
    fun `fromRawByte with only controlDeviceConnected set`() {
        val flags = EngineStatusFlags.fromRawByte(0b0000_0010u)

        assertEquals(
            EngineStatusFlags(
                appMode = false,
                controlDeviceConnected = true,
                lidOpen = false,
                fixedSpeed = false,
            ),
            flags,
        )
    }

    @Test
    fun `fromRawByte with only lidOpen set`() {
        val flags = EngineStatusFlags.fromRawByte(0b0000_0100u)

        assertEquals(
            EngineStatusFlags(
                appMode = false,
                controlDeviceConnected = false,
                lidOpen = true,
                fixedSpeed = false,
            ),
            flags,
        )
    }

    @Test
    fun `fromRawByte with only fixedSpeed set`() {
        val flags = EngineStatusFlags.fromRawByte(0b0000_1000u)

        assertEquals(
            EngineStatusFlags(
                appMode = false,
                controlDeviceConnected = false,
                lidOpen = false,
                fixedSpeed = true,
            ),
            flags,
        )
    }

    @Test
    fun `fromRawByte ignores unrelated high bits`() {
        val flags = EngineStatusFlags.fromRawByte(0b1111_0000u)

        assertEquals(
            EngineStatusFlags(
                appMode = false,
                controlDeviceConnected = false,
                lidOpen = false,
                fixedSpeed = false,
            ),
            flags,
        )
    }
}
