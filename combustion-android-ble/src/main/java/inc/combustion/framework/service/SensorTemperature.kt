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
import kotlin.math.roundToInt
import kotlin.random.Random

@JvmInline
value class SensorTemperature(
    val value: Double,
) : DeviceTemperature {

    fun toRawDataEnd(): UByteArray {
        // Convert temperature to 13-bit raw value
        val raw13 = ((this.value + 20.0) / 0.1).roundToInt().coerceIn(0, 0x1FFF)

        // Shift left to place raw13 into bits 3–15 (leave bits 0–2 as 0)
        val raw16 = (raw13 shl 3).toUShort()

        // Split into little-endian bytes
        val lowByte = (raw16.toUInt() and 0xFFu).toUByte()
        val highByte = ((raw16.toUInt() shr 8) and 0xFFu).toUByte()

        return ubyteArrayOf(lowByte, highByte)
    }

    companion object {
        private fun UShort.temperatureFromRaw() =
            (this.toDouble() * 0.1) - 20.0

        internal fun fromRawDataStart(bytes: UByteArray): SensorTemperature {
            require(bytes.size >= 2) { "Temperature requires 2 bytes" }
            val raw16 = bytes[0].toUShort() or (bytes[1].toUShort() shl 8)
            return SensorTemperature(raw16.temperatureFromRaw())
        }

        internal fun fromRawDataEnd(bytes: UByteArray): SensorTemperature {
            require(bytes.size >= 2) { "Temperature requires 2 bytes" }

            // Combine bytes into a 16-bit value (little-endian)
            val raw16: UShort = (bytes[0].toUInt() or (bytes[1].toUInt() shl 8)).toUShort()

            // Shift to discard first 3 bits, and keep 13 bits
            val raw13: UShort = ((raw16.toUInt() shr 3) and 0x1FFFu).toUShort()

            return SensorTemperature(raw13.temperatureFromRaw())
        }

        internal fun withRandomData(): SensorTemperature {
            return SensorTemperature(Random.nextDouble(45.0, 60.0))
        }
    }
}