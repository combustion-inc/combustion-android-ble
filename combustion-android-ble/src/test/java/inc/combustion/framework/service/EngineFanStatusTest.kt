/*
 * Project: Combustion Inc. Android Framework
 * File: EngineFanStatusTest.kt
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

import inc.combustion.framework.ble.putLittleEndianUInt32At
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineFanStatusTest {
    @Test
    fun `fromRawData with not enough data returns null`() {
        val raw = UByteArray(11) { 0xFFu }

        assertNull(EngineFanStatus.fromRawData(raw))
    }

    @Test
    fun `fromRawData parses all fields`() {
        val raw = UByteArray(12)
        raw[0] = EngineFanState.FAN_ON.type
        raw[1] = 80u // duty cycle
        raw[2] = 75u // commanded speed
        raw[3] = 70u // measured speed
        raw.putLittleEndianUInt32At(4, 123_456u) // fan off time ms
        raw.putLittleEndianUInt32At(8, 654_321u) // fan on time ms

        val status = EngineFanStatus.fromRawData(raw)

        assertEquals(
            EngineFanStatus(
                fanState = EngineFanState.FAN_ON,
                dutyCycle = 80u,
                commandedSpeed = 75u,
                measuredSpeed = 70u,
                fanOffTimeMs = 123_456u,
                fanOnTimeMs = 654_321u,
            ),
            status,
        )
    }

    @Test
    fun `fromRawData ignores trailing bytes beyond RAW_SIZE`() {
        val raw = UByteArray(15)
        raw[0] = EngineFanState.PAUSED.type
        raw.putLittleEndianUInt32At(4, 1u)
        raw.putLittleEndianUInt32At(8, 2u)

        assertEquals(EngineFanState.PAUSED, EngineFanStatus.fromRawData(raw)?.fanState)
    }
}
