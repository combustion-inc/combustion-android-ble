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

/**
 * Data class for the current state of a probe.
 *
 * @property serialNumber Serial Number
 * @property mac Bluetooth MAC Address
 * @property fwVersion Firmware Version
 * @property hwRevision Hardware Revision
 * @property temperaturesCelsius Current temperature values
 * @property instantReadCelsius Current instant read value
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
    val baseDevice: Device,
    val sessionInfo: SessionInformation? = null,
    val temperaturesCelsius: ProbeTemperatures? = null,
    val instantReadCelsius: Double? = null,
    val coreTemperatureCelsius: Double? = null,
    val surfaceTemperatureCelsius: Double? = null,
    val ambientTemperatureCelsius: Double? = null,
    val minSequenceNumber: UInt = 0u,
    val maxSequenceNumber: UInt = 0u,
    val uploadState: ProbeUploadState = ProbeUploadState.Unavailable,
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
    val hopCount: UInt? = null,
    val statusNotificationsStale: Boolean = false,
    val overheatingSensors: List<Int> = listOf(),
) {
    val serialNumber = baseDevice.serialNumber
    val mac = baseDevice.mac
    val fwVersion = baseDevice.fwVersion
    val hwRevision = baseDevice.hwRevision
    val modelInformation = baseDevice.modelInformation
    val rssi = baseDevice.rssi
    val connectionState = baseDevice.connectionState

    val temperaturesStale: Boolean get() { return connectionState == DeviceConnectionState.OUT_OF_RANGE }
    val instantReadStale: Boolean get() { return instantReadCelsius == null }

    val predictionPercent: Double?
        get() {
            heatStartTemperatureCelsius?.let { start ->
                setPointTemperatureCelsius?.let { end ->
                    estimatedCoreCelsius?.let { core ->
                        if(core > end) {
                            return 100.0
                        }

                        if(core < start) {
                            return 0.0
                        }

                        return (((core - start) / (end - start)) * 100.0)
                    }
                }
            }

            return null
        }

    val isOverheating: Boolean
        get() = overheatingSensors.isNotEmpty()

    companion object {
        fun create(serialNumber: String = "", mac: String = "") : Probe {
            return Probe(baseDevice = Device(
                serialNumber = serialNumber,
                mac = mac
            ))
        }
    }
}

