/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeColor.kt
 * Author: https://github.com/miwright2
 *
 * MIT License
 *
 * Copyright (c) 2022. Combustion Inc.
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

import inc.combustion.framework.ble.shl
import inc.combustion.framework.ble.shr

enum class HopCount(val type: UByte) {
    HOP1(0x00u),
    HOP2(0x01u),
    HOP3(0x02u),
    HOP4(0x03u);

    companion object {
        private const val HOP_COUNT_MASK = 0x03
        private const val HOP_COUNT_SHIFT = 6

        fun fromUByte(byte: UByte) : HopCount {
            val rawHopCount = ((byte.toUShort() and (HOP_COUNT_MASK.toUShort() shl HOP_COUNT_SHIFT)) shr HOP_COUNT_SHIFT).toUInt()
            return fromRaw(rawHopCount)
        }

        fun fromRaw(raw: UInt) : HopCount {
            return when(raw) {
                0x00u -> HOP1
                0x01u -> HOP2
                0x02u -> HOP3
                0x03u -> HOP4
                else ->  HOP1
            }
        }

        fun stringValues() : List<String> {
            return values().toList().map { it.toString() }
        }
    }

    val hopCount: UInt
        get() = type + 1u
}
