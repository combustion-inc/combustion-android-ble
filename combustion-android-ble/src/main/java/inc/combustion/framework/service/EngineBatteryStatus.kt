/*
 * Project: Combustion Inc. Android Framework
 * File: EngineBatteryStatus.kt
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

data class EngineBatteryStatus(
    val batteryLevel: EngineBatteryLevel = EngineBatteryLevel.OK,
    val batteryState: EngineBatteryState = EngineBatteryState.NOT_CHARGING,
    /** Raw battery voltage in tenths of a volt (0–255, i.e. 0–25.5 V). Voltage (V) = raw value / 10.0 */
    val batteryVoltageTenthsVolts: UByte = 0u,
) {

    val batteryVoltageVolts: Float
        get() = batteryVoltageTenthsVolts.toFloat() / 10.0f

    companion object {
        const val RAW_SIZE = 3

        private const val IDX_LEVEL = 0
        private const val IDX_STATUS = 1
        private const val IDX_VOLTAGE = 2

        fun fromRaw(data: UByteArray): EngineBatteryStatus? {
            if (data.size < RAW_SIZE) return null

            return EngineBatteryStatus(
                batteryLevel = EngineBatteryLevel.fromUByte(data[IDX_LEVEL]),
                batteryState = EngineBatteryState.fromUByte(data[IDX_STATUS]),
                batteryVoltageTenthsVolts = data[IDX_VOLTAGE],
            )
        }
    }
}