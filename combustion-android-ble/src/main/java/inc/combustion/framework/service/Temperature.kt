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

    fun toRawUShort(): UShort {
        return (((value + 20.0) / 0.05).toInt().coerceIn(0, 0xFFFF)).toUShort()
    }

    fun toRawDataEnd(): UByteArray {
        val raw = this.toRawUShort() // temperature to 13-bit value

        val high = (raw.toInt() shr 8) and 0x1F       // get top 5 bits
        val low = raw.toInt() and 0xFF                // lower 8 bits

        val byte0 = (high shl 3).toUByte()            // shift into bits 3–7
        val byte1 = low.toUByte()

        return ubyteArrayOf(byte0, byte1)
    }

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

            val high = (bytes[0].toInt() shr 3) and 0x1F
            val low = bytes[1].toInt()

            val raw = ((high shl 8) or low).toUShort()
            val temperature = raw.temperatureFromRaw()

            return Temperature(temperature)
        }

        internal fun withRandomData(): Temperature {
            return Temperature(Random.nextDouble(45.0, 60.0))
        }
    }
}