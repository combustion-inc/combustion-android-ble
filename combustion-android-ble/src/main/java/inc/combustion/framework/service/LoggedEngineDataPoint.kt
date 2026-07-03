/*
 * Project: Combustion Inc. Android Framework
 * File: LoggedEngineDataPoint.kt
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

import inc.combustion.framework.ble.EngineStatus
import inc.combustion.framework.service.LoggedDataPoint.Companion.getTimestamp
import java.util.Date

data class LoggedEngineDataPoint(
    override val sessionId: UInt,
    override val sequenceNumber: UInt,
    override val timestamp: Date,
    val temperatureSetPoint: SensorTemperature,
    val controlTemperature: SensorTemperature?,
    val controlDeviceType: CombustionProductType?,
    val controlSerialNumber: String?,
    // EngineBatteryStatus fields
    val batteryLevel: EngineBatteryLevel,
    val batteryState: EngineBatteryState,
    val batteryVoltageTenthsVolts: UByte,
    // EngineStatusFlags fields
    val appMode: Boolean,
    val controlDeviceConnected: Boolean,
    val lidOpen: Boolean,
    val fixedSpeed: Boolean,
    // EngineFanStatus fields
    val fanState: EngineFanState,
    val dutyCycle: UByte,
    val commandedSpeed: UByte,
    val measuredSpeed: UByte,
    val fanOffTimeMs: UInt,
    val fanOnTimeMs: UInt,
    // EngineControllerStatus fields
    val controllerState: EngineControllerState,
    val responseCoefficient: Float,
    val cyclesCompleted: UByte,
    val reachedSetpoint: Boolean,
    val maintenanceMode: Boolean,
    val smoothedTemperatureCelsius: Float,
    val timeToPeakSeconds: UByte,
    val driftRateCelsiusPerSecond: Float,
    // Knob fields
    val knobVoltageMillivolts: UInt,
    val knobAngleTenthsDegrees: UInt,
    val chargingFault: EngineChargingFault,
) : LoggedDataPoint {

    override fun copyWith(
        sessionId: UInt,
        sequenceNumber: UInt,
        timestamp: Date,
    ): LoggedEngineDataPoint = this.copy(
        sessionId = sessionId,
        sequenceNumber = sequenceNumber,
        timestamp = timestamp,
    )

    internal companion object {
        fun fromDeviceStatus(
            status: EngineStatus,
            sessionStart: Date?,
        ): LoggedEngineDataPoint {
            val timestamp =
                getTimestamp(sessionStart, status.maxSequenceNumber, status.samplePeriod)
            return LoggedEngineDataPoint(
                sessionId = status.sessionInformation.sessionID,
                sequenceNumber = status.maxSequenceNumber,
                timestamp = timestamp,
                temperatureSetPoint = status.temperatureSetPoint,
                controlTemperature = status.controlTemperature,
                controlDeviceType = status.controlDeviceType,
                controlSerialNumber = status.controlSerialNumber,
                batteryLevel = status.engineBatteryStatus.batteryLevel,
                batteryState = status.engineBatteryStatus.batteryState,
                batteryVoltageTenthsVolts = status.engineBatteryStatus.batteryVoltageTenthsVolts,
                appMode = status.engineStatusFlags.appMode,
                controlDeviceConnected = status.engineStatusFlags.controlDeviceConnected,
                lidOpen = status.engineStatusFlags.lidOpen,
                fixedSpeed = status.engineStatusFlags.fixedSpeed,
                fanState = status.engineFanStatus.fanState,
                dutyCycle = status.engineFanStatus.dutyCycle,
                commandedSpeed = status.engineFanStatus.commandedSpeed,
                measuredSpeed = status.engineFanStatus.measuredSpeed,
                fanOffTimeMs = status.engineFanStatus.fanOffTimeMs,
                fanOnTimeMs = status.engineFanStatus.fanOnTimeMs,
                controllerState = status.engineControllerStatus.state,
                responseCoefficient = status.engineControllerStatus.responseCoefficient,
                cyclesCompleted = status.engineControllerStatus.cyclesCompleted,
                reachedSetpoint = status.engineControllerStatus.flags.reachedSetpoint,
                maintenanceMode = status.engineControllerStatus.flags.maintenanceMode,
                smoothedTemperatureCelsius = status.engineControllerStatus.smoothedTemperatureCelsius,
                timeToPeakSeconds = status.engineControllerStatus.timeToPeakSeconds,
                driftRateCelsiusPerSecond = status.engineControllerStatus.driftRateCelsiusPerSecond,
                knobVoltageMillivolts = status.knobVoltageMillivolts,
                knobAngleTenthsDegrees = status.knobAngleTenthsDegrees,
                chargingFault = status.chargingFault,
            )
        }
    }
}
