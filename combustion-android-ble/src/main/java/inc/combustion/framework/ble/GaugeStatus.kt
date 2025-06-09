/*
 * Project: Combustion Inc. Android Framework
 * File: GaugeStatus.kt
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

package inc.combustion.framework.ble

import inc.combustion.framework.service.GaugeStatusFlags
import inc.combustion.framework.service.HighLowAlarmStatus
import inc.combustion.framework.service.HopCount
import inc.combustion.framework.service.ProbeMode
import inc.combustion.framework.service.SensorTemperature
import inc.combustion.framework.service.SessionInformation
import inc.combustion.framework.toPercentage

data class GaugeStatus(
    val sessionInformation: SessionInformation,
    val samplePeriod: UInt,
    val temperature: SensorTemperature,
    val gaugeStatusFlags: GaugeStatusFlags,
    override val minSequenceNumber: UInt,
    override val maxSequenceNumber: UInt,
    val batteryPercentage: Int,
    val highLowAlarmStatus: HighLowAlarmStatus,
    val isNewRecord: Boolean,
    val hopCount: HopCount,
) : SpecializedDeviceStatus {

    override val mode: ProbeMode = ProbeMode.NORMAL

    companion object {
        private val SESSION_ID_RANGE = 0..3
        private val SAMPLE_PERIOD_RANGE = 4..5
        private val RAW_TEMP_RANGE = 6..7
        private val STATUS_FLAGS_RANGE = 8..8
        private val MIN_SEQ_RANGE = 9..12
        private val MAX_SEQ_RANGE = 13..16
        private val BATTERY_PERCENTAGE_RANGE = 17..17
        private val HIGH_LOW_ALARM_RANGE = 18..21
        private val NEW_RECORD_FLAG_RANGE = 22..22
        private val HOP_COUNT_RANGE = 23..23

        val RAW_SIZE = NEW_RECORD_FLAG_RANGE.last + 1

        fun fromRawData(data: UByteArray): GaugeStatus? {
            if (data.size < RAW_SIZE) return null

            val sessionID: UInt = data.getLittleEndianUInt32At(SESSION_ID_RANGE.first)
            val samplePeriod: UInt = data.getLittleEndianUInt16At(SAMPLE_PERIOD_RANGE.first)
            val sessionInformation =
                SessionInformation(sessionID = sessionID, samplePeriod = samplePeriod)

            val gaugeStatusFlags =
                GaugeStatusFlags.fromRawByte(data.sliceArray(STATUS_FLAGS_RANGE)[0])

            val temperature = if (gaugeStatusFlags.sensorPresent) {
                SensorTemperature.fromRawDataStart(data.sliceArray(RAW_TEMP_RANGE))
            } else {
                SensorTemperature.NO_DATA
            }

            val minSequenceNumber = data.getLittleEndianUInt32At(MIN_SEQ_RANGE.first)
            val maxSequenceNumber = data.getLittleEndianUInt32At(MAX_SEQ_RANGE.first)

            val batteryPercentage: Int = data.sliceArray(BATTERY_PERCENTAGE_RANGE)[0].toPercentage()

            val highLowAlarmStatus: HighLowAlarmStatus = HighLowAlarmStatus.fromRawData(
                data.sliceArray(HIGH_LOW_ALARM_RANGE)
            )

            val isNewRecord: Boolean =
                data.getLittleEndianUShortAt(NEW_RECORD_FLAG_RANGE.first).toInt() == 1

            val hopCount: HopCount = HopCount.fromUByte(data.sliceArray(HOP_COUNT_RANGE)[0])

            return GaugeStatus(
                sessionInformation = sessionInformation,
                samplePeriod = samplePeriod,
                temperature = temperature,
                gaugeStatusFlags = gaugeStatusFlags,
                minSequenceNumber = minSequenceNumber,
                maxSequenceNumber = maxSequenceNumber,
                batteryPercentage = batteryPercentage,
                highLowAlarmStatus = highLowAlarmStatus,
                isNewRecord = isNewRecord,
                hopCount = hopCount,
            )
        }
    }
}