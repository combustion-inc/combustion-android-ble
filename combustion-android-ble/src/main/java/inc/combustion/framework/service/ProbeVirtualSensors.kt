/*
 * Project: Combustion Inc. Android Example
 * File: ProbeVirtualSensors.kt
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

import inc.combustion.framework.ble.shr

data class ProbeVirtualSensors(
    val virtualCoreSensor: VirtualCoreSensor = VirtualCoreSensor.T1,
    val virtualSurfaceSensor: VirtualSurfaceSensor = VirtualSurfaceSensor.T4,
    val virtualAmbientSensor: VirtualAmbientSensor = VirtualAmbientSensor.T8
) {
    companion object {
        val DEFAULT = ProbeVirtualSensors(VirtualCoreSensor.T1, VirtualSurfaceSensor.T4, VirtualAmbientSensor.T8)

        private const val DEVICE_STATUS_MASK = 0xFE
        private const val DEVICE_STATUS_SHIFT = 0x01
        private const val LOG_RESPONSE_MASK = 0x7F
        private const val LOG_RESPONSE_SHIFT = 0x00

        fun fromDeviceStatus(byte: UByte) : ProbeVirtualSensors {
            return fromUByteWorker(byte, DEVICE_STATUS_MASK.toUShort(), DEVICE_STATUS_SHIFT)
        }

        fun fromLogResponse(word: UShort) : ProbeVirtualSensors {
            return fromUByteWorker(word.toUByte(), LOG_RESPONSE_MASK.toUShort(), LOG_RESPONSE_SHIFT)
        }

        private fun fromUByteWorker(byte: UByte, mask: UShort, shift: Int) : ProbeVirtualSensors{
            val rawUByte = ((byte.toUShort() and mask) shr shift).toUByte()
            return ProbeVirtualSensors(
                VirtualCoreSensor.fromUByte(rawUByte),
                VirtualSurfaceSensor.fromUByte(rawUByte),
                VirtualAmbientSensor.fromUByte(rawUByte)
            )
        }
    }

    enum class VirtualCoreSensor {
        T1, T2, T3, T4, T5, T6;

        override fun toString(): String {
            return when(this) {
                T1 -> "T1"
                T2 -> "T2"
                T3 -> "T3"
                T4 -> "T4"
                T5 -> "T5"
                T6 -> "T6"
            }
        }

        fun temperatureFrom(temperatures: ProbeTemperatures): Double {
            return when(this) {
                T1 -> temperatures.values[0]
                T2 -> temperatures.values[1]
                T3 -> temperatures.values[2]
                T4 -> temperatures.values[3]
                T5 -> temperatures.values[4]
                T6 -> temperatures.values[5]
            }
        }

        companion object {
            private const val MASK = 0x07
            private const val SHIFT = 0x00

            fun fromUByte(byte: UByte) : VirtualCoreSensor {
                val raw = ((byte.toUShort() and MASK.toUShort()) shr SHIFT).toUInt()
                return fromRaw(raw)
            }

            private fun fromRaw(raw: UInt) : VirtualCoreSensor{
                return when(raw) {
                    0x00u -> T1
                    0x01u -> T2
                    0x02u -> T3
                    0x03u -> T4
                    0x04u -> T5
                    0x05u -> T6
                    else -> T1
                }
            }
        }
    }

    enum class VirtualSurfaceSensor {
        T4, T5, T6, T7;

        override fun toString(): String {
            return when(this) {
                T4 -> "T4"
                T5 -> "T5"
                T6 -> "T6"
                T7 -> "T7"
            }
        }

        fun temperatureFrom(temperatures: ProbeTemperatures): Double {
            return when(this) {
                T4 -> temperatures.values[3]
                T5 -> temperatures.values[4]
                T6 -> temperatures.values[5]
                T7 -> temperatures.values[6]
            }
        }

        companion object {
            private const val MASK = 0x18
            private const val SHIFT = 0x03

            fun fromUByte(byte: UByte) : VirtualSurfaceSensor {
                val raw = ((byte.toUShort() and MASK.toUShort()) shr SHIFT).toUInt()
                return fromRaw(raw)
            }

            private fun fromRaw(raw: UInt) : VirtualSurfaceSensor{
                return when(raw) {
                    0x00u -> T4
                    0x01u -> T5
                    0x02u -> T6
                    0x03u -> T7
                    else -> T4
                }
            }
        }
    }

    enum class VirtualAmbientSensor {
        T5, T6, T7, T8;

        override fun toString(): String {
            return when(this) {
                T5 -> "T5"
                T6 -> "T6"
                T7 -> "T7"
                T8 -> "T8"
            }
        }

        fun temperatureFrom(temperatures: ProbeTemperatures): Double {
            return when(this) {
                T5 -> temperatures.values[4]
                T6 -> temperatures.values[5]
                T7 -> temperatures.values[6]
                T8 -> temperatures.values[7]
            }
        }

        companion object {
            private const val MASK = 0x60
            private const val SHIFT = 0x05

            fun fromUByte(byte: UByte) : VirtualAmbientSensor {
                val raw = ((byte.toUShort() and MASK.toUShort()) shr SHIFT).toUInt()
                return fromRaw(raw)
            }

            private fun fromRaw(raw: UInt) : VirtualAmbientSensor{
                return when(raw) {
                    0x00u -> T5
                    0x01u -> T6
                    0x02u -> T7
                    0x03u -> T8
                    else -> T8
                }
            }
        }
    }
}
