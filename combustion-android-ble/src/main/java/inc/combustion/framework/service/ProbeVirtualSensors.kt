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
    val virtualCoreSensor: VirtualCoreSensor,
    val virtualSurfaceSensor: VirtualSurfaceSensor
) {
    companion object {
        val DEFAULT = ProbeVirtualSensors(VirtualCoreSensor.T1, VirtualSurfaceSensor.T4)

        private const val DEVICE_STATUS_MASK = 0x3E
        private const val DEVICE_STATUS_SHIFT = 0x01
        private const val LOG_RESPONSE_MASK = 0x1F
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
                VirtualSurfaceSensor.fromUByte(rawUByte)
            )
        }
    }

    enum class VirtualCoreSensor(val uByte: UByte) {
        T1(0x00u),
        T2(0x01u),
        T3(0x02u),
        T4(0x03u),
        T5(0x04u),
        T6(0x05u);

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

    enum class VirtualSurfaceSensor(val uByte: UByte) {
        T4(0x00u),
        T5(0x01u),
        T6(0x02u),
        T7(0x03u);

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
}
