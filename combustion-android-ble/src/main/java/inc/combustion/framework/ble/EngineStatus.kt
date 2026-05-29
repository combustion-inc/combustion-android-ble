/*
 * Project: Combustion Inc. Android Framework
 * File: EngineStatus.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2026. Combustion Inc.
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
import inc.combustion.framework.utf8StringFromRange

data class EngineStatus(
    val sessionInformation: SessionInformation,
    val samplePeriod: UInt,
    override val minSequenceNumber: UInt,
    override val maxSequenceNumber: UInt,
    val engineBatteryStatus: EngineBatteryStatus,
    val temperatureSetPoint: SensorTemperature,
    val controlTemperature: SensorTemperature?,
    val controlDeviceType: CombustionProductType?,
    val controlSerialNumber: String?,
    val engineStatusFlags: EngineStatusFlags,
    val engineFanStatus: EngineFanStatus,
    val hopCount: HopCount,
    /** Raw potentiometer voltage in millivolts (0–3300 mV). Voltage (V) = millivolts / 1000.0 */
    val knobVoltageMillivolts: UInt,
    /** Knob position in tenths of degrees (0–3599), clockwise from Off (0°). Angle (°) = raw value / 10.0 */
    val knobAngleTenthsDegrees: UInt,
) : SpecializedDeviceStatus {

    override val mode: ProbeMode = ProbeMode.NORMAL

    companion object {
        private val SESSION_ID_RANGE = 0..3 // 4
        private val SAMPLE_PERIOD_RANGE = 4..5 // 2
        private val MIN_SEQ_RANGE = 6..9 // 4
        private val MAX_SEQ_RANGE = 10..13 // 4
        private val BATTERY_STATUS_RANGE = 14..15 // 2
        private val TEMP_SET_POINT_RANGE = 16..17 // 2
        private val CONTROL_TEMP_RANGE = 18..19 // 2
        private val CONTROL_DEVICE_TYPE_RANGE = 20..20 // 1
        private val PROBE_SERIAL_RANGE = 21..24 // 4
        private val NODE_SERIAL_RANGE = 25..34 // 10
        private val ENGINE_STATUS_FLAGS_RANGE = 35..35 // 1
        private val FAN_STATUS_RANGE = 36..47 // 12
        private val HOP_COUNT_RANGE = 48..48 // 1
        private val KNOB_VOLT_RANGE = 49..50 // 2
        private val KNOB_ANGLE_RANGE = 51..52 // 2

        val RAW_SIZE = KNOB_ANGLE_RANGE.last + 1

        private fun <T> UByteArray.takeIfIsSet(block: (UByteArray) -> T): T? =
            if (any { it != 0u.toUByte() }) block(this) else null

        fun fromRawData(data: UByteArray): EngineStatus? {
            if (data.size < RAW_SIZE) return null

            val sessionID: UInt = data.getLittleEndianUInt32At(SESSION_ID_RANGE.first)
            val samplePeriod: UInt = data.getLittleEndianUInt16At(SAMPLE_PERIOD_RANGE.first)
            val sessionInformation =
                SessionInformation(sessionID = sessionID, samplePeriod = samplePeriod)

            val minSequenceNumber = data.getLittleEndianUInt32At(MIN_SEQ_RANGE.first)
            val maxSequenceNumber = data.getLittleEndianUInt32At(MAX_SEQ_RANGE.first)

            val engineBatteryStatus =
                EngineBatteryStatus.fromRaw(data.sliceArray(BATTERY_STATUS_RANGE))
                    ?: return null

            val tempSetPoint =
                SensorTemperature.fromRawDataStart(data.sliceArray(TEMP_SET_POINT_RANGE))

            val controlDeviceType = data.sliceArray(CONTROL_DEVICE_TYPE_RANGE)
                .takeIfIsSet { CombustionProductType.fromUByte(it[0]) }

            val controlTemp = if (controlDeviceType != null) {
                SensorTemperature.fromRawDataStart(data.sliceArray(CONTROL_TEMP_RANGE))
            } else {
                null
            }

            val controlSerialNumber = when (controlDeviceType) {
                null -> null
                CombustionProductType.PROBE -> data.sliceArray(PROBE_SERIAL_RANGE).takeIfIsSet {
                    Integer.toHexString(it.getLittleEndianUInt32At(0).toInt()).uppercase()
                }

                else -> data.sliceArray(NODE_SERIAL_RANGE)
                    .takeIfIsSet { data.utf8StringFromRange(NODE_SERIAL_RANGE) }
            }

            val engineStatusFlags =
                EngineStatusFlags.fromRawByte(data.sliceArray(ENGINE_STATUS_FLAGS_RANGE)[0])
            val engineFanStatus =
                EngineFanStatus.fromRawData(data.sliceArray(FAN_STATUS_RANGE)) ?: return null

            val hopCount: HopCount = HopCount.fromUByte(data.sliceArray(HOP_COUNT_RANGE)[0])
            val knobVoltageMillivolts: UInt = data.getLittleEndianUInt16At(KNOB_VOLT_RANGE.first)
            val knobAngleTenthsDegrees: UInt = data.getLittleEndianUInt16At(KNOB_ANGLE_RANGE.first)

            return EngineStatus(
                sessionInformation = sessionInformation,
                samplePeriod = samplePeriod,
                minSequenceNumber = minSequenceNumber,
                maxSequenceNumber = maxSequenceNumber,
                engineBatteryStatus = engineBatteryStatus,
                temperatureSetPoint = tempSetPoint,
                controlTemperature = controlTemp,
                controlDeviceType = controlDeviceType,
                controlSerialNumber = controlSerialNumber,
                engineStatusFlags = engineStatusFlags,
                engineFanStatus = engineFanStatus,
                hopCount = hopCount,
                knobVoltageMillivolts = knobVoltageMillivolts,
                knobAngleTenthsDegrees = knobAngleTenthsDegrees,
            )
        }
    }
}