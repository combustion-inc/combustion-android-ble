/*
 * Project: Combustion Inc. Android Framework
 * File: SetProbeHighLowAlarmRequest.kt
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

package inc.combustion.framework.ble.uart

import android.util.Log
import inc.combustion.framework.service.ProbeHighLowAlarmStatus
import inc.combustion.framework.service.ProbeHighLowAlarmStatus.Companion.HIGH_ALARMS_STATUS_INDEX
import inc.combustion.framework.service.ProbeHighLowAlarmStatus.Companion.LOW_ALARMS_STATUS_INDEX
import inc.combustion.framework.service.ProbeHighLowAlarmStatus.Companion.SENSOR_COUNT

internal class SetProbeHighLowAlarmRequest(
    probeHighLowAlarmStatus: ProbeHighLowAlarmStatus,
) : Request(
    ProbeHighLowAlarmStatus.PROBE_HIGH_LOW_ALARMS_SIZE_BYTES.toUByte(),
    MessageType.SET_PROBE_HIGH_LOW_ALARM,
) {

    init {
        val headerSize = HEADER_SIZE.toInt()
        for (sensorIdx in 0 until SENSOR_COUNT) {
            val sensor = when (sensorIdx) {
                0 -> probeHighLowAlarmStatus.t1
                1 -> probeHighLowAlarmStatus.t2
                2 -> probeHighLowAlarmStatus.t3
                3 -> probeHighLowAlarmStatus.t4
                4 -> probeHighLowAlarmStatus.t5
                5 -> probeHighLowAlarmStatus.t6
                6 -> probeHighLowAlarmStatus.t7
                7 -> probeHighLowAlarmStatus.t8
                8 -> probeHighLowAlarmStatus.virtualCore
                9 -> probeHighLowAlarmStatus.virtualSurface
                10 -> probeHighLowAlarmStatus.virtualAmbient
                else -> throw IndexOutOfBoundsException("probeHighLowAlarmStatus sensor index $sensorIdx is out of bounds")
            }
            val idx = sensorIdx * 2
            val highBytes = sensor.highStatus.toBytes()
            val lowBytes = sensor.lowStatus.toBytes()
            Log.v(
                "D3V",
                "SetProbeHighLowAlarmRequest: sensorIdx = $sensorIdx, idx = $idx, highBytes, $highBytes",
            )
            Log.v(
                "D3V",
                "SetProbeHighLowAlarmRequest: sensorIdx = $sensorIdx, idx = $idx, lowBytes, $lowBytes",
            )
            data[headerSize + HIGH_ALARMS_STATUS_INDEX + idx + 0] = highBytes[0]
            data[headerSize + HIGH_ALARMS_STATUS_INDEX + idx + 1] = highBytes[1]
            data[headerSize + LOW_ALARMS_STATUS_INDEX + idx + 0] = lowBytes[0]
            data[headerSize + LOW_ALARMS_STATUS_INDEX + idx + 1] = lowBytes[1]
        }
        Log.v("D3V", "SetProbeHighLowAlarmRequest: data, $data")
        setCRC()
    }
}