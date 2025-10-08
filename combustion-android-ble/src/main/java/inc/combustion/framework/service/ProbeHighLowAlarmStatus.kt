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
    val orderedSensorList: List<HighLowAlarmStatus> =
        listOf(t1, t2, t3, t4, t5, t6, t7, t8, virtualCore, virtualSurface, virtualAmbient)

    fun anySensor(predicate: HighLowAlarmStatus.() -> Boolean): Boolean {
        return orderedSensorList.any { it.predicate() }
    }

    val isAlarming: Boolean
        get() = anySensor { isAlarming }

    val isSet: Boolean
        get() = anySensor { isSet }

    val isTripped: Boolean
        get() = anySensor { isTripped }

    fun copyWithAlarmingDisabled(): ProbeHighLowAlarmStatus {
        var status = this
        for (sensorIdx in 0 until SENSOR_COUNT) {
            val sensor = orderedSensorList[sensorIdx]
            if (sensor.isAlarming) {
                val updatedSensor = sensor.copy(
                    lowStatus = sensor.lowStatus.copy(alarming = false),
                    highStatus = sensor.highStatus.copy(alarming = false)
                )
                status = when (sensorIdx) {
                    0 -> status.copy(t1 = updatedSensor)
                    1 -> status.copy(t2 = updatedSensor)
                    2 -> status.copy(t3 = updatedSensor)
                    3 -> status.copy(t4 = updatedSensor)
                    4 -> status.copy(t5 = updatedSensor)
                    5 -> status.copy(t6 = updatedSensor)
                    6 -> status.copy(t7 = updatedSensor)
                    7 -> status.copy(t8 = updatedSensor)
                    8 -> status.copy(virtualCore = updatedSensor)
                    9 -> status.copy(virtualSurface = updatedSensor)
                    10 -> status.copy(virtualAmbient = updatedSensor)
                    else -> throw IndexOutOfBoundsException("probeHighLowAlarmStatus sensor index $sensorIdx is out of bounds")
                }
            }
        }
        return status
    }

    internal fun toRawData(): UByteArray {
        val data = UByteArray(PROBE_HIGH_LOW_ALARMS_SIZE_BYTES)
        for (sensorIdx in 0 until SENSOR_COUNT) {
            val sensor = orderedSensorList[sensorIdx]
            val idx = sensorIdx * 2
            val highBytes = sensor.highStatus.toBytes()
            val lowBytes = sensor.lowStatus.toBytes()
            data[HIGH_ALARMS_STATUS_INDEX + idx + 0] = highBytes[0]
            data[HIGH_ALARMS_STATUS_INDEX + idx + 1] = highBytes[1]
            data[LOW_ALARMS_STATUS_INDEX + idx + 0] = lowBytes[0]
            data[LOW_ALARMS_STATUS_INDEX + idx + 1] = lowBytes[1]
        }
        return data
    }

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
}