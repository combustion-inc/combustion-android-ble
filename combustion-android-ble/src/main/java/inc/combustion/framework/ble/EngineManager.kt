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
import inc.combustion.framework.ble.uart.meatnet.NodeMessageType
import inc.combustion.framework.service.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class EngineManager(
    mac: String,
    serialNumber: String,
    initialEngineAdvertisingData: EngineAdvertisingData?,
    scope: CoroutineScope,
    settings: DeviceManager.Settings,
    dfuDisconnectedNodeCallback: (DeviceID) -> Unit,
    private val commandCoordinator: CommandCoordinator = CommandCoordinator(),
) : NodeHybridManager<EngineBleDevice, EngineAdvertisingData, Engine, SimulatedEngineBleDevice>(
    scope = scope,
    settings = settings,
    dfuDisconnectedNodeCallback = dfuDisconnectedNodeCallback,
) {

    override val arbitrator = EngineDataLinkArbitrator()

    // Engine status can arrive concurrently from more than one source (a direct BLE link and/or
    // multiple mesh-relay nodes), all invoking handleStatus() for the same engine. This mutex
    // serializes the read-decide-write sequence below so two overlapping status updates can't
    // clobber each other's _deviceFlow update with a stale `previous` snapshot.
    private val handleStatusMutex = Mutex()

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
        // Engine firmware doesn't yet support log transfer (see LogManager.manageEngine, which
        // is why this is never actually called today). Degrade gracefully rather than throw, so
        // wiring up manageEngine in the future can't turn this into a crash.
        Log.w(
            LOG_TAG,
            "EngineManager.sendLogRequest: log transfer isn't supported by engine firmware yet, ignoring request for $serialNumber",
        )
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

        handleStatusMutex.withLock {
            if (simulated || arbitrator.shouldUpdateDataFromStatusForNormalMode(
                    status,
                    status.sessionInformation,
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

                // engineStatusFlags.controlDeviceConnected is reported here exactly as firmware
                // sent it -- no debounce. A consuming application is recommended to apply its own
                // momentary radio-drop confirmation delay before displaying this flag, since this
                // framework is BLE-only and has no visibility into cloud-relayed engine status
                // (e.g. from other users' phones or node devices) -- a debounce implemented here
                // could only ever protect a viewer with their own direct BLE link to the engine.
                _deviceFlow.update {
                    it.copy(
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

                // Lets a pending setControlDevice/etc. retry loop complete early from this status
                // alone, even if its own response packet is lost -- see CommandCoordinator.
                commandCoordinator.confirmCommandStatus(serialNumber, status)
            }
        }
    }

    /**
     * Sends via [commandCoordinator], retrying with a freshly re-evaluated route every 5s for up
     * to 30s, following the same shape as [setControlDevice]. Engine has no pure-GATT-direct
     * transport (see [setControlDevice]'s KDoc), so every attempt is a [CommandAttemptKey.Node]
     * keyed by [NodeMessageType.SET_ENGINE_TEMPERATURE_SET_POINT] and the request ID.
     *
     * Deliberately does not optimistically write [Engine.temperatureSetPointCelsius] into
     * `_deviceFlow` on completion, for the same reason as [setControlDevice]: a `SUCCESS` result
     * can come from [CommandCoordinator.valueConfirmation]'s "changed to something else" branch, in
     * which case the commanded value would be wrong to display. The next real status update
     * refreshes `_deviceFlow` through [handleStatus] instead.
     */
    fun setTemperatureSetPoint(
        temperature: SensorTemperature,
        completionHandler: (Boolean) -> Unit,
    ) {
        val startingTemperature = _deviceFlow.value.temperatureSetPointCelsius

        scope.launch {
            val result = commandCoordinator.sendRoutedCommand(
                targetSerialNumber = serialNumber,
                send = {
                    val requestId = makeRequestId()
                    val key = CommandAttemptKey.Node(
                        NodeMessageType.SET_ENGINE_TEMPERATURE_SET_POINT,
                        requestId,
                    )
                    val onResponse: (Boolean, Any?) -> Unit = { success, _ ->
                        if (success) {
                            commandCoordinator.completeAttempt(key, success = true)
                        }
                    }

                    val sent = simulatedDevice?.sendSetTemperatureSetPoint(
                        temperature,
                        requestId,
                        onResponse,
                    ) ?: arbitrator.directLink?.sendSetTemperatureSetPoint(
                        temperature,
                        requestId,
                        onResponse,
                    ) ?: run {
                        val nodeLinks = arbitrator.connectedNodeLinks
                        if (nodeLinks.isEmpty()) {
                            null
                        } else {
                            nodeLinks.forEach { node ->
                                node.sendSetEngineTemperatureSetPoint(
                                    serialNumber,
                                    temperature,
                                    requestId,
                                    onResponse,
                                )
                            }
                        }
                    }

                    if (sent != null) setOf(key) else emptySet()
                },
                isConfirmed = CommandCoordinator.valueConfirmation(
                    startingValue = startingTemperature,
                    commandedValue = temperature,
                    extractValue = { (it as? EngineStatus)?.temperatureSetPoint },
                ),
            )

            withContext(Dispatchers.Main.immediate) {
                completionHandler(result == CommandResult.SUCCESS)
            }
        }
    }

    /**
     * Sends via [commandCoordinator], retrying with a freshly re-evaluated route (direct link,
     * else MeatNet nodes) every 5s for up to 30s, and completing early either from a raw ACK/NACK
     * response or from a later status
     * update that shows the change already took effect -- see [CommandCoordinator.valueConfirmation]
     * for why "already changed, even if not to what we asked for" also counts as done, rather than
     * fighting a competing command from another device indefinitely.
     *
     * Engine has no pure-GATT-direct transport distinct from the MeatNet/UART mechanism --
     * [EngineBleDevice.sendSetControlDevice] itself just delegates to a wrapped [NodeBleDevice]
     * (see NodeHybridBleDevice) -- so every attempt, whether over the "direct" link or a relaying
     * node, is registered as a [CommandAttemptKey.Node] keyed by [NodeMessageType.SET_ENGINE_CONTROL_DEVICE]
     * and the request ID, never [CommandAttemptKey.Direct] (that key exists for pure-probe-direct
     * commands, a later phase).
     *
     * A per-transmission response timeout still exists underneath, inside
     * [UartBleDevice.MessageCompletionHandler] (unchanged, still governs a single UART
     * write/response pair) -- but only an explicit success from it is forwarded to
     * [CommandCoordinator.completeAttempt]; its own timeout failures are not, so a slow single
     * transmission can't prematurely end the outer 30s/5s retry cycle. [CommandCoordinator]'s own
     * timer is what decides when to give up or retry.
     *
     * Deliberately does not optimistically write [controlSerialNumber]/[controlDeviceType] into
     * `_deviceFlow` on completion (the previous implementation did). A `SUCCESS` result can now
     * come from [CommandCoordinator.valueConfirmation]'s "value changed to something else" branch
     * -- i.e. a *different* command won -- and blindly writing our own commanded value in that case
     * would display the wrong controller. The next real status update (which should follow shortly
     * either way) updates `_deviceFlow` through the normal [handleStatus] path instead.
     *
     * Does not wire [CommandCoordinator.handleDeviceDisconnected] -- it only purges
     * [CommandAttemptKey.Direct] entries, which this command never registers. A disconnect
     * mid-retry is instead handled by `send` simply being re-evaluated fresh on the next retry,
     * naturally picking whatever route (if any) is currently available.
     */
    fun setControlDevice(
        controlDeviceType: CombustionProductType,
        controlSerialNumber: String,
        completionHandler: (Boolean) -> Unit,
    ) {
        val commandedSerialNumber = controlSerialNumber.takeIf(String::isNotEmpty)
        val startingSerialNumber = _deviceFlow.value.controlSerialNumber

        scope.launch {
            val result = commandCoordinator.sendRoutedCommand(
                targetSerialNumber = serialNumber,
                send = {
                    val requestId = makeRequestId()
                    val key = CommandAttemptKey.Node(
                        NodeMessageType.SET_ENGINE_CONTROL_DEVICE,
                        requestId,
                    )
                    val onResponse: (Boolean, Any?) -> Unit = { success, _ ->
                        if (success) {
                            commandCoordinator.completeAttempt(key, success = true)
                        }
                        // A failure/timeout from this one transmission is not forwarded --
                        // CommandCoordinator's own retry timer decides when to try again.
                    }

                    val sent = simulatedDevice?.sendSetControlDevice(
                        controlDeviceType,
                        controlSerialNumber,
                        requestId,
                        onResponse,
                    ) ?: arbitrator.directLink?.sendSetControlDevice(
                        controlDeviceType,
                        controlSerialNumber,
                        requestId,
                        onResponse,
                    ) ?: run {
                        val nodeLinks = arbitrator.connectedNodeLinks
                        if (nodeLinks.isEmpty()) {
                            null
                        } else {
                            nodeLinks.forEach { node ->
                                node.sendSetEngineControlDevice(
                                    serialNumber,
                                    controlDeviceType,
                                    controlSerialNumber,
                                    requestId,
                                    onResponse,
                                )
                            }
                        }
                    }

                    if (sent != null) setOf(key) else emptySet()
                },
                isConfirmed = CommandCoordinator.valueConfirmation(
                    startingValue = startingSerialNumber,
                    commandedValue = commandedSerialNumber,
                    extractValue = { (it as? EngineStatus)?.controlSerialNumber },
                ),
            )

            withContext(Dispatchers.Main.immediate) {
                completionHandler(result == CommandResult.SUCCESS)
            }
        }
    }
}
