/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeID.kt
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

enum class ProbeID(val type: UByte) {
    ID1(0x00u),
    ID2(0x01u),
    ID3(0x02u),
    ID4(0x03u),
    ID5(0x04u),
    ID6(0x05u),
    ID7(0x06u),
    ID8(0x07u);

    companion object {
        private const val PROBE_ID_MASK = 0x07
        private const val PROBE_ID_SHIFT = 5

        fun fromUByte(byte: UByte) : ProbeID {
            val probeID = ((byte.toUShort() and (PROBE_ID_MASK.toUShort() shl PROBE_ID_SHIFT)) shr PROBE_ID_SHIFT).toUInt()
            return when(probeID) {
                0x00u -> ID1
                0x01u -> ID2
                0x02u -> ID3
                0x03u -> ID4
                0x04u -> ID5
                0x05u -> ID6
                0x06u -> ID7
                0x07u -> ID8
                else -> ID1
            }
        }
    }
}
