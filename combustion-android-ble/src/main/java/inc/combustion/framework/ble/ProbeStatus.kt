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
    val minSequenceNumber: UInt,
    val maxSequenceNumber: UInt,
    val temperatures: ProbeTemperatures,
    val id: ProbeID,
    val color: ProbeColor,
    val mode: ProbeMode,
    val batteryStatus: ProbeBatteryStatus,
    val virtualSensors: ProbeVirtualSensors,
    val predictionStatus: PredictionStatus,
    val foodSafeData: FoodSafeData?,
    val foodSafeStatus: FoodSafeStatus?,
) {
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
        const val RAW_SIZE_INCLUDING_FOOD_SAFE = 44

        private const val MIN_SEQ_INDEX = 0
        private const val MAX_SEQ_INDEX = 4
        private val TEMPERATURE_RANGE = 8..20
        private val MODE_COLOR_ID_RANGE = 21..21
        private val STATUS_RANGE = 22..22
        private val PREDICTION_STATUS_RANGE = 23 until 23 + PredictionStatus.SIZE_BYTES
        private val FOOD_SAFE_DATA_RANGE = 30 until 30 + FoodSafeData.SIZE_BYTES
        private val FOOD_SAFE_STATUS_RANGE = 39 until 39 + FoodSafeStatus.SIZE_BYTES

        fun fromRawData(data: UByteArray): ProbeStatus? {
            if (data.size < MIN_RAW_SIZE) return null

            val minSequenceNumber = data.getLittleEndianUInt32At(MIN_SEQ_INDEX)
            val maxSequenceNumber = data.getLittleEndianUInt32At(MAX_SEQ_INDEX)
            val temperatures = ProbeTemperatures.fromRawData(data.sliceArray(TEMPERATURE_RANGE))
            val modeColorId = data.sliceArray(MODE_COLOR_ID_RANGE)[0]
            val deviceStatus = data.sliceArray(STATUS_RANGE)[0]
            val predictionStatus = PredictionStatus.fromRawData(data.sliceArray(PREDICTION_STATUS_RANGE))
            val probeColor = ProbeColor.fromUByte(modeColorId)
            val probeID = ProbeID.fromUByte(modeColorId)
            val probeMode = ProbeMode.fromUByte(modeColorId)
            val batteryStatus = ProbeBatteryStatus.fromUByte(deviceStatus)
            val virtualSensors = ProbeVirtualSensors.fromDeviceStatus(deviceStatus)

            val dataIncludesFoodSafe = data.size > MIN_RAW_SIZE
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
            )
        }
    }
}
