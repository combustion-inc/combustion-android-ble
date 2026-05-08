/*
 * Project: Combustion Inc. Android Framework
 * File: Engine.kt
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

package inc.combustion.framework.service

import inc.combustion.framework.service.dfu.DfuProductType

data class Engine(
    override val baseDevice: Device,
    override val productType: CombustionProductType = CombustionProductType.ENGINE,
    override val dfuProductType: DfuProductType = DfuProductType.ENGINE,
    override val sessionInfo: SessionInformation? = null,
    override val statusNotificationsStale: Boolean = false,
    override val uploadState: ProbeUploadState = ProbeUploadState.Unavailable, // TODO : rename class?
    override val minSequence: UInt? = null,
    override val maxSequence: UInt? = null,
    val engineStatusFlags: EngineStatusFlags = EngineStatusFlags(),
    val engineBatteryStatus: EngineBatteryStatus = EngineBatteryStatus(),
    val engineFanStatus: EngineFanStatus? = null,
    val enginePreferences: EnginePreferences = EnginePreferences(),
    val temperatureSetPointCelsius: SensorTemperature = SensorTemperature.NO_DATA,
    val controlDeviceType: CombustionProductType? = null,
    val controlSerialNumber: String? = null,
    val controlTemperature: SensorTemperature? = null,
    override val recordsDownloaded: Int = 0,
    override val logUploadPercent: UInt = 0u,
    override val hopCount: UInt? = null,
    /** Raw potentiometer voltage in millivolts (0–3300 mV). Voltage (V) = millivolts / 1000.0 */
    val knobVoltageMillivolts: UInt? = null,
    /** Knob position in tenths of degrees (0–3599), clockwise from Off (0°). Angle (°) = raw value / 10.0 */
    val knobAngleTenthsDegrees: UInt? = null,
) : SpecializedDevice {

    override val lowBattery: Boolean = engineBatteryStatus.batteryLevel != EngineBatteryLevel.OK

    override val isOverheating: Boolean = false

    val isControlled: Boolean = (controlSerialNumber != null) && (controlDeviceType != null)

    val knobVoltageVolts: Float? = knobVoltageMillivolts?.let { it.toFloat() / 1000.0f }
    val knobAngleDegrees: Float? = knobAngleTenthsDegrees?.let { it.toFloat() / 10.0f }

    companion object {
        fun create(serialNumber: String = "", mac: String = ""): Engine {
            return Engine(
                baseDevice = Device(
                    serialNumber = serialNumber,
                    mac = mac,
                )
            )
        }
    }
}