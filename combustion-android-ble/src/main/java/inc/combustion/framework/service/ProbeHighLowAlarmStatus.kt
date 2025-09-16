/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeHighLowAlarmStatus.kt
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

import android.util.Log

data class ProbeHighLowAlarmStatus(
    val t1: HighLowAlarmStatus = HighLowAlarmStatus.DEFAULT,
    val t2: HighLowAlarmStatus = HighLowAlarmStatus.DEFAULT,
    val t3: HighLowAlarmStatus = HighLowAlarmStatus.DEFAULT,
    val t4: HighLowAlarmStatus = HighLowAlarmStatus.DEFAULT,
    val t5: HighLowAlarmStatus = HighLowAlarmStatus.DEFAULT,
    val t6: HighLowAlarmStatus = HighLowAlarmStatus.DEFAULT,
    val t7: HighLowAlarmStatus = HighLowAlarmStatus.DEFAULT,
    val t8: HighLowAlarmStatus = HighLowAlarmStatus.DEFAULT,
    val virtualCore: HighLowAlarmStatus = HighLowAlarmStatus.DEFAULT,
    val virtualSurface: HighLowAlarmStatus = HighLowAlarmStatus.DEFAULT,
    val virtualAmbient: HighLowAlarmStatus = HighLowAlarmStatus.DEFAULT,
) {
    companion object {
        internal const val PROBE_HIGH_LOW_ALARMS_SIZE_BYTES = 44
        internal const val SENSOR_COUNT = 11
        val DEFAULT = ProbeHighLowAlarmStatus()

        internal const val HIGH_ALARMS_STATUS_INDEX = 0
        internal const val LOW_ALARMS_STATUS_INDEX = SENSOR_COUNT * 2

        internal fun fromRawData(data: UByteArray): ProbeHighLowAlarmStatus {
            if (data.size < PROBE_HIGH_LOW_ALARMS_SIZE_BYTES) {
                throw IllegalArgumentException("Invalid buffer")
            }

            var status = DEFAULT
            val getSensorStatus: (highBytes: UByteArray, lowBytes: UByteArray) -> HighLowAlarmStatus =
                { highBytes, lowBytes ->
                    val highStatus = HighLowAlarmStatus.AlarmStatus.fromRawData(highBytes)
                    val lowStatus = HighLowAlarmStatus.AlarmStatus.fromRawData(lowBytes)
                    HighLowAlarmStatus(highStatus = highStatus, lowStatus = lowStatus)
                }
            for (sensorIdx in 0 until SENSOR_COUNT) {
                val idx = sensorIdx * 2
                val highBytes =
                    data.sliceArray(HIGH_ALARMS_STATUS_INDEX + idx until HIGH_ALARMS_STATUS_INDEX + idx + 2)
                val lowBytes =
                    data.sliceArray(LOW_ALARMS_STATUS_INDEX + idx until LOW_ALARMS_STATUS_INDEX + idx + 2)
                val sensorStatus = getSensorStatus(highBytes, lowBytes)
                Log.v(
                    "D3V",
                    "ProbeHighLowAlarmStatus.fromRawData, sensorIdx = $sensorIdx, idx = $idx, highBytes = $highBytes, lowBytes = $lowBytes"
                )
                Log.v(
                    "D3V",
                    "ProbeHighLowAlarmStatus.fromRawData, sensorIdx = $sensorIdx, idx = $idx, sensorStatus = $sensorStatus"
                )

                status = when (sensorIdx) {
                    0 -> status.copy(t1 = sensorStatus)
                    1 -> status.copy(t2 = sensorStatus)
                    2 -> status.copy(t3 = sensorStatus)
                    3 -> status.copy(t4 = sensorStatus)
                    4 -> status.copy(t5 = sensorStatus)
                    5 -> status.copy(t6 = sensorStatus)
                    6 -> status.copy(t7 = sensorStatus)
                    7 -> status.copy(t8 = sensorStatus)
                    8 -> status.copy(virtualCore = sensorStatus)
                    9 -> status.copy(virtualSurface = sensorStatus)
                    10 -> status.copy(virtualAmbient = sensorStatus)
                    else -> throw IndexOutOfBoundsException("probeHighLowAlarmStatus sensor index $idx is out of bounds")
                }
            }
            return status
        }
    }

    internal fun toRawData(): UByteArray {
        val data = UByteArray(PROBE_HIGH_LOW_ALARMS_SIZE_BYTES)
        for (sensorIdx in 0 until SENSOR_COUNT) {
            val sensor = when (sensorIdx) {
                0 -> t1
                1 -> t2
                2 -> t3
                3 -> t4
                4 -> t5
                5 -> t6
                6 -> t7
                7 -> t8
                8 -> virtualCore
                9 -> virtualSurface
                10 -> virtualAmbient
                else -> throw IndexOutOfBoundsException("probeHighLowAlarmStatus sensor index $sensorIdx is out of bounds")
            }
            val idx = sensorIdx * 2
            val highBytes = sensor.highStatus.toBytes()
            val lowBytes = sensor.lowStatus.toBytes()
            Log.v(
                "D3V",
                "toRawData: sensorIdx = $sensorIdx, idx = $idx, highBytes, $highBytes",
            )
            Log.v(
                "D3V",
                "toRawData: sensorIdx = $sensorIdx, idx = $idx, lowBytes, $lowBytes",
            )
            data[HIGH_ALARMS_STATUS_INDEX + idx + 0] = highBytes[0]
            data[HIGH_ALARMS_STATUS_INDEX + idx + 1] = highBytes[1]
            data[LOW_ALARMS_STATUS_INDEX + idx + 0] = lowBytes[0]
            data[LOW_ALARMS_STATUS_INDEX + idx + 1] = lowBytes[1]
        }
        return data
    }
}