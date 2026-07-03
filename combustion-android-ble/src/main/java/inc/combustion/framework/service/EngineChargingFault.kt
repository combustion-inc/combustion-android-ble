/*
 * Project: Combustion Inc. Android Framework
 * File: EngineChargingFault.kt
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

enum class EngineChargingFault(val type: UByte) {
    NONE(0x00u),
    OVER_TEMP(0x01u),
    UNDER_TEMP(0x02u),
    NTC_FAULT(0x03u),
    INPUT_FAULT(0x04u),
    BATTERY_MISSING(0x05u),
    CHARGE_TIMER(0x06u),
    CHARGE_STALL(0x07u);

    companion object {
        fun fromUByte(byte: UByte): EngineChargingFault {
            return values().firstOrNull { it.type == byte } ?: NONE
        }
    }
}
