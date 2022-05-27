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

enum class ProbeColor(val type: UByte) {
    COLOR1(0x00u),
    COLOR2(0x01u),
    COLOR3(0x02u),
    COLOR4(0x03u),
    COLOR5(0x04u),
    COLOR6(0x05u),
    COLOR7(0x06u),
    COLOR8(0x07u);

    companion object {
        private const val PROBE_COLOR_MASK = 0x07
        private const val PROBE_COLOR_SHIFT = 2

        fun fromUByte(byte: UByte) : ProbeColor {
            val rawProbeColor = ((byte.toUShort() and (PROBE_COLOR_MASK.toUShort() shl PROBE_COLOR_SHIFT)) shr PROBE_COLOR_SHIFT).toUInt()
            return when(rawProbeColor) {
                0x00u -> COLOR1
                0x01u -> COLOR2
                0x02u -> COLOR3
                0x03u -> COLOR4
                0x04u -> COLOR5
                0x05u -> COLOR6
                0x06u -> COLOR7
                0x07u -> COLOR8
                else -> COLOR1
            }
        }
    }
}
