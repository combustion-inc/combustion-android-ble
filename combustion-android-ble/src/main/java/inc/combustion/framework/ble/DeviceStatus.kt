/*
 * Project: Combustion Inc. Android Framework
 * File: DeviceStatus.kt
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
package inc.combustion.framework.ble

import inc.combustion.framework.service.*

/**
 * Data object for Device Status packet.
 *
 * @property minSequenceNumber minimum sequence number available for probe.
 * @property maxSequenceNumber maximum/current sequence number available for probe.
 * @property temperatures probe's current temperature values.
 */
internal data class DeviceStatus(
    val minSequenceNumber: UInt,
    val maxSequenceNumber: UInt,
    val temperatures: ProbeTemperatures,
    val id: ProbeID,
    val color: ProbeColor,
    val mode: ProbeMode,
    val batteryStatus: ProbeBatteryStatus
) {
    companion object {
        private const val MIN_SEQ_INDEX = 0
        private const val MAX_SEQ_INDEX = 4
        private val TEMPERATURE_RANGE = 8..20
        private val MODE_COLOR_ID_RANGE = 21..21
        private val STATUS_RANGE = 22..22

        fun fromRawData(data: UByteArray): DeviceStatus? {
            if (data.size < 21) return null

            val minSequenceNumber = data.getLittleEndianUInt32At(MIN_SEQ_INDEX)
            val maxSequenceNumber = data.getLittleEndianUInt32At(MAX_SEQ_INDEX)
            val temperatures = ProbeTemperatures.fromRawData(data.sliceArray(TEMPERATURE_RANGE))

            // use mode and color ID if available
            val modeColorId = if (data.size > 21)
               data.sliceArray(MODE_COLOR_ID_RANGE)[0]
            else
                null

            // use status if available
            val status = if (data.size > 22)
                data.sliceArray(STATUS_RANGE)[0]
            else
                null

            val probeColor = modeColorId?.let { ProbeColor.fromUByte(it) } ?: run { ProbeColor.COLOR1 }
            val probeID = modeColorId?.let { ProbeID.fromUByte(it) } ?: run { ProbeID.ID1 }
            val probeMode = modeColorId?.let { ProbeMode.fromUByte(it) } ?: run { ProbeMode.NORMAL }
            val batteryStatus = status?.let { ProbeBatteryStatus.fromUByte(it) } ?: run { ProbeBatteryStatus.OK }

            return DeviceStatus(
                minSequenceNumber,
                maxSequenceNumber,
                temperatures,
                probeID,
                probeColor,
                probeMode,
                batteryStatus
            )
        }
    }
}
