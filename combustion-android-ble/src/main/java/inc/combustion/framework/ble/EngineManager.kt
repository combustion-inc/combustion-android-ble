/*
 * Project: Combustion Inc. Android Framework
 * File: EngineManager.kt
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

import android.util.Log
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.device.DeviceID
import inc.combustion.framework.ble.device.DeviceInformationBleDevice
import inc.combustion.framework.ble.device.EngineBleDevice
import inc.combustion.framework.ble.device.SimulatedEngineBleDevice
import inc.combustion.framework.ble.scanning.DeviceAdvertisingData
import inc.combustion.framework.ble.scanning.EngineAdvertisingData
import inc.combustion.framework.ble.uart.LogResponse
import inc.combustion.framework.service.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

internal class EngineManager(
    mac: String,
    serialNumber: String,
    initialEngineAdvertisingData: EngineAdvertisingData?,
    scope: CoroutineScope,
    settings: DeviceManager.Settings,
    dfuDisconnectedNodeCallback: (DeviceID) -> Unit,
) : NodeHybridManager<EngineBleDevice, EngineAdvertisingData, Engine, SimulatedEngineBleDevice>(
    scope = scope,
    settings = settings,
    dfuDisconnectedNodeCallback = dfuDisconnectedNodeCallback,
) {

    override val arbitrator = EngineDataLinkArbitrator()

    override val _deviceFlow = Engine.create(serialNumber = serialNumber, mac = mac).let { base ->
        MutableStateFlow(initialEngineAdvertisingData?.let { updateDataFromAdvertisement(it, base) }
            ?: base)
    }

    override val deviceFlow: StateFlow<Engine> = _deviceFlow.asStateFlow()

    override val device: Engine
        get() = _deviceFlow.value

    private val _logResponseFlow = MutableSharedFlow<LogResponse>(
        replay = 0, extraBufferCapacity = 50, BufferOverflow.SUSPEND
    )
    override val logResponseFlow: SharedFlow<LogResponse> = _logResponseFlow.asSharedFlow()

    private val _normalModeStatusFlow = MutableSharedFlow<SpecializedDeviceStatus>(
        replay = 0, extraBufferCapacity = 10, BufferOverflow.DROP_OLDEST
    )
    override val normalModeStatusFlow: SharedFlow<SpecializedDeviceStatus> =
        _normalModeStatusFlow.asSharedFlow()

    init {
        monitorStatusNotifications()
    }

    // Abstract method implementations

    override fun Engine.withBaseDevice(baseDevice: Device): Engine = copy(baseDevice = baseDevice)
    override fun Engine.withStatusNotificationsStale(stale: Boolean): Engine =
        copy(statusNotificationsStale = stale)

    override fun Engine.withUploadState(state: ProbeUploadState): Engine = copy(uploadState = state)
    override fun Engine.withRecordsDownloaded(count: Int): Engine = copy(recordsDownloaded = count)
    override fun Engine.withLogUploadPercent(percent: UInt): Engine =
        copy(logUploadPercent = percent)

    override fun Engine.withSessionInfo(
        info: SessionInformation,
        minSequenceNumber: UInt,
        maxSequenceNumber: UInt
    ): Engine =
        copy(minSequence = minSequenceNumber, maxSequence = maxSequenceNumber, sessionInfo = info)

    override fun castToAdvertisementType(advertisement: DeviceAdvertisingData): EngineAdvertisingData? =
        advertisement as? EngineAdvertisingData

    override fun updateDataFromAdvertisement(
        advertisement: EngineAdvertisingData,
        current: Engine,
    ): Engine = current.copy(
        baseDevice = current.baseDevice.copy(rssi = advertisement.rssi),
        engineStatusFlags = advertisement.engineStatusFlags,
        temperatureSetPointCelsius = advertisement.engineTemperatureSetPoint,
        enginePreferences = advertisement.enginePreferences,
    )

    override fun sendLogRequest(startSequenceNumber: UInt, endSequenceNumber: UInt) {
        TODO("Not yet implemented")
    }

    fun hasEngine(): Boolean = hasDevice()

    fun addEngine(
        engine: EngineBleDevice,
        baseDevice: DeviceInformationBleDevice,
        advertisement: EngineAdvertisingData,
    ) = addDevice(engine, baseDevice, advertisement)

    fun addSimulatedEngine(simEngine: SimulatedEngineBleDevice) = addSimulatedDevice(simEngine)

    override suspend fun updateDataFromSimulatedStatus(status: SpecializedDeviceStatus) {
        handleStatus(status as EngineStatus, simulated = true)
    }

    suspend fun observedEngineStatus(engineStatus: EngineStatus) {
        handleStatus(engineStatus, simulated = false)
    }

    private suspend fun handleStatus(
        status: EngineStatus,
        simulated: Boolean = simulatedDevice != null,
    ) {
        Log.v(LOG_TAG, "EngineManager.handleStatus: $serialNumber $status")

        statusNotificationsMonitor.activity()

        if (simulated || arbitrator.shouldUpdateDataFromStatusForNormalMode(
                status,
                sessionInfo,
            )
        ) {
            handleSessionInfo(
                status.sessionInformation,
                minSequenceNumber = status.minSequenceNumber,
                maxSequenceNumber = status.maxSequenceNumber,
            )

            _normalModeStatusFlow.emit(status)

            if (!simulated) {
                fetchDeviceInfo()
            }

            _deviceFlow.update {
                _deviceFlow.value.copy(
                    engineBatteryStatus = status.engineBatteryStatus,
                    engineStatusFlags = status.engineStatusFlags,
                    engineFanStatus = status.engineFanStatus,
                    engineControllerStatus = status.engineControllerStatus,
                    temperatureSetPointCelsius = status.temperatureSetPoint,
                    controlDeviceType = status.controlDeviceType,
                    controlSerialNumber = status.controlSerialNumber,
                    controlTemperature = status.controlTemperature,
                    knobVoltageMillivolts = status.knobVoltageMillivolts,
                    knobAngleTenthsDegrees = status.knobAngleTenthsDegrees,
                    hopCount = status.hopCount.hopCount,
                    chargingFault = status.chargingFault,
                )
            }
        }
    }

    fun setTemperatureSetPoint(
        temperature: SensorTemperature,
        completionHandler: (Boolean) -> Unit,
    ) {
        val onCompletion: (Boolean) -> Unit = { success ->
            if (success) {
                _deviceFlow.update {
                    _deviceFlow.value.copy(
                        temperatureSetPointCelsius = temperature,
                    )
                }
            }
            completionHandler(success)
        }

        val requestId = makeRequestId()
        simulatedDevice?.sendSetTemperatureSetPoint(temperature, requestId) { status, _ ->
            onCompletion(status)
        } ?: arbitrator.directLink?.sendSetTemperatureSetPoint(
            temperature,
            requestId,
        ) { status, _ ->
            onCompletion(status)
        } ?: run {
            val nodeLinks = arbitrator.connectedNodeLinks
            if (nodeLinks.isNotEmpty()) {
                val handled = AtomicBoolean(false)
                nodeLinks.forEach { node ->
                    node.sendSetEngineTemperatureSetPoint(
                        serialNumber,
                        temperature,
                        requestId,
                    ) { status, _ ->
                        if (!handled.getAndSet(true)) {
                            onCompletion(status)
                        }
                    }
                }
            } else {
                onCompletion(false)
            }
        }
    }

    fun setControlDevice(
        controlDeviceType: CombustionProductType,
        controlSerialNumber: String,
        completionHandler: (Boolean) -> Unit,
    ) {
        val onCompletion: (Boolean) -> Unit = { success ->
            if (success) {
                val newControlSerialNumber = controlSerialNumber.takeIf(String::isNotEmpty)
                _deviceFlow.update { engine ->
                    engine.copy(
                        controlDeviceType = newControlSerialNumber?.let { controlDeviceType },
                        controlSerialNumber = newControlSerialNumber,
                        engineStatusFlags = engine.engineStatusFlags.copy(
                            controlDeviceConnected = newControlSerialNumber != null,
                        ),
                    )
                }
            }
            completionHandler(success)
        }

        val requestId = makeRequestId()
        simulatedDevice?.sendSetControlDevice(
            controlDeviceType,
            controlSerialNumber,
            requestId
        ) { status, _ ->
            onCompletion(status)
        } ?: arbitrator.directLink?.sendSetControlDevice(
            controlDeviceType,
            controlSerialNumber,
            requestId,
        ) { status, _ ->
            onCompletion(status)
        } ?: run {
            val nodeLinks = arbitrator.connectedNodeLinks
            if (nodeLinks.isNotEmpty()) {
                val handled = AtomicBoolean(false)
                nodeLinks.forEach { node ->
                    node.sendSetEngineControlDevice(
                        serialNumber,
                        controlDeviceType,
                        controlSerialNumber,
                        requestId,
                    ) { status, _ ->
                        if (!handled.getAndSet(true)) {
                            onCompletion(status)
                        }
                    }
                }
            } else {
                onCompletion(false)
            }
        }
    }
}
