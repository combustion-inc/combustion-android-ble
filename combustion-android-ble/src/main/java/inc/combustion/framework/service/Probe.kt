/*
 * Project: Combustion Inc. Android Framework
 * File: Probe.kt
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

import inc.combustion.framework.service.dfu.DfuProductType

/**
 * Data class for the current state of a probe.
 *
 * [instantReadCelsius] and [instantReadFahrenheit] have the same deadband filtering and rounding
 * that are applied to the instant read value on the display--use these when displaying to users as
 * it minimizes 'flicker', where the reading jumps between two adjacent values quickly.
 *
 * [instantReadRawCelsius] contains the actual instant read value, which can be prone to the flicker
 * mentioned above.
 *
 * If [instantReadCelsius], [instantReadFahrenheit], and [instantReadRawCelsius] will be null if the
 * thermometer is not in instant read mode.
 *
 * @property serialNumber Serial Number
 * @property mac Bluetooth MAC Address
 * @property fwVersion Firmware Version
 * @property hwRevision Hardware Revision
 * @property temperaturesCelsius Current temperature values
 * @property rssi Received signal strength
 * @property minSequence Minimum log sequence number
 * @property maxSequence Current sequence number
 * @property connectionState Connection state
 * @property uploadState Upload State
 * @property id Probe ID
 * @property color Probe Color
 * @property mode Probe Mode, see related.  For instance, normal mode or instant read.
 * @property batteryStatus Probe battery status
 * @property virtualSensors Virtual sensor positions
 * @property predictionState Current state of cook
 * @property predictionMode Current prediction mode
 * @property predictionType Current type of prediction
 * @property setPointTemperatureCelsius Current setpoint temperature of prediction.
 * @property heatStartTemperatureCelsius Temperature at heat start of prediction, if known.
 * @property predictionSeconds The prediction in number of seconds from now, filtered and intended for display to the user.
 * @property rawPredictionSeconds The raw prediction from the probe in number of seconds from now.
 * @property estimatedCoreCelsisus Current estimate of the core temperature for prediction.
 * @property overheatingSensors List of indices into [temperaturesCelsius], where each entry is an
 *                              overheating sensor.
 *
 * @see DeviceConnectionState
 * @see ProbeUploadState
 * @see ProbeTemperatures
 * @see ProbeID
 * @see ProbeColor
 * @see ProbeMode
 */
data class Probe(
    override val baseDevice: Device,
    override val sessionInfo: SessionInformation? = null,
    override val productType: CombustionProductType = CombustionProductType.PROBE,
    override val dfuProductType: DfuProductType = DfuProductType.PROBE,
    val temperaturesCelsius: ProbeTemperatures? = null,
    val instantReadCelsius: Double? = null,
    val instantReadFahrenheit: Double? = null,
    val instantReadRawCelsius: Double? = null,
    val coreTemperatureCelsius: Double? = null,
    val surfaceTemperatureCelsius: Double? = null,
    val ambientTemperatureCelsius: Double? = null,
    override val minSequence: UInt? = null,
    override val maxSequence: UInt? = null,
    @Deprecated(
        message = "This field will be removed in a future release",
        level = DeprecationLevel.WARNING,
    )
    val minSequenceNumber: UInt = minSequence ?: 0u,
    @Deprecated(
        message = "This field will be removed in a future release",
        level = DeprecationLevel.WARNING
    )
    val maxSequenceNumber: UInt = maxSequence ?: 0u,
    override val uploadState: ProbeUploadState = ProbeUploadState.Unavailable,
    val id: ProbeID = ProbeID.ID1,
    val color: ProbeColor = ProbeColor.COLOR1,
    val batteryStatus: ProbeBatteryStatus = ProbeBatteryStatus.OK,
    val virtualSensors: ProbeVirtualSensors = ProbeVirtualSensors(),
    val predictionState: ProbePredictionState? = null,
    val predictionMode: ProbePredictionMode? = null,
    val predictionType: ProbePredictionType? = null,
    val setPointTemperatureCelsius: Double? = null,
    val heatStartTemperatureCelsius: Double? = null,
    val predictionSeconds: UInt? = null,
    val rawPredictionSeconds: UInt? = null,
    val estimatedCoreCelsius: Double? = null,
    override val hopCount: UInt? = null,
    override val statusNotificationsStale: Boolean = false,
    val predictionStale: Boolean = false,
    val overheatingSensors: List<Int> = listOf(),
    val recordsDownloaded: Int = 0,
    val preferredLink: String = "",
    val logUploadPercent: UInt = 0u,
    val foodSafeData: FoodSafeData? = null,
    val foodSafeStatus: FoodSafeStatus? = null,
    val thermometerPrefs: ThermometerPreferences? = null,
    val highLowAlarmStatus: ProbeHighLowAlarmStatus? = null,
) : SpecializedDevice {
    override val lowBattery: Boolean
        get() = batteryStatus.isLowBattery

    val instantReadStale: Boolean
        get() {
            return instantReadCelsius == null
        }

    val predictionPercent: Double?
        get() {
            heatStartTemperatureCelsius?.let { start ->
                setPointTemperatureCelsius?.let { end ->
                    estimatedCoreCelsius?.let { core ->
                        if (core > end) {
                            return 100.0
                        }

                        if (core < start) {
                            return 0.0
                        }

                        return (((core - start) / (end - start)) * 100.0)
                    }
                }
            }

            return null
        }

    override val isOverheating: Boolean
        get() = overheatingSensors.isNotEmpty()

    val isPredicting: Boolean
        get() = (
                predictionMode?.let {
                    it == ProbePredictionMode.TIME_TO_REMOVAL || it == ProbePredictionMode.REMOVAL_AND_RESTING
                } ?: false
                )

    companion object {
        const val PREDICTION_IDLE_TIMEOUT_MS = 60000L

        fun create(serialNumber: String = "", mac: String = ""): Probe {
            return Probe(
                baseDevice = Device(
                    serialNumber = serialNumber,
                    mac = mac,
                )
            )
        }
    }
}

