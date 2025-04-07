/*
 * Project: Combustion Inc. Android Framework
 * File: HighLowAlarm.kt
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

import inc.combustion.framework.isBitSet
import inc.combustion.framework.setBit

data class HighLowAlarmStatus(
    val highStatus: AlarmStatus,
    val lowStatus: AlarmStatus,
) {
    data class AlarmStatus(
        val set: Boolean = false,
        val tripped: Boolean = false,
        val alarming: Boolean = false,
        val temperature: Temperature = Temperature(-20.0),
    ) {

        fun toBytes(): UByteArray {
            var flagBits = 0
            if (set) flagBits = flagBits.setBit(0)
            if (tripped) flagBits = flagBits.setBit(1)
            if (alarming) flagBits = flagBits.setBit(2)

            val rawTemp = temperature.toRawUShort()
            val tempByte0 = ((rawTemp.toInt() shr 8) and 0x1F) // only low 5 bits
            val tempByte1 = rawTemp.toInt() and 0xFF

            // Safely merge flags (bits 0–2) with top 5 temp bits (bits 3–7)
            val combinedByte0 = ((tempByte0 shl 3) or flagBits).toUByte()
            val byte1 = tempByte1.toUByte()

            return ubyteArrayOf(combinedByte0, byte1)
        }
    }

    companion object {
        val DEFAULT = HighLowAlarmStatus(
            highStatus = AlarmStatus(),
            lowStatus = AlarmStatus(),
        )

        private fun alarmStatusFromBytes(bytes: UByteArray): AlarmStatus {
            require(bytes.size >= 2) { "AlarmStatus requires 2 bytes" }
            val firstByte = bytes[0]
            return AlarmStatus(
                set = firstByte.isBitSet(0),
                tripped = firstByte.isBitSet(1),
                alarming = firstByte.isBitSet(2),
                temperature = Temperature.fromRawDataEnd(bytes)
            )
        }

        fun fromRawData(bytes: UByteArray): HighLowAlarmStatus {
            require(bytes.size >= 4) { "HighLowAlarmStatus requires 4 bytes" }
            return HighLowAlarmStatus(
                highStatus = alarmStatusFromBytes(bytes.sliceArray(0..1)),
                lowStatus = alarmStatusFromBytes(bytes.sliceArray(2..3)),
            )
        }
    }

    fun toRawData(): UByteArray {
        val highBytes = highStatus.toBytes()
        val lowBytes = lowStatus.toBytes()
        return highBytes + lowBytes
    }
}