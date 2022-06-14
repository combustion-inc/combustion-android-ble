/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeMode.kt
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

enum class ProbeMode(val type: UByte) {
    NORMAL(0x00u),
    INSTANT_READ(0x01u),
    RESERVED(0x02u),
    ERROR(0x03u);

    companion object {
        private const val PROBE_ID_MASK = 0x03

        fun fromUByte(byte: UByte) : ProbeMode {
            val probeMode = (byte.toUShort() and PROBE_ID_MASK.toUShort()).toUInt()
            return when(probeMode) {
                0x00u -> NORMAL
                0x01u -> INSTANT_READ
                0x02u -> RESERVED
                0x03u -> ERROR
                else -> NORMAL
            }
        }
    }
}
