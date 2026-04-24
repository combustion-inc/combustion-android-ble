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

enum class EngineBatteryLevel(val type: UByte) {
    OK(0x00u),
    LOW_BATTERY(0x01u),
    CRITICAL(0x02u);

    val isLowBattery: Boolean
        get() = this != OK

    companion object {
        private const val ENGINE_BATTERY_LEVEL_MASK = 0x03

        fun fromUByte(byte: UByte): EngineBatteryLevel {
            return when ((byte.toUShort() and ENGINE_BATTERY_LEVEL_MASK.toUShort()).toUByte()) {
                OK.type -> OK
                LOW_BATTERY.type -> LOW_BATTERY
                CRITICAL.type -> CRITICAL
                else -> OK
            }
        }
    }
}