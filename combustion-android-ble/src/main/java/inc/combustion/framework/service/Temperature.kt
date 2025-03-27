/*
 * Project: Combustion Inc. Android Framework
 * File: GaugeTemperatures.kt
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

package inc.combustion.framework.service

import inc.combustion.framework.ble.shl
import inc.combustion.framework.ble.shr
import kotlin.random.Random

@JvmInline
value class Temperature(
    val value: Double,
) {

    companion object {
        private fun UShort.temperatureFromRaw() =
            (this.toDouble() * 0.05) - 20.0

        internal fun fromRawDataStart(bytes: UByteArray): Temperature {
            require(bytes.size >= 2) { "Temperature requires 2 bytes" }
            val rawTemperature =
                ((bytes[0] and 0xFF.toUByte()).toUShort() shl 5) or ((bytes[1] and 0xF8.toUByte()).toUShort() shr 3)
            val temperature = rawTemperature.temperatureFromRaw()
            return Temperature(temperature)
        }

        internal fun fromRawDataEnd(bytes: UByteArray): Temperature {
            require(bytes.size >= 2) { "Temperature requires 2 bytes" }
            val rawTemperature =
                ((bytes[0] and 0x1F.toUByte()).toUShort() shl 8) or (bytes[1].toUShort())
            val temperature = rawTemperature.temperatureFromRaw()
            return Temperature(temperature)
        }

        internal fun withRandomData(): Temperature {
            return Temperature(Random.nextDouble(45.0, 60.0))
        }
    }
}