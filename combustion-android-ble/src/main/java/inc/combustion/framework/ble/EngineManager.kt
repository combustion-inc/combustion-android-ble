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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

// How long a control device must be continuously reported as disconnected before the engine is
// treated as uncontrolled, so a brief radio drop doesn't flash the UI to "unattended."
private const val CONTROL_DEVICE_DISCONNECT_CONFIRMATION_DELAY_MS = 30_000L

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

    private val controlDeviceDisconnectMonitor = IdleMonitor()
    private var controlDeviceDisconnectPending = false

    // Engine status can arrive concurrently from more than one source (a direct BLE link and/or
    // multiple mesh-relay nodes), all invoking handleStatus() for the same engine. This mutex
    // serializes the read-decide-write sequence below so two overlapping status updates can't
    // race on controlDeviceDisconnectPending/controlDeviceDisconnectMonitor or clobber each
    // other's _deviceFlow update with a stale `previous` snapshot.
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
                // Snapshot before handleSessionInfo() overwrites `sessionInfo` in place, so we can
                // still tell whether the cooking session changed.
                val previousSessionInfo = sessionInfo
                val previous = _deviceFlow.value

                handleSessionInfo(
                    status.sessionInformation,
                    minSequenceNumber = status.minSequenceNumber,
                    maxSequenceNumber = status.maxSequenceNumber,
                )

                _normalModeStatusFlow.emit(status)

                if (!simulated) {
                    fetchDeviceInfo()
                }

                val sessionChanged =
                    previousSessionInfo != null && previousSessionInfo != status.sessionInformation

                val controlDeviceType = status.controlDeviceType
                val controlSerialNumber = status.controlSerialNumber
                val controlTemperature = status.controlTemperature

                val controlDeviceConnected: Boolean
                when {
                    status.engineStatusFlags.controlDeviceConnected -> {
                        // Reconnected, or a different controller took over -- adopt immediately
                        // and clear any pending disconnect confirmation.
                        controlDeviceDisconnectPending = false
                        controlDeviceConnected = true
                    }

                    sessionChanged || previous.controlSerialNumber == null -> {
                        // Nothing controlled before, or the cooking session moved on -- nothing
                        // to debounce.
                        controlDeviceDisconnectPending = false
                        controlDeviceConnected = false
                    }

                    else -> {
                        // Was controlled and firmware now reports disconnected -- debounce
                        // controlDeviceConnected alone so a momentary status miss doesn't flap
                        // the UI/notification to disconnected before firmware confirms it.
                        val enteringPending = !controlDeviceDisconnectPending
                        if (enteringPending) {
                            controlDeviceDisconnectPending = true
                            controlDeviceDisconnectMonitor.activity()
                        }
                        val confirmed = controlDeviceDisconnectMonitor.isIdle(
                            CONTROL_DEVICE_DISCONNECT_CONFIRMATION_DELAY_MS,
                        )
                        controlDeviceConnected = !confirmed
                        // Log only on the pending-entry and pending->confirmed transitions, not on
                        // every status in between -- while disconnected, every subsequent status
                        // falls into this branch, and logging each one would spam the log for as
                        // long as the engine stays disconnected.
                        val justConfirmed = confirmed && previous.engineStatusFlags.controlDeviceConnected
                        if (enteringPending || justConfirmed) {
                            Log.d(
                                LOG_TAG,
                                "EngineManager.handleStatus: $serialNumber debouncing " +
                                        "controlDeviceConnected (was controlled, firmware now reports " +
                                        "disconnected) to avoid flapping on a momentary status miss -- " +
                                        "confirmed=$confirmed controlDeviceConnected=$controlDeviceConnected",
                            )
                        }
                    }
                }

                _deviceFlow.update {
                    it.copy(
                        engineBatteryStatus = status.engineBatteryStatus,
                        engineStatusFlags = status.engineStatusFlags.copy(
                            controlDeviceConnected = controlDeviceConnected,
                        ),
                        engineFanStatus = status.engineFanStatus,
                        engineControllerStatus = status.engineControllerStatus,
                        temperatureSetPointCelsius = status.temperatureSetPoint,
                        controlDeviceType = controlDeviceType,
                        controlSerialNumber = controlSerialNumber,
                        controlTemperature = controlTemperature,
                        knobVoltageMillivolts = status.knobVoltageMillivolts,
                        knobAngleTenthsDegrees = status.knobAngleTenthsDegrees,
                        hopCount = status.hopCount.hopCount,
                        chargingFault = status.chargingFault,
                    )
                }
            }
        }
    }

    fun setTemperatureSetPoint(
        temperature: SensorTemperature,
        completionHandler: (Boolean) -> Unit,
    ) {
        // No lock needed here, unlike setControlDevice: this only writes _deviceFlow, via a
        // self-contained transform (it.copy(temperatureSetPointCelsius = temperature)) with no
        // dependency on any other shared mutable state. MutableStateFlow.update{} already
        // guarantees that write is atomic and never lost against a concurrent handleStatus()
        // update -- a lock here wouldn't change which write "wins" when both land close together,
        // only how they interleave, so it would add complexity without fixing anything.
        val onCompletion: (Boolean) -> Unit = { success ->
            if (success) {
                _deviceFlow.update {
                    it.copy(
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
        // Dispatched onto scope and taken under handleStatusMutex -- this callback fires from a
        // UART completion handler that can run concurrently with handleStatus(), and both paths
        // read/write controlDeviceDisconnectPending and the same control-device _deviceFlow
        // fields. Without the lock, this could race with a concurrent handleStatus() call and
        // either clobber its decision or be clobbered by it. The lock/update itself has no
        // thread-affinity requirement and runs on scope's default dispatcher; only
        // completionHandler is explicitly switched to Dispatchers.Main.immediate below, since it's
        // public API and the UART completion path it previously ran on synchronously
        // (UartBleDevice.MessageCompletionHandler.handled(), invoked from NodeBleDevice's UART
        // message loop) always delivers on Dispatchers.Main -- callers may reasonably do UI work
        // in completionHandler, same as before this lock was added.
        val onCompletion: (Boolean) -> Unit = { success ->
            scope.launch {
                if (success) {
                    val newControlSerialNumber = controlSerialNumber.takeIf(String::isNotEmpty)
                    handleStatusMutex.withLock {
                        controlDeviceDisconnectPending = false
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
                }
                withContext(Dispatchers.Main.immediate) {
                    completionHandler(success)
                }
            }
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
