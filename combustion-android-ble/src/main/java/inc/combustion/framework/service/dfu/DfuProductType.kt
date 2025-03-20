/*
 * Project: Combustion Inc. Android Framework
 * File: DfuProductType.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2025. Combustion Inc.
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

package inc.combustion.framework.service.dfu

import inc.combustion.framework.service.CombustionProductType

enum class DfuProductType {
    UNKNOWN,
    PROBE,
    DISPLAY,
    CHARGER,
    GAUGE;

    companion object {

        /**
         * Returns the [DfuProductType] according to the string in the DIS Model Number
         * characteristic.
         */
        fun fromModelString(model: String): DfuProductType {
            return when (model) {
                "Timer" -> DISPLAY
                "Charger" -> CHARGER
                "" -> PROBE
                "Gauge" -> GAUGE
                else -> UNKNOWN
            }
        }

        /**
         * Returns the [DfuProductType] from [CombustionProductType].
         * NB, not ideal since [CombustionProductType.NODE] can be multiple types, eg [CHARGER], [DISPLAY], or [GAUGE]
         */
        fun fromCombustionProductType(productType: CombustionProductType): DfuProductType =
            when (productType) {
                CombustionProductType.PROBE -> PROBE
                CombustionProductType.GAUGE -> GAUGE
                CombustionProductType.NODE -> UNKNOWN
                else -> UNKNOWN
            }
    }
}