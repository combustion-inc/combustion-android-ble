/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeStatus.kt
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
 * Data object for the Probe Status packet.
 *
 * @property minSequenceNumber minimum sequence number available for probe.
 * @property maxSequenceNumber maximum/current sequence number available for probe.
 * @property temperatures probe's current temperature values.
 */
internal data class ProbeStatus(
    override val minSequenceNumber: UInt,
    override val maxSequenceNumber: UInt,
    val temperatures: ProbeTemperatures,
    val id: ProbeID,
    val color: ProbeColor,
    override val mode: ProbeMode,
    val batteryStatus: ProbeBatteryStatus,
    val virtualSensors: ProbeVirtualSensors,
    val predictionStatus: PredictionStatus,
    val foodSafeData: FoodSafeData?,
    val foodSafeStatus: FoodSafeStatus?,
    val overheatingSensors: OverheatingSensors,
    val thermometerPrefs: ThermometerPreferences,
    val probeHighLowAlarmStatus: ProbeHighLowAlarmStatus?,
    /**
     * Session ID and sample period appended to the end of the status by newer probe firmware
     * (PRO-134). Null means this message didn't carry them: the probe's firmware predates them,
     * a MeatNet node on older shared firmware relayed the status and dropped the trailing bytes,
     * or the session ID was 0, which firmware reserves to mean "not supplied" (e.g. a newer node
     * relaying a probe on older firmware). So null is "not known from this message" -- the
     * session info can still be read with a session info request.
     */
    val sessionInformation: SessionInformation?,
) : SpecializedDeviceStatus {
    val virtualCoreTemperature: Double
        get() {
            return virtualSensors.virtualCoreSensor.temperatureFrom(temperatures)
        }

    val virtualSurfaceTemperature: Double
        get() {
            return virtualSensors.virtualSurfaceSensor.temperatureFrom(temperatures)
        }

    val virtualAmbientTemperature: Double
        get() {
            return virtualSensors.virtualAmbientSensor.temperatureFrom(temperatures)
        }

    companion object {
        const val MIN_RAW_SIZE = 30
        const val RAW_SIZE_INCLUDING_FOOD_SAFE =
            MIN_RAW_SIZE + FoodSafeData.SIZE_BYTES + FoodSafeStatus.SIZE_BYTES

        // session ID (4 bytes) then sample period (2 bytes)
        private const val SESSION_INFO_SIZE_BYTES = 6
        private const val SESSION_INFO_SAMPLE_PERIOD_OFFSET = 4

        private const val MIN_SEQ_INDEX = 0
        private const val MAX_SEQ_INDEX = 4
        private val TEMPERATURE_RANGE = 8..20
        private val MODE_COLOR_ID_RANGE = 21..21
        private val STATUS_RANGE = 22..22
        private val PREDICTION_STATUS_RANGE = 23 until 23 + PredictionStatus.SIZE_BYTES

        private val FOOD_SAFE_DATA_START_INDEX = PREDICTION_STATUS_RANGE.last + 1
        private val FOOD_SAFE_DATA_RANGE =
            FOOD_SAFE_DATA_START_INDEX until FOOD_SAFE_DATA_START_INDEX + FoodSafeData.SIZE_BYTES

        private val FOOD_SAFE_STATUS_START_INDEX = FOOD_SAFE_DATA_RANGE.last + 1
        private val FOOD_SAFE_STATUS_RANGE =
            FOOD_SAFE_STATUS_START_INDEX until FOOD_SAFE_STATUS_START_INDEX + FoodSafeStatus.SIZE_BYTES

        private val OVERHEAT_BYTE_START_INDEX = FOOD_SAFE_STATUS_RANGE.last + 1
        private val OVERHEAT_BYTE_RANGE =
            OVERHEAT_BYTE_START_INDEX until OVERHEAT_BYTE_START_INDEX + OverheatingSensors.SIZE_BYTES

        fun fromRawData(
            data: UByteArray,
            overheatRange: IntRange = OVERHEAT_BYTE_RANGE
        ): ProbeStatus? {
            if (data.size < MIN_RAW_SIZE) return null

            val minSequenceNumber = data.getLittleEndianUInt32At(MIN_SEQ_INDEX)
            val maxSequenceNumber = data.getLittleEndianUInt32At(MAX_SEQ_INDEX)
            val temperatures = ProbeTemperatures.fromRawData(data.sliceArray(TEMPERATURE_RANGE))
            val modeColorId = data.sliceArray(MODE_COLOR_ID_RANGE)[0]
            val deviceStatus = data.sliceArray(STATUS_RANGE)[0]
            val predictionStatus =
                PredictionStatus.fromRawData(data.sliceArray(PREDICTION_STATUS_RANGE))
            val probeColor = ProbeColor.fromUByte(modeColorId)
            val probeID = ProbeID.fromUByte(modeColorId)
            val probeMode = ProbeMode.fromUByte(modeColorId)
            val batteryStatus = ProbeBatteryStatus.fromUByte(deviceStatus)
            val virtualSensors = ProbeVirtualSensors.fromDeviceStatus(deviceStatus)

            val dataIncludesFoodSafe = data.size > FOOD_SAFE_DATA_RANGE.last
            val foodSafeData = if (dataIncludesFoodSafe) {
                FoodSafeData.fromRawData(data.sliceArray(FOOD_SAFE_DATA_RANGE))
            } else {
                null
            }
            val foodSafeStatus = if (dataIncludesFoodSafe) {
                FoodSafeStatus.fromRawData(data.sliceArray(FOOD_SAFE_STATUS_RANGE))
            } else {
                null
            }

            // Decode Over heating flags
            val dataIncludesOverheat = data.size > overheatRange.last
            val overheatingSensors = if (dataIncludesOverheat) {

                // Sanity check for the overheating flags. If none of the temperatures are above the
                // previous thresholds, then ignore the flag values there are no overheating sensors.
                // There is a bug in the repeater firmware versions <= 2.2.0 that can cause these
                // flags to be incorrectly set. This should help reduce invalid overheating errors
                // to the user.
                if (!OverheatingSensors.fromTemperatures(temperatures).isAnySensorOverheating()) {
                    OverheatingSensors.fromRawByte(0u)
                } else {
                    OverheatingSensors.fromRawByte(data.sliceArray(overheatRange)[0])
                }
            } else {
                OverheatingSensors.fromTemperatures(temperatures)
            }

            val probeRefsByteStartIndex = overheatRange.last + 1
            val probeRefsRange =
                probeRefsByteStartIndex until probeRefsByteStartIndex + ThermometerPreferences.PROBE_PREFS_SIZE_BYTES
            val probePrefs = if (data.size > probeRefsRange.last) {
                val probePrefsRaw = data.sliceArray(probeRefsRange)[0]
                ThermometerPreferences.fromRawByte(probePrefsRaw)
            } else {
                ThermometerPreferences.DEFAULT
            }

            val highLowAlarmsByteStartIndex = probeRefsRange.last + 1
            val highLowAlarmsRange =
                highLowAlarmsByteStartIndex until highLowAlarmsByteStartIndex + ProbeHighLowAlarmStatus.PROBE_HIGH_LOW_ALARMS_SIZE_BYTES
            val highLowAlarms = if (data.size > highLowAlarmsRange.last) {
                ProbeHighLowAlarmStatus.fromRawData(data.sliceArray(highLowAlarmsRange))
            } else {
                null
            }

            // Session info is appended after the alarms, so its offset follows overheatRange the
            // same way the fields above do, which also covers the relayed node message layout.
            val sessionInfoByteStartIndex = highLowAlarmsRange.last + 1
            val sessionInfoRange =
                sessionInfoByteStartIndex until sessionInfoByteStartIndex + SESSION_INFO_SIZE_BYTES
            val sessionInformation = if (data.size > sessionInfoRange.last) {
                val sessionID = data.getLittleEndianUInt32At(sessionInfoRange.first)
                val samplePeriod = data.getLittleEndianUInt16At(
                    sessionInfoRange.first + SESSION_INFO_SAMPLE_PERIOD_OFFSET
                )
                // A session ID of 0 is reserved to mean "not supplied". A sample period of 0 is
                // never valid either, and would break timestamp math downstream.
                if (sessionID != 0u && samplePeriod != 0u) {
                    SessionInformation(sessionID = sessionID, samplePeriod = samplePeriod)
                } else {
                    null
                }
            } else {
                null
            }

            return ProbeStatus(
                minSequenceNumber = minSequenceNumber,
                maxSequenceNumber = maxSequenceNumber,
                temperatures = temperatures,
                id = probeID,
                color = probeColor,
                mode = probeMode,
                batteryStatus = batteryStatus,
                virtualSensors = virtualSensors,
                predictionStatus = predictionStatus,
                foodSafeData = foodSafeData,
                foodSafeStatus = foodSafeStatus,
                overheatingSensors = overheatingSensors,
                thermometerPrefs = probePrefs,
                probeHighLowAlarmStatus = highLowAlarms,
                sessionInformation = sessionInformation,
            )
        }
    }
}
