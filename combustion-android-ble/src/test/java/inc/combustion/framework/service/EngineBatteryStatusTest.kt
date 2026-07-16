/*
 * Project: Combustion Inc. Android Framework
 * File: EngineBatteryStatusTest.kt
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

class EngineBatteryStatusTest {
    @Test
    fun `fromRaw with not enough data returns null`() {
        val raw = UByteArray(EngineBatteryStatus.RAW_SIZE - 1) { 0xFFu }

        assertNull(EngineBatteryStatus.fromRaw(raw))
    }

    @Test
    fun `fromRaw parses level, state, and voltage`() {
        val raw = ubyteArrayOf(
            EngineBatteryLevel.CRITICAL.type,
            EngineBatteryState.CHARGING.type,
            0xC8u, // 200 -> 20.0V
        )

        val status = EngineBatteryStatus.fromRaw(raw)

        assertEquals(
            EngineBatteryStatus(
                batteryLevel = EngineBatteryLevel.CRITICAL,
                batteryState = EngineBatteryState.CHARGING,
                batteryVoltageTenthsVolts = 200u,
            ),
            status,
        )
        assertEquals(20.0f, status?.batteryVoltageVolts)
    }

    @Test
    fun `fromRaw parses OK and not charging defaults`() {
        val raw = ubyteArrayOf(
            EngineBatteryLevel.OK.type,
            EngineBatteryState.NOT_CHARGING.type,
            0x00u,
        )

        val status = EngineBatteryStatus.fromRaw(raw)

        assertEquals(EngineBatteryLevel.OK, status?.batteryLevel)
        assertEquals(EngineBatteryState.NOT_CHARGING, status?.batteryState)
        assertEquals(0.0f, status?.batteryVoltageVolts)
    }

    @Test
    fun `fromRaw ignores trailing bytes`() {
        val raw = ubyteArrayOf(
            EngineBatteryLevel.LOW_BATTERY.type,
            EngineBatteryState.FULLY_CHARGED.type,
            0x32u, // 50 -> 5.0V
            0xFFu,
            0xFFu,
        )

        val status = EngineBatteryStatus.fromRaw(raw)

        assertEquals(
            EngineBatteryStatus(
                batteryLevel = EngineBatteryLevel.LOW_BATTERY,
                batteryState = EngineBatteryState.FULLY_CHARGED,
                batteryVoltageTenthsVolts = 50u,
            ),
            status,
        )
    }
}
