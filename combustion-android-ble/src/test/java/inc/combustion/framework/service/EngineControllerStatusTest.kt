/*
 * Project: Combustion Inc. Android Framework
 * File: EngineControllerStatusTest.kt
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
import org.junit.Assert.assertNull
import org.junit.Test

class EngineControllerStatusTest {
    @Test
    fun `fromRawData with not enough data returns null`() {
        val raw = UByteArray(EngineControllerStatus.RAW_SIZE - 1) { 0xFFu }

        assertNull(EngineControllerStatus.fromRawData(raw))
    }

    @Test
    fun `fromRawData parses all fields, including a negative drift rate`() {
        val raw = ubyteArrayOf(
            EngineControllerState.OBSERVE.type, // state
            250u, // response coefficient -> 250 / 500.0 = 0.5
            42u, // cycles completed
            0b0000_0011u, // flags: reachedSetpoint + maintenanceMode
            0xE8u, 0x03u, // smoothed temperature (little-endian u16) = 1000 -> 100.0C
            77u, // time to peak seconds
            0xF6u, // drift rate: -10 (signed byte) -> -10 / 1000.0
        )

        val status = EngineControllerStatus.fromRawData(raw)

        assertEquals(EngineControllerState.OBSERVE, status?.state)
        assertEquals(0.5f, status?.responseCoefficient)
        assertEquals(42u.toUByte(), status?.cyclesCompleted)
        assertEquals(EngineControllerFlags(reachedSetpoint = true, maintenanceMode = true), status?.flags)
        assertEquals(100.0f, status?.smoothedTemperatureCelsius)
        assertEquals(77u.toUByte(), status?.timeToPeakSeconds)
        status?.driftRateCelsiusPerSecond?.let {
            assertEquals(-0.01f, it, 0.0001f)
        } ?: run {
            assert(false)
        }
    }

    @Test
    fun `fromRawData parses a positive drift rate`() {
        val raw = ubyteArrayOf(
            EngineControllerState.IDLE.type,
            0u,
            0u,
            0b0000_0000u,
            0x00u, 0x00u,
            0u,
            50u, // +50 / 1000.0 = 0.05
        )

        val status = EngineControllerStatus.fromRawData(raw)

        status?.driftRateCelsiusPerSecond?.let {
            assertEquals(0.05f, it, 0.0001f)
        } ?: run {
            assert(false)
        }
    }
}
