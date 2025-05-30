/*
 * Project: Combustion Inc. Android Framework
 * File: CombustionProductType.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2023. Combustion Inc.
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

enum class CombustionProductType(val type: UByte) {
    UNKNOWN(0x00u),
    PROBE(0x01u),
    DISPLAY(0x02u), // TODO : rename to NODE
    CHARGER(0x03u); // TODO : rename to GAUGE

    companion object {
        fun fromUByte(byte: UByte) : CombustionProductType {
            return when(byte.toUInt()) {
                0x01u -> PROBE
                0x02u -> DISPLAY
                0x03u -> CHARGER
                else -> UNKNOWN
            }
        }

        /**
         * Returns the [CombustionProductType] according to the string in the DIS Model Number
         * characteristic.
         */
        fun fromModelString(model: String) : CombustionProductType {
            return when(model) {
                "Timer" -> DISPLAY
                "Charger" -> CHARGER
                "" -> PROBE
                else -> UNKNOWN
            }
        }
    }

    val isRepeater: Boolean get() = this != PROBE
}
