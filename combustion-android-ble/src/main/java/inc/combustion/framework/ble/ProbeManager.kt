/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeManager.kt
 * Author: https://github.com/miwright2
 *
 * MIT License
 *
 * Copyright (c) 2023. Combustion Inc.
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
import inc.combustion.framework.InstantReadFilter
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.device.*
import inc.combustion.framework.ble.scanning.ProbeAdvertisingData
import inc.combustion.framework.ble.uart.MessageType
import inc.combustion.framework.ble.uart.ProbeLogResponse
import inc.combustion.framework.ble.uart.meatnet.NodeMessageType
import inc.combustion.framework.service.*
import inc.combustion.framework.service.utils.DefaultLinearizationTimerImpl
import inc.combustion.framework.service.utils.PredictionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * This class is responsible for managing and arbitrating the data links to a temperature
 * probe.  When MeatNet is enabled that includes data links through repeater devices over
 * MeatNet and direct links to temperature probes.  When MeatNet is disabled, this class
 * manages only direct links to temperature probes.  The class is responsible for presenting
 * a common interface over both scenarios.
 *
 * @property scope Coroutine scope.
 * @property settings Service settings.
 * @constructor
 * Constructs a probe manager
 *
 * @param serialNumber The serial number of the probe being managed.
 */
internal class ProbeManager(
    serialNumber: String,
    private val scope: CoroutineScope,
    private val settings: DeviceManager.Settings,
    private val dfuDisconnectedNodeCallback: (DeviceID) -> Unit,
    private val commandCoordinator: CommandCoordinator = CommandCoordinator(
        retryIntervalMs = PROBE_DIRECT_RETRY_INTERVAL_MS,
    ),
) : BleManager() {
    companion object {
        private const val MEATNET_STATUS_NOTIFICATIONS_TIMEOUT_MS = 30_000L
        private const val PREDICTION_IDLE_TIMEOUT_MS = Probe.PREDICTION_IDLE_TIMEOUT_MS

        // The raw probe-direct UART protocol has no per-request ID: ProbeBleDevice.processUartResponses
        // always matches a response against a fixed null key, regardless of what (if anything) was
        // passed to the corresponding send*/wait() call -- see setPowerMode's KDoc. A retried
        // direct-link attempt's wait() call therefore always registers under that same null key,
        // so it only avoids colliding with the still-outstanding previous attempt if that attempt's
        // own per-transmission timeout (UartCapableProbe.PROBE_MESSAGE_RESPONSE_TIMEOUT_MS) has
        // already cleared it by the time the next retry fires -- i.e. this must stay strictly
        // greater than that timeout, with enough margin to absorb scheduling jitter.
        private const val PROBE_DIRECT_RETRY_INTERVAL_MS =
            UartCapableProbe.PROBE_MESSAGE_RESPONSE_TIMEOUT_MS + 1_000L
        private const val PROBE_INSTANT_READ_IDLE_TIMEOUT_MS = 5000L
    }

    // encapsulates logic for managing network data links
    override val arbitrator = ProbeDataLinkArbitrator(settings)

    // Probe status can arrive concurrently from more than one source (a direct BLE link and/or
    // multiple mesh-relay nodes, one observer registered per link in observe()), all invoking
    // handleProbeStatus() for the same probe. This mutex serializes the read-decide-write
    // sequence in handleProbeStatus() so two overlapping status updates can't race on the
    // arbitrator's session/sequence bookkeeping or clobber each other's _deviceFlow update --
    // mirrors the same fix in EngineManager/GaugeManager.
    private val handleStatusMutex = Mutex()

    // Serializes concurrent CommandCoordinator-driven calls to the *same* command on this probe
    // (e.g. two overlapping setPowerMode() calls), one Mutex per MessageType, created lazily. This
    // guards a real collision on the direct link: CommandAttemptKey.Direct has no per-call
    // discriminator (no reqId -- see PROBE_DIRECT_RETRY_INTERVAL_MS), so two concurrent commands
    // of the same type would both register under the identical key, and whichever registers last
    // would silently steal the other's eventual response/completion. Held around the whole
    // sendRoutedCommand call (all its retries), not just one attempt, so a second concurrent call
    // waits for the first to fully resolve before starting its own -- mirrors
    // WiFiNodesManager.getNodeMutex.
    private val commandMutexes: MutableMap<MessageType, Mutex> = ConcurrentHashMap()

    private fun getCommandMutex(messageType: MessageType): Mutex =
        // computeIfAbsent, not a plain get-then-put: the latter isn't atomic, so two callers
        // racing to create the very first Mutex for a given MessageType could each end up
        // holding a different instance and never actually serialize against each other --
        // silently defeating the one thing this map exists for.
        commandMutexes.computeIfAbsent(messageType) { Mutex() }

    // idle monitors
    private val instantReadMonitor = IdleMonitor()
    private val statusNotificationsMonitor = IdleMonitor()
    private val predictionMonitor = IdleMonitor()

    // holds the current state and data for this probe
    private val _deviceFlow = MutableStateFlow(Probe.create(serialNumber = serialNumber))

    // the flow that is consumed to get state and date updates
    override val deviceFlow: StateFlow<Probe> = _deviceFlow.asStateFlow()

    // the flow that produces ProbeStatus updates from MeatNet
    private val _normalModeProbeStatusFlow = MutableSharedFlow<ProbeStatus>(
        replay = 0, extraBufferCapacity = 10, BufferOverflow.DROP_OLDEST
    )

    // the flow that is consumed to get ProbeStatus updates from MeatNet
    override val normalModeStatusFlow = _normalModeProbeStatusFlow.asSharedFlow()

    // the flow that produces LogResponses from MeatNet
    private val _logResponseFlow = MutableSharedFlow<ProbeLogResponse>(
        replay = 0, extraBufferCapacity = 50, BufferOverflow.SUSPEND
    )

    // tracks the link that is processing the most recent log transfer
    private var logTransferLink: ProbeBleDeviceBase? = null

    // tracks if we've run into a message timeout condition getting
    // session info
    private var sessionInfoTimeout: Boolean = false

    // the flow that is consumed to get LogResponses from MeatNet
    override val logResponseFlow = _logResponseFlow.asSharedFlow()

    // serial number of the probe that is being managed by this manager
    override val serialNumber: String
        get() {
            return _deviceFlow.value.serialNumber
        }

    // current upload state for this probe, determined by LogManager
    override var uploadState: ProbeUploadState
        get() {
            return _deviceFlow.value.uploadState
        }
        set(value) {
            if (value != _deviceFlow.value.uploadState) {
                _deviceFlow.update { it.copy(uploadState = value) }
            }
        }

    override var recordsDownloaded: Int
        get() {
            return _deviceFlow.value.recordsDownloaded
        }
        set(value) {
            if (value != _deviceFlow.value.recordsDownloaded) {
                _deviceFlow.update { it.copy(recordsDownloaded = value) }
            }
        }

    override var logUploadPercent: UInt
        get() {
            return _deviceFlow.value.logUploadPercent
        }
        set(value) {
            if (value != _deviceFlow.value.logUploadPercent) {
                _deviceFlow.update { it.copy(logUploadPercent = value) }
            }
        }

    val connectionState: DeviceConnectionState
        get() {
            // if this is a simulated probe, no need to arbitrate
            simulatedProbe?.let {
                return it.connectionState
            }

            // if not using meatnet, then we use the direct link
            if (!settings.meatNetEnabled) {
                return arbitrator.bleDevice?.connectionState
                    ?: DeviceConnectionState.OUT_OF_RANGE
            }

            // if the MeatNet preferred link is the direct link, then just use the direct link's state.
            if (arbitrator.directLink == arbitrator.preferredMeatNetLink) {
                arbitrator.directLink?.let {
                    return it.connectionState
                }
            }

            // if all devices are out of range, then connection state is Out of Range
            if (arbitrator.meatNetIsOutOfRange) {
                return DeviceConnectionState.OUT_OF_RANGE
            }

            // else, if there is any device that is connected
            arbitrator.getPreferredConnectionState(DeviceConnectionState.CONNECTED)?.let {
                // and we have run into a session info message timeout condition, then we are
                // in a NO_ROUTE scenario.  exclude when we are actively uploading, because we know that
                // can saturate the network and responses might be dropped.
                if (sessionInfoTimeout && (uploadState !is ProbeUploadState.ProbeUploadInProgress)) {
                    return DeviceConnectionState.NO_ROUTE
                }

                // otherwise, we are connected
                return DeviceConnectionState.CONNECTED
            }

            // else, if there is any device that is connecting
            arbitrator.getPreferredConnectionState(DeviceConnectionState.CONNECTING)?.let {
                return DeviceConnectionState.CONNECTING
            }

            // else, if there is any device that is connecting
            arbitrator.getPreferredConnectionState(DeviceConnectionState.DISCONNECTING)?.let {
                return DeviceConnectionState.DISCONNECTING
            }

            // else, if there is any device that is advertising connectable.
            arbitrator.getPreferredConnectionState(DeviceConnectionState.ADVERTISING_CONNECTABLE)
                ?.let {
                    return DeviceConnectionState.ADVERTISING_CONNECTABLE
                }

            // else, if there is any device that is advertising not connectable
            arbitrator.getPreferredConnectionState(DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE)
                ?.let {
                    return DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE
                }

            // else, all MeatNet devices are connected with no route, then the state is no route
            return DeviceConnectionState.NO_ROUTE
        }

    private val fwVersion: FirmwareVersion?
        get() {
            simulatedProbe?.let { return it.deviceInfoFirmwareVersion }

            arbitrator.bleDevice?.let {
                if (it.deviceInfoFirmwareVersion != null) {
                    return it.deviceInfoFirmwareVersion
                }
            }

            return arbitrator.repeatedProbeBleDevices.firstOrNull {
                it.deviceInfoFirmwareVersion != null
            }?.deviceInfoFirmwareVersion
        }

    private val hwRevision: String?
        get() {
            simulatedProbe?.let { return it.deviceInfoHardwareRevision }

            arbitrator.bleDevice?.let {
                if (it.deviceInfoHardwareRevision != null) {
                    return it.deviceInfoHardwareRevision
                }
            }

            return arbitrator.repeatedProbeBleDevices.firstOrNull {
                it.deviceInfoHardwareRevision != null
            }?.deviceInfoHardwareRevision
        }

    private val modelInformation: ModelInformation?
        get() {
            simulatedProbe?.let { return it.deviceInfoModelInformation }

            arbitrator.bleDevice?.let {
                if (it.deviceInfoModelInformation != null) {
                    return it.deviceInfoModelInformation
                }
            }

            return arbitrator.repeatedProbeBleDevices.firstOrNull {
                it.deviceInfoModelInformation != null
            }?.deviceInfoModelInformation
        }

    override val device: Probe
        get() {
            return _deviceFlow.value
        }

    // current minimum sequence number for the probe
    override val minSequenceNumber: UInt?
        get() {
            return _deviceFlow.value.minSequence
        }

    // current maximum sequence number for the probe
    override val maxSequenceNumber: UInt?
        get() {
            return _deviceFlow.value.maxSequence
        }

    private val predictionManager = PredictionManager(DefaultLinearizationTimerImpl())

    private val instantReadFilter = InstantReadFilter()

    init {
        predictionManager.setPredictionCallback { prediction, shouldUpdateStatus ->
            if (shouldUpdateStatus) {
                _deviceFlow.update { updatePredictionInfo(prediction, it) }
            }
        }

        monitorStatusNotifications()
    }

//    fun addJob(serialNumber: String, job: Job) = jobManager.addJob(serialNumber, job)

    fun addProbe(
        probe: ProbeBleDevice,
        baseDevice: DeviceInformationBleDevice,
        advertisement: ProbeAdvertisingData,
    ) {
        if (IGNORE_PROBES) return

        if (arbitrator.addDevice(probe, baseDevice)) {
            handleAdvertisingPackets(probe, advertisement)
            observe(probe)
            Log.i(LOG_TAG, "PM($serialNumber) is managing link ${probe.linkId}")
        }
    }

    fun addRepeatedProbe(
        repeatedProbe: RepeatedProbeBleDevice,
        repeater: DeviceInformationBleDevice,
        advertisement: ProbeAdvertisingData
    ) {
        if (IGNORE_REPEATERS) return

        if (arbitrator.addRepeatedProbe(repeatedProbe, repeater)) {
            handleAdvertisingPackets(repeatedProbe, advertisement)
            observe(repeatedProbe)
            Log.i(LOG_TAG, "PM($serialNumber) is managing link ${repeatedProbe.linkId}")
        }
    }

    private var simulatedProbe: SimulatedProbeBleDevice? = null

    fun addSimulatedProbe(simProbe: SimulatedProbeBleDevice) {
        simulatedProbe ?: run {
            var updatedProbe = _deviceFlow.value

            // process simulated device status notifications
            simProbe.observeProbeStatusUpdates(hopCount = null) { status, _ ->
                if (status.mode == ProbeMode.NORMAL) {
                    updatedProbe = updateNormalMode(status, updatedProbe)
                    _normalModeProbeStatusFlow.emit(status)
                } else if (status.mode == ProbeMode.INSTANT_READ) {
                    updatedProbe = updateInstantRead(status, updatedProbe)
                }
            }

            // process simulated connection state changes
            simProbe.observeConnectionState { state ->
                updatedProbe = handleConnectionState(simProbe, state, updatedProbe)
            }

            // process simulated out of range
            simProbe.observeOutOfRange(OUT_OF_RANGE_TIMEOUT) {
                updatedProbe = handleOutOfRange(updatedProbe)
            }

            // process simulated advertising packets
            simProbe.observeAdvertisingPackets(serialNumber, simProbe.mac) { advertisement ->
                if (advertisement is ProbeAdvertisingData) {
                    updatedProbe = updateDataFromAdvertisement(advertisement, updatedProbe)
                    if (settings.autoReconnect && simProbe.shouldConnect) {
                        simProbe.connect()
                    }
                }
            }

            // process simulated rssi
            simProbe.observeRemoteRssi { rssi ->
                updatedProbe =
                    updatedProbe.copy(baseDevice = _deviceFlow.value.baseDevice.copy(rssi = rssi))
            }

            simulatedProbe = simProbe

            _deviceFlow.update {
                updatedProbe.copy(baseDevice = it.baseDevice.copy(mac = simProbe.mac))
            }
        }
    }

    fun connect() {
        arbitrator.getNodesNeedingConnection(true).forEach { node ->
            node.connect()
        }

        simulatedProbe?.shouldConnect = true
        simulatedProbe?.connect()
    }

    fun disconnect(canDisconnectFromMeatNetDevices: Boolean = false) {
        arbitrator.getNodesNeedingDisconnect(
            canDisconnectFromMeatNetDevices = canDisconnectFromMeatNetDevices
        ).forEach { node ->
            Log.d(LOG_TAG, "ProbeManager: disconnecting $node")

            // Since this is an explicit disconnect, we want to also disable the auto-reconnect flag
            arbitrator.setShouldAutoReconnect(false)
            node.disconnect()
        }

        arbitrator.directLinkDiscoverTimestamp = null

        simulatedProbe?.shouldConnect = false
        simulatedProbe?.disconnect()
    }

    /**
     * Sends via [commandCoordinator] (see [PROBE_DIRECT_RETRY_INTERVAL_MS] for why retrying this
     * command safely needs its own retry interval). No MeatNet route exists for this command, so
     * it only ever registers a [CommandAttemptKey.Direct] keyed by
     * [MessageType.SET_PROBE_COLOR] -- see [setPowerMode]'s KDoc for why that attempt is sent with
     * `reqId = null`, and for why the whole call is wrapped in [getCommandMutex].
     */
    fun setProbeColor(color: ProbeColor, completionHandler: (Boolean) -> Unit) {
        val startingColor = _deviceFlow.value.color

        scope.launch {
            val result = getCommandMutex(MessageType.SET_PROBE_COLOR).withLock {
                commandCoordinator.sendRoutedCommand(
                    targetSerialNumber = serialNumber,
                    send = {
                        val directLink = simulatedProbe ?: arbitrator.directLink
                        val key = directLink?.let {
                            CommandAttemptKey.Direct(MessageType.SET_PROBE_COLOR, it.id)
                        }
                        val onResponse: (Boolean, Any?) -> Unit = { success, _ ->
                            if (success && key != null) {
                                commandCoordinator.completeAttempt(key, success = true)
                            }
                        }

                        // Note: not supported by MeatNet.
                        val sent = simulatedProbe?.sendSetProbeColor(color, onResponse)
                            ?: arbitrator.directLink?.sendSetProbeColor(color, onResponse)

                        if (sent != null && key != null) setOf(key) else emptySet()
                    },
                    isConfirmed = CommandCoordinator.valueConfirmation(
                        startingValue = startingColor.toExtractedValue(),
                        commandedValue = color,
                        extractValue = { it.extractedAs<ProbeStatus, _> { s -> s.color } },
                    ),
                )
            }

            withContext(Dispatchers.Main.immediate) {
                completionHandler(result == CommandResult.SUCCESS)
            }
        }
    }

    /**
     * Sends via [commandCoordinator]. See [setPowerMode]'s KDoc for the Direct-vs-Node key
     * routing rationale and the null-vs-real request ID split, both of which apply identically
     * here.
     *
     * Deliberately does not optimistically write [id] into `_deviceFlow` on completion -- see
     * `EngineManager.setControlDevice`'s KDoc for why.
     */
    fun setProbeID(probeId: ProbeID, completionHandler: (Boolean) -> Unit) {
        val startingProbeId = _deviceFlow.value.id

        scope.launch {
            val result = getCommandMutex(MessageType.SET_PROBE_ID).withLock {
                commandCoordinator.sendRoutedCommand(
                    targetSerialNumber = serialNumber,
                    send = {
                        val directLink = simulatedProbe ?: arbitrator.directLink
                        val nodeLinks = arbitrator.connectedNodeLinks
                        val nodeRequestId = makeRequestId()

                        val key = when {
                            directLink != null ->
                                CommandAttemptKey.Direct(MessageType.SET_PROBE_ID, directLink.id)

                            nodeLinks.isNotEmpty() ->
                                CommandAttemptKey.Node(NodeMessageType.SET_PROBE_ID, nodeRequestId)

                            else -> null
                        }
                        val onResponse: (Boolean, Any?) -> Unit = { success, _ ->
                            if (success && key != null) {
                                commandCoordinator.completeAttempt(key, success = true)
                            }
                        }

                        val sent = simulatedProbe?.sendSetProbeID(probeId, null, onResponse)
                            ?: arbitrator.directLink?.sendSetProbeID(probeId, null, onResponse)
                            ?: run {
                                if (nodeLinks.isEmpty()) {
                                    null
                                } else {
                                    nodeLinks.forEach {
                                        it.sendSetProbeID(probeId, nodeRequestId, onResponse)
                                    }
                                }
                            }

                        if (sent != null && key != null) setOf(key) else emptySet()
                    },
                    isConfirmed = CommandCoordinator.valueConfirmation(
                        startingValue = startingProbeId.toExtractedValue(),
                        commandedValue = probeId,
                        extractValue = { it.extractedAs<ProbeStatus, _> { s -> s.id } },
                    ),
                )
            }

            withContext(Dispatchers.Main.immediate) {
                completionHandler(result == CommandResult.SUCCESS)
            }
        }
    }

    /**
     * Sends via [commandCoordinator]. See [setPowerMode]'s KDoc for the Direct-vs-Node key
     * routing rationale and the null-vs-real request ID split, both of which apply identically
     * here.
     *
     * [mode] and [removalTemperatureC] are confirmed together as a single pair -- see
     * [PredictionStatus.predictionMode]/[PredictionStatus.setPointTemperature] -- since a status
     * only shows both changed together, never independently, for this command.
     */
    fun setPrediction(
        removalTemperatureC: Double,
        mode: ProbePredictionMode,
        completionHandler: (Boolean) -> Unit
    ) {
        // Built from Probe.predictionMode/setPointTemperatureCelsius (both null until a real
        // status arrives, and only ever null/non-null together per this function's KDoc), not
        // wrapped with toExtractedValue(): the Pair below is never itself null -- `to` always
        // constructs one -- so gating on its component instead is what actually distinguishes "no
        // confirmed prediction yet" from "confirmed prediction of some value." See
        // CommandCoordinator.valueConfirmation's KDoc for why that distinction matters.
        val startingPredictionMode = _deviceFlow.value.predictionMode
        val startingSetPointTemperature = _deviceFlow.value.setPointTemperatureCelsius
        val startingPrediction = if (startingPredictionMode != null && startingSetPointTemperature != null) {
            ExtractedValue.Present(startingPredictionMode to startingSetPointTemperature)
        } else {
            ExtractedValue.Absent
        }

        scope.launch {
            val result = getCommandMutex(MessageType.SET_PREDICTION).withLock {
                commandCoordinator.sendRoutedCommand(
                    targetSerialNumber = serialNumber,
                    send = {
                        val directLink = simulatedProbe ?: arbitrator.directLink
                        val nodeLinks = arbitrator.connectedNodeLinks
                        val nodeRequestId = makeRequestId()

                        val key = when {
                            directLink != null ->
                                CommandAttemptKey.Direct(MessageType.SET_PREDICTION, directLink.id)

                            nodeLinks.isNotEmpty() ->
                                CommandAttemptKey.Node(NodeMessageType.SET_PREDICTION, nodeRequestId)

                            else -> null
                        }
                        val onResponse: (Boolean, Any?) -> Unit = { success, _ ->
                            if (success && key != null) {
                                commandCoordinator.completeAttempt(key, success = true)
                            }
                        }

                        val sent = simulatedProbe?.sendSetPrediction(
                            removalTemperatureC,
                            mode,
                            null,
                            onResponse,
                        ) ?: arbitrator.directLink?.sendSetPrediction(
                            removalTemperatureC,
                            mode,
                            null,
                            onResponse,
                        ) ?: run {
                            if (nodeLinks.isEmpty()) {
                                null
                            } else {
                                nodeLinks.forEach {
                                    it.sendSetPrediction(
                                        removalTemperatureC,
                                        mode,
                                        nodeRequestId,
                                        onResponse,
                                    )
                                }
                            }
                        }

                        if (sent != null && key != null) setOf(key) else emptySet()
                    },
                    isConfirmed = CommandCoordinator.valueConfirmation(
                        startingValue = startingPrediction,
                        commandedValue = mode to removalTemperatureC,
                        extractValue = {
                            it.extractedAs<ProbeStatus, _> { s ->
                                s.predictionStatus.predictionMode to s.predictionStatus.setPointTemperature
                            }
                        },
                    ),
                )
            }

            withContext(Dispatchers.Main.immediate) {
                completionHandler(result == CommandResult.SUCCESS)
            }
        }
    }

    /**
     * Sends via [commandCoordinator]. See [setPowerMode]'s KDoc for the Direct-vs-Node key
     * routing rationale and the null-vs-real request ID split, both of which apply identically
     * here.
     *
     * Deliberately does not optimistically write [foodSafeData] into `_deviceFlow` on completion
     * -- see `EngineManager.setControlDevice`'s KDoc for why.
     */
    fun configureFoodSafe(foodSafeData: FoodSafeData, completionHandler: (Boolean) -> Unit) {
        val startingFoodSafeData = _deviceFlow.value.foodSafeData

        scope.launch {
            val result = getCommandMutex(MessageType.CONFIGURE_FOOD_SAFE).withLock {
                commandCoordinator.sendRoutedCommand(
                    targetSerialNumber = serialNumber,
                    send = {
                        val directLink = simulatedProbe ?: arbitrator.directLink
                        val nodeLinks = arbitrator.connectedNodeLinks
                        val nodeRequestId = makeRequestId()

                        val key = when {
                            directLink != null ->
                                CommandAttemptKey.Direct(
                                    MessageType.CONFIGURE_FOOD_SAFE,
                                    directLink.id,
                                )

                            nodeLinks.isNotEmpty() ->
                                CommandAttemptKey.Node(
                                    NodeMessageType.CONFIGURE_FOOD_SAFE,
                                    nodeRequestId,
                                )

                            else -> null
                        }
                        val onResponse: (Boolean, Any?) -> Unit = { success, _ ->
                            if (success && key != null) {
                                commandCoordinator.completeAttempt(key, success = true)
                            }
                        }

                        val sent = simulatedProbe?.sendConfigureFoodSafe(
                            foodSafeData,
                            null,
                            onResponse,
                        ) ?: arbitrator.directLink?.sendConfigureFoodSafe(
                            foodSafeData,
                            null,
                            onResponse,
                        ) ?: run {
                            if (nodeLinks.isEmpty()) {
                                null
                            } else {
                                nodeLinks.forEach {
                                    it.sendConfigureFoodSafe(
                                        foodSafeData,
                                        nodeRequestId,
                                        onResponse,
                                    )
                                }
                            }
                        }

                        if (sent != null && key != null) setOf(key) else emptySet()
                    },
                    isConfirmed = CommandCoordinator.valueConfirmation(
                        startingValue = startingFoodSafeData.toExtractedValue(),
                        commandedValue = foodSafeData,
                        // Not extractedAs: a null foodSafeData means the status packet simply
                        // didn't include those trailing bytes (older firmware/truncated relay,
                        // see ProbeStatus.fromRawData's dataIncludesFoodSafe) -- it's never a
                        // legitimate "present but null" observation the way
                        // EngineStatus.controlSerialNumber's null is, so it must fall back to
                        // Absent rather than being wrapped in Present.
                        extractValue = {
                            (it as? ProbeStatus)?.foodSafeData?.let { v -> ExtractedValue.Present(v) }
                                ?: ExtractedValue.Absent
                        },
                    ),
                )
            }

            withContext(Dispatchers.Main.immediate) {
                completionHandler(result == CommandResult.SUCCESS)
            }
        }
    }

    /**
     * Not ported to [commandCoordinator]: Reset Food Safe (0x08) is idempotent in principle (it
     * resets to a fixed state), but a spurious retry after a lost ACK could still wipe out a few
     * seconds of legitimately re-accumulated tracking, and there's no status field that fits
     * [CommandCoordinator.valueConfirmation] to confirm it early anyway -- see the discussion on
     * `ProbeManager.resetProbe` for the closely related reasoning on `resetProbe` itself, which
     * has a stronger, data-loss version of this same problem (each execution starts a new
     * session, not just a reset to a fixed state).
     */
    fun resetFoodSafe(completionHandler: (Boolean) -> Unit) {
        simulatedProbe?.sendResetFoodSafe { status, _ ->
            completionHandler(status)
        } ?: run {
            // if there is a direct link to the probe, then use that
            arbitrator.directLink?.sendResetFoodSafe { status, _ ->
                completionHandler(status)
            } ?: run {
                val nodeLinks = arbitrator.connectedNodeLinks
                if (nodeLinks.isNotEmpty()) {
                    val handled = AtomicBoolean(false)
                    val requestId = makeRequestId()
                    nodeLinks.forEach {
                        it.sendResetFoodSafe(requestId) { status, _ ->
                            if (!handled.getAndSet(true)) {
                                completionHandler(status)
                            }
                        }
                    }

                } else {
                    completionHandler(false)
                }
            }
        }
    }

    /**
     * Sends via [commandCoordinator]. Unlike Engine/Gauge,
     * [arbitrator]'s direct link is a genuine separate GATT/UART connection to the probe, distinct
     * from a MeatNet node link -- so an attempt over it is registered as a
     * [CommandAttemptKey.Direct] keyed by [MessageType.SET_POWER_MODE] and the probe's device ID,
     * while an attempt over a node is a [CommandAttemptKey.Node] keyed by
     * [NodeMessageType.SET_POWER_MODE] and the request ID; [handleConnectionState] purges the
     * former via [CommandCoordinator.handleDeviceDisconnected] when that direct link drops.
     *
     * The direct-link attempt is always sent with `reqId = null`, never a freshly generated one:
     * [ProbeBleDevice]'s raw UART response processing always matches a response against a fixed
     * null key regardless of what was passed to `wait()`, so passing a real ID there wouldn't just
     * fail to help, it would make the response *never* match at all (registered under a non-null
     * key, looked up under null) -- see [PROBE_DIRECT_RETRY_INTERVAL_MS] for how retries stay safe
     * despite every direct-link attempt sharing that same null key. Only the MeatNet/node path
     * (matched by real per-attempt request IDs -- see [RepeatedProbeBleDevice]) is given one.
     *
     * The whole call (all its retries) is wrapped in [getCommandMutex]: since
     * [CommandAttemptKey.Direct] has no per-call discriminator either, two concurrent calls to
     * this same command would both register under the identical key, and whichever registers last
     * would silently steal the other's eventual response/completion. The mutex makes a second
     * concurrent call wait for the first to fully resolve rather than racing it.
     *
     * Deliberately does not optimistically write [ThermometerPreferences.powerMode] into
     * `_deviceFlow` on completion -- see `EngineManager.setControlDevice`'s KDoc for why.
     */
    fun setPowerMode(powerMode: ProbePowerMode, completionHandler: (Boolean) -> Unit) {
        val startingPowerMode = _deviceFlow.value.thermometerPrefs?.powerMode

        scope.launch {
            val result = getCommandMutex(MessageType.SET_POWER_MODE).withLock {
                commandCoordinator.sendRoutedCommand(
                    targetSerialNumber = serialNumber,
                    send = {
                        val directLink = simulatedProbe ?: arbitrator.directLink
                        val nodeLinks = arbitrator.connectedNodeLinks
                        val nodeRequestId = makeRequestId()

                        val key = when {
                            directLink != null ->
                                CommandAttemptKey.Direct(MessageType.SET_POWER_MODE, directLink.id)

                            nodeLinks.isNotEmpty() ->
                                CommandAttemptKey.Node(NodeMessageType.SET_POWER_MODE, nodeRequestId)

                            else -> null
                        }
                        val onResponse: (Boolean, Any?) -> Unit = { success, _ ->
                            if (success && key != null) {
                                commandCoordinator.completeAttempt(key, success = true)
                            }
                        }

                        val sent = simulatedProbe?.sendSetPowerMode(
                            powerMode,
                            null,
                            onResponse,
                        ) ?: arbitrator.directLink?.sendSetPowerMode(
                            powerMode,
                            null,
                            onResponse,
                        ) ?: run {
                            if (nodeLinks.isEmpty()) {
                                null
                            } else {
                                nodeLinks.forEach {
                                    it.sendSetPowerMode(powerMode, nodeRequestId, onResponse)
                                }
                            }
                        }

                        if (sent != null && key != null) setOf(key) else emptySet()
                    },
                    isConfirmed = CommandCoordinator.valueConfirmation(
                        startingValue = startingPowerMode.toExtractedValue(),
                        commandedValue = powerMode,
                        extractValue = { it.extractedAs<ProbeStatus, _> { s -> s.thermometerPrefs.powerMode } },
                    ),
                )
            }

            withContext(Dispatchers.Main.immediate) {
                completionHandler(result == CommandResult.SUCCESS)
            }
        }
    }

    /**
     * Not ported to [commandCoordinator]: Reset Thermometer (0x0A) starts a brand new cook
     * session each time it runs, clearing prediction and data buffers -- unlike
     * [resetFoodSafe]'s "reset to a fixed state," this is not idempotent. A spurious retry after
     * a lost ACK (not a real failure) would start a second new session on top of the first,
     * silently orphaning whatever the probe had already begun logging under the first one. There
     * is also no status field a reboot could confirm against in the first place.
     */
    fun resetProbe(completionHandler: (Boolean) -> Unit) {
        simulatedProbe?.sendResetProbe { status, _ ->
            completionHandler(status)
        } ?: run {
            // if there is a direct link to the probe, then use that
            arbitrator.directLink?.sendResetProbe { status, _ ->
                completionHandler(status)
            } ?: run {
                val nodeLinks = arbitrator.connectedNodeLinks
                if (nodeLinks.isNotEmpty()) {
                    val handled = AtomicBoolean(false)
                    val requestId = makeRequestId()
                    nodeLinks.forEach {
                        it.sendResetProbe(requestId) { status, _ ->
                            if (!handled.getAndSet(true)) {
                                completionHandler(status)
                            }
                        }
                    }

                } else {
                    completionHandler(false)
                }
            }
        }
    }

    /**
     * Sends via [commandCoordinator]. See [setPowerMode]'s KDoc for the Direct-vs-Node key
     * routing rationale and the null-vs-real request ID split, both of which apply identically
     * here.
     *
     * Deliberately does not optimistically write [probeHighLowAlarmStatus] into `_deviceFlow` on
     * completion -- see `EngineManager.setControlDevice`'s KDoc for why.
     */
    fun setProbeHighLowAlarmStatus(
        probeHighLowAlarmStatus: ProbeHighLowAlarmStatus,
        completionHandler: (Boolean) -> Unit,
    ) {
        val startingHighLowAlarmStatus = _deviceFlow.value.highLowAlarmStatus

        scope.launch {
            val result = getCommandMutex(MessageType.SET_PROBE_HIGH_LOW_ALARM).withLock {
                commandCoordinator.sendRoutedCommand(
                    targetSerialNumber = serialNumber,
                    send = {
                        val directLink = simulatedProbe ?: arbitrator.directLink
                        val nodeLinks = arbitrator.connectedNodeLinks
                        val nodeRequestId = makeRequestId()

                        val key = when {
                            directLink != null ->
                                CommandAttemptKey.Direct(
                                    MessageType.SET_PROBE_HIGH_LOW_ALARM,
                                    directLink.id,
                                )

                            nodeLinks.isNotEmpty() ->
                                CommandAttemptKey.Node(
                                    NodeMessageType.SET_PROBE_HIGH_LOW_ALARM,
                                    nodeRequestId,
                                )

                            else -> null
                        }
                        val onResponse: (Boolean, Any?) -> Unit = { success, _ ->
                            if (success && key != null) {
                                commandCoordinator.completeAttempt(key, success = true)
                            }
                        }

                        val sent = simulatedProbe?.sendSetProbeHighLowAlarmStatus(
                            probeHighLowAlarmStatus,
                            null,
                            onResponse,
                        ) ?: arbitrator.directLink?.sendSetProbeHighLowAlarmStatus(
                            probeHighLowAlarmStatus,
                            null,
                            onResponse,
                        ) ?: run {
                            if (nodeLinks.isEmpty()) {
                                null
                            } else {
                                nodeLinks.forEach { node ->
                                    node.sendSetProbeHighLowAlarmStatus(
                                        probeHighLowAlarmStatus,
                                        nodeRequestId,
                                        onResponse,
                                    )
                                }
                            }
                        }

                        if (sent != null && key != null) setOf(key) else emptySet()
                    },
                    isConfirmed = CommandCoordinator.valueConfirmation(
                        // Compares HighLowAlarmStatus.Threshold per sensor, not the full
                        // ProbeHighLowAlarmStatus -- AlarmStatus.tripped/alarming are live,
                        // device-computed flags, not something this command sets, so comparing
                        // them too would let an unrelated flip on ANY of the 11 sensors
                        // spuriously look like "the commanded value changed." See
                        // AlarmStatus.Threshold's KDoc.
                        startingValue = startingHighLowAlarmStatus?.thresholds.toExtractedValue(),
                        commandedValue = probeHighLowAlarmStatus.thresholds,
                        // Not extractedAs: a null probeHighLowAlarmStatus means the status packet
                        // didn't include those trailing bytes (older firmware/truncated relay,
                        // see ProbeStatus.fromRawData) -- never a legitimate "present but null"
                        // observation, so it must fall back to Absent. See configureFoodSafe's
                        // extractValue for the same reasoning.
                        extractValue = {
                            (it as? ProbeStatus)?.probeHighLowAlarmStatus?.let { v -> ExtractedValue.Present(v.thresholds) }
                                ?: ExtractedValue.Absent
                        },
                    ),
                )
            }

            withContext(Dispatchers.Main.immediate) {
                completionHandler(result == CommandResult.SUCCESS)
            }
        }
    }

    override fun sendLogRequest(startSequenceNumber: UInt, endSequenceNumber: UInt) {
        simulatedProbe?.sendLogRequest(startSequenceNumber, endSequenceNumber) {
            _logResponseFlow.emit(it)
        } ?: run {
            logTransferLink = arbitrator.preferredMeatNetLink
            logTransferLink?.sendLogRequest(startSequenceNumber, endSequenceNumber) {
                _logResponseFlow.emit(it)
            }
        }
    }

    private fun observe(base: ProbeBleDeviceBase) {
        if (base is ProbeBleDevice) {
            _deviceFlow.update {
                it.copy(
                    baseDevice = it.baseDevice.copy(
                        mac = base.mac,
                        productType = base.productType,
                    )
                )
            }
        }

        base.observeAdvertisingPackets(serialNumber, base.mac) { advertisement ->
            if (advertisement is ProbeAdvertisingData) {
                handleAdvertisingPackets(base, advertisement)
            }
        }
        base.observeConnectionState { state ->
            _deviceFlow.update { handleConnectionState(base, state, it) }
            _nodeConnectionFlow.emit(arbitrator.connectedNodeLinks.map { it.id }.toSet())
        }
        base.observeOutOfRange(OUT_OF_RANGE_TIMEOUT) {
            _deviceFlow.update { handleOutOfRange(it) }
        }
        (base as? RepeatedProbeBleDevice)?.observeMeatNetNodeTimeout(
            MEATNET_STATUS_NOTIFICATIONS_TIMEOUT_MS
        )
        base.observeProbeStatusUpdates(hopCount = base.hopCount) { status, hopCount ->
            handleProbeStatus(
                status,
                hopCount
            )
        }
        base.observeRemoteRssi { rssi ->
            _deviceFlow.update { handleRemoteRssi(base, rssi, it) }
        }
    }

    private fun monitorStatusNotifications() {
        addJob(
            serialNumber,
            scope.launch(
                CoroutineName("${serialNumber}.monitorStatusNotifications") + Dispatchers.IO
            ) {
                // Wait before starting to monitor prediction status, this allows for initial
                // connection time
                delay(STATUS_NOTIFICATIONS_POLL_DELAY_MS)

                while (isActive) {
                    delay(STATUS_NOTIFICATIONS_IDLE_POLL_RATE_MS)

                    val statusNotificationsStale =
                        statusNotificationsMonitor.isIdle(STATUS_NOTIFICATIONS_IDLE_TIMEOUT_MS)
                    val predictionStale =
                        predictionMonitor.isIdle(PREDICTION_IDLE_TIMEOUT_MS) && _deviceFlow.value.isPredicting
                    val shouldUpdate =
                        statusNotificationsStale != _deviceFlow.value.statusNotificationsStale ||
                                predictionStale != _deviceFlow.value.predictionStale

                    if (shouldUpdate) {
                        _deviceFlow.update {
                            it.copy(
                                statusNotificationsStale = statusNotificationsStale,
                                predictionStale = predictionStale
                            )
                        }
                    }
                }
            }
        )
    }

    private suspend fun handleProbeStatus(status: ProbeStatus, hopCount: UInt?) {
        handleStatusMutex.withLock {
            if (arbitrator.shouldUpdateDataFromStatus(status, sessionInfo, hopCount)) {
                Log.v(LOG_TAG, "ProbeManager.handleProbeStatus: $serialNumber $status")

                var updatedProbe = _deviceFlow.value.copy(hasReceivedStatus = true)

                updatedProbe = updateLink(updatedProbe)
                updatedProbe =
                    updateBatteryIdColor(status.batteryStatus, status.id, status.color, updatedProbe)
                updatedProbe = updateSequenceNumbers(
                    status.minSequenceNumber,
                    status.maxSequenceNumber,
                    updatedProbe
                )
                updatedProbe = updateThermometerPreferences(status.thermometerPrefs, updatedProbe)
                updatedProbe = updateHighLowAlarms(status.probeHighLowAlarmStatus, updatedProbe)

                if (status.mode == ProbeMode.NORMAL) {
                    updatedProbe = updateNormalMode(status, updatedProbe)

                    _normalModeProbeStatusFlow.emit(status)

                    // redundantly check for device information
                    fetchDeviceInfo()
                } else if (status.mode == ProbeMode.INSTANT_READ) {
                    updatedProbe = updateInstantRead(status, updatedProbe)
                }

                // These log-related items can be updated outside of this function--specifically, these
                // are updated by the LogManager when we emit a new status to the
                // normalModeProbeStatusFlow.
                _deviceFlow.update {
                    updatedProbe.copy(
                        uploadState = it.uploadState,
                        recordsDownloaded = it.recordsDownloaded,
                        logUploadPercent = it.logUploadPercent,
                    )
                }

                // Lets a pending setPowerMode/setProbeHighLowAlarmStatus/etc. retry loop complete
                // early from this status alone, even if its own response packet is lost -- see
                // CommandCoordinator.
                commandCoordinator.confirmCommandStatus(serialNumber, status)
            }
        }

        // optimize MeatNet connection resources.  if we have a direct link to a probe we want to
        // free the resource if we can reach the probe through MeatNet.  we avoid doing that
        // during a log record transfer because there is a fair amount of setup cost for that operation
        // so just let it run to completion before changing topology.
        arbitrator.directLink?.let { directLink ->
            if (uploadState !is ProbeUploadState.ProbeUploadInProgress && arbitrator.hasMeatNetRoute) {
                Log.i(
                    LOG_TAG,
                    "PM($serialNumber): disconnecting from ${directLink.productType}[${directLink.mac}] in favor of MeatNet connection(s)."
                )
                directLink.disconnect()
            }
        }
    }

    private fun handleAdvertisingPackets(
        device: ProbeBleDeviceBase,
        advertisement: ProbeAdvertisingData
    ) {
        Log.v(LOG_TAG, "ProbeManager.handleAdvertisingPackets($device, $advertisement)")
        val state = connectionState
        val networkIsAdvertisingAndNotConnected =
            (state == DeviceConnectionState.ADVERTISING_CONNECTABLE || state == DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE ||
                    state == DeviceConnectionState.CONNECTING)

        if (networkIsAdvertisingAndNotConnected) {
            val updatedProbe =
                if (arbitrator.shouldUpdateDataFromAdvertisingPacket(device, advertisement)) {
                    updateDataFromAdvertisement(advertisement, _deviceFlow.value)
                } else {
                    _deviceFlow.value
                }

            _deviceFlow.update {
                updatedProbe.copy(
                    baseDevice = _deviceFlow.value.baseDevice.copy(connectionState = state)
                )
            }
        }

        if (arbitrator.shouldConnect(device)) {
            Log.i(
                LOG_TAG,
                "PM($serialNumber) automatically connecting to ${device.id} (${device.productType}) on link ${device.linkId}"
            )
            device.connect()
        }
    }

    private fun handleConnectionState(
        device: ProbeBleDeviceBase,
        state: DeviceConnectionState,
        currentProbe: Probe,
    ): Probe {
        val isConnected = state == DeviceConnectionState.CONNECTED
        val isDisconnected = state == DeviceConnectionState.DISCONNECTED

        if (isConnected) {
            Log.i(LOG_TAG, "PM($serialNumber): ${device.productType}[${device.id}] is connected.")
            fetchDeviceInfo()
        }

        var updatedProbe = currentProbe

        if (isDisconnected) {
            Log.i(
                LOG_TAG,
                "PM($serialNumber): ${device.productType}[${device.id}] is disconnected."
            )

            // perform any cleanup
            device.disconnect()

            // if a probe, then reset the discover timestamp upon disconnection
            if (device is ProbeBleDevice) {
                arbitrator.directLinkDiscoverTimestamp = null
            }

            // Invalidate FW version so it's re-read on connection after DFU
            updatedProbe = updatedProbe.copy(
                baseDevice = updatedProbe.baseDevice.copy(
                    fwVersion = null
                )
            )

            // remove this item from the list of firmware details for the network
            dfuDisconnectedNodeCallback(device.id)

            // Purges any CommandAttemptKey.Direct entries tied to this link so a pending command
            // can still complete via a later retry over a different route (e.g. MeatNet), rather
            // than waiting on a response that can never arrive on this now-disconnected link.
            commandCoordinator.handleDeviceDisconnected(device.id)
        }

        // use the arbitrated connection state, fw version, hw revision, model information
        return updateConnectionState(updatedProbe)
    }

    private fun handleOutOfRange(currentProbe: Probe): Probe {
        // if the arbitrated connection state is out of range, then update.
        return if (connectionState == DeviceConnectionState.OUT_OF_RANGE) {
            currentProbe.copy(
                baseDevice = _deviceFlow.value.baseDevice.copy(
                    connectionState = DeviceConnectionState.OUT_OF_RANGE
                )
            )
        } else {
            currentProbe
        }
    }

    private fun handleRemoteRssi(
        device: ProbeBleDeviceBase,
        rssi: Int,
        currentProbe: Probe
    ): Probe {
        return if (arbitrator.shouldUpdateOnRemoteRssi(device)) {
            currentProbe.copy(baseDevice = currentProbe.baseDevice.copy(rssi = rssi))
        } else {
            currentProbe
        }
    }

    private fun fetchDeviceInfo() {
        fetchSessionInfo()
        fetchFirmwareVersion()
        fetchHardwareRevision()
        fetchModelInformation()
    }

    private fun fetchFirmwareVersion() {
        // if we don't know the probe's firmware version
        if (!_deviceFlow.value.fwVersion.isValid()) {

            // if direct link, then get the probe version over that link
            arbitrator.directLink?.readFirmwareVersionAsync { fwVersion ->
                // update firmware version on completion of read
                _deviceFlow.update {
                    it.copy(baseDevice = _deviceFlow.value.baseDevice.copy(fwVersion = fwVersion))
                }
            } ?: run {

                // otherwise, use MeatNet is there is a connection
                val nodeLinks = arbitrator.connectedNodeLinks
                if (nodeLinks.isNotEmpty()) {
                    val handled = AtomicBoolean(false)
                    val requestId = makeRequestId()

                    // for each MeatNet link, send the request
                    nodeLinks.forEach { device ->
                        device.readProbeFirmwareVersion(requestId) { version ->

                            // on first response from network, use the value
                            if (!handled.getAndSet(true)) {
                                _deviceFlow.update {
                                    it.copy(baseDevice = it.baseDevice.copy(fwVersion = version))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun fetchHardwareRevision() {
        // if we don't know the probe's hardware revision
        if (_deviceFlow.value.hwRevision == null) {

            // if direct link, then get the probe revision over that link
            arbitrator.directLink?.readHardwareRevisionAsync { hwRevision ->

                // update firmware version on completion of read
                _deviceFlow.update {
                    it.copy(baseDevice = it.baseDevice.copy(hwRevision = hwRevision))
                }
            } ?: run {

                // otherwise, use MeatNet is there is a connection
                val nodeLinks = arbitrator.connectedNodeLinks
                if (nodeLinks.isNotEmpty()) {
                    val handled = AtomicBoolean(false)
                    val requestId = makeRequestId()

                    // for each MeatNet link, send the request
                    nodeLinks.forEach { device ->
                        device.readProbeHardwareRevision(requestId) { revision ->

                            // on first response from network, use the value
                            if (!handled.getAndSet(true)) {
                                _deviceFlow.update {
                                    it.copy(
                                        baseDevice = it.baseDevice.copy(hwRevision = revision)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun fetchModelInformation() {
        // if we don't know the probe's model information
        if (_deviceFlow.value.modelInformation == null) {

            // if direct link, then get the probe model info over that link
            arbitrator.directLink?.readModelInformationAsync { info ->

                // update firmware version on completion of read
                _deviceFlow.update {
                    it.copy(
                        baseDevice = it.baseDevice.copy(modelInformation = info)
                    )
                }
            } ?: run {

                // otherwise, use MeatNet is there is a connection
                val nodeLinks = arbitrator.connectedNodeLinks
                if (nodeLinks.isNotEmpty()) {
                    val handled = AtomicBoolean(false)
                    val requestId = makeRequestId()

                    // for each MeatNet link, send the request
                    nodeLinks.forEach { device ->
                        device.readProbeModelInformation(requestId) { info ->

                            // on first response from network, use the value
                            if (!handled.getAndSet(true)) {
                                _deviceFlow.update {
                                    it.copy(baseDevice = it.baseDevice.copy(modelInformation = info))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun fetchSessionInfo() {
        simulatedProbe?.let { device ->
            if (sessionInfo == null) {
                // Same handleStatusMutex protection as handleSessionInfo() below -- this
                // completion callback writes sessionInfo/_deviceFlow and can otherwise race
                // handleProbeStatus(), which reads/writes the same state.
                device.sendSessionInformationRequest { status, info ->
                    if (status && info is SessionInformation) {
                        scope.launch {
                            handleStatusMutex.withLock {
                                sessionInfo = info
                                _deviceFlow.update { it.copy(sessionInfo = info) }
                            }
                        }
                    }
                }
            }
        } ?: run {
            // if direct link, then get the session info directly from the probe
            arbitrator.directLink?.sendSessionInformationRequest(null, ::handleSessionInfo)
                ?: run {
                    // otherwise, use MeatNet is there is a connection
                    val nodeLinks = arbitrator.connectedNodeLinks
                    if (nodeLinks.isNotEmpty()) {
                        val handled = AtomicBoolean(false)
                        val requestId = makeRequestId()
                        nodeLinks.forEach {
                            it.sendSessionInformationRequest(requestId) { b, any ->
                                // on first response from network, use the value
                                if (!handled.getAndSet(true)) {
                                    nodeLinks.forEach { link ->
                                        if (link != it) {
                                            link.cancelSessionInfoRequest(requestId)
                                        }
                                    }
                                    handleSessionInfo(b, any)
                                }
                            }
                        }
                    }
                }
        }
    }

    private fun handleSessionInfo(status: Boolean, any: Any?) {
        // if we've timed out waiting for the message, then use that state
        // to help us identify NO_ROUTE connection state.
        if (!status) {
            sessionInfoTimeout = true
            return
        }

        // This is a completion callback from an explicit session-info request (fetchSessionInfo,
        // called from fetchDeviceInfo()), fired from a UART completion handler that can run
        // concurrently with handleProbeStatus() -- both read/write sessionInfo, uploadState,
        // logTransferLink, and _deviceFlow.minSequence/maxSequence/sessionInfo, so this needs the
        // same handleStatusMutex protection handleProbeStatus() uses for that state. No
        // consumer-facing callback is invoked here (logTransferCompleteCallback is internal
        // bookkeeping only -- see LogManager), so unlike EngineManager.setControlDevice's
        // completionHandler, nothing here needs to land on a specific dispatcher.
        scope.launch {
            handleStatusMutex.withLock {
                sessionInfoTimeout = false
                val info = any as SessionInformation
                var minSequence = minSequenceNumber
                var maxSequence = maxSequenceNumber

                // if the session information has changed, then we need to finish the previous log session.
                if (sessionInfo != info) {
                    logTransferCompleteCallback()
                    logTransferLink = null
                    uploadState = ProbeUploadState.Unavailable

                    minSequence = null
                    maxSequence = null

                    Log.i(LOG_TAG, "PM($serialNumber): finished log transfer.")
                }

                sessionInfo = info
                _deviceFlow.update {
                    it.copy(
                        minSequence = minSequence,
                        maxSequence = maxSequence,
                        sessionInfo = info,
                    )
                }
            }
        }
    }

    private fun updateDataFromAdvertisement(
        advertisement: ProbeAdvertisingData,
        currentProbe: Probe,
    ): Probe {
        var updatedProbe = currentProbe.copy(
            baseDevice = _deviceFlow.value.baseDevice.copy(rssi = advertisement.rssi),
        )

        updatedProbe = when (advertisement.mode) {
            ProbeMode.INSTANT_READ -> {
                updateInstantRead(advertisement.probeTemperatures.values[0], updatedProbe)
            }

            ProbeMode.NORMAL -> {
                updateTemperatures(
                    advertisement.probeTemperatures,
                    advertisement.virtualSensors,
                    advertisement.overheatingSensors,
                    updatedProbe
                )
            }

            else -> {
                updatedProbe
            }
        }

        advertisement.thermometerPreferences?.let {
            updatedProbe = updatedProbe.copy(
                thermometerPrefs = it
            )
        }

        return updateBatteryIdColor(
            advertisement.batteryStatus,
            advertisement.probeID,
            advertisement.color,
            updatedProbe,
        )
    }

    private fun updateNormalMode(status: ProbeStatus, currentProbe: Probe): Probe {
        statusNotificationsMonitor.activity()
        predictionMonitor.activity()

        var updatedProbe = currentProbe

        updatedProbe = updateConnectionState(updatedProbe)
        updatedProbe = updateTemperatures(
            status.temperatures,
            status.virtualSensors,
            status.overheatingSensors,
            updatedProbe
        )
        predictionManager.updatePredictionStatus(
            predictionInfo = PredictionManager.PredictionInfo.fromPredictionStatus(
                status.predictionStatus
            ),
            sequenceNumber = status.maxSequenceNumber
        )?.let { predictionInfo ->
            updatedProbe = updatePredictionInfo(predictionInfo, updatedProbe)
        }
        updatedProbe = updateFoodSafe(status.foodSafeData, status.foodSafeStatus, updatedProbe)

        return updatedProbe
    }

    private fun updateConnectionState(currentProbe: Probe): Probe {
        return currentProbe.copy(
            baseDevice = _deviceFlow.value.baseDevice.copy(
                connectionState = connectionState,
                fwVersion = fwVersion,
                hwRevision = hwRevision,
                modelInformation = modelInformation
            )
        )
    }

    private fun updateInstantRead(
        value: Double?,
        currentProbe: Probe,
    ): Probe {
        instantReadFilter.addReading(value)
        val probe = currentProbe.copy(
            instantReadCelsius = instantReadFilter.values?.first,
            instantReadFahrenheit = instantReadFilter.values?.second,
            instantReadRawCelsius = value
        )
        instantReadMonitor.activity()

        return probe
    }

    private fun updateInstantRead(status: ProbeStatus, currentProbe: Probe) =
        updateInstantRead(status.temperatures.values[0], currentProbe)

    private fun updatePredictionInfo(
        predictionInfo: PredictionManager.PredictionInfo?,
        currentProbe: Probe,
    ): Probe {
        return currentProbe.copy(
            predictionState = predictionInfo?.predictionState,
            predictionMode = predictionInfo?.predictionMode,
            predictionType = predictionInfo?.predictionType,
            setPointTemperatureCelsius = predictionInfo?.setPointTemperature,
            heatStartTemperatureCelsius = predictionInfo?.heatStartTemperature,
            rawPredictionSeconds = predictionInfo?.rawPredictionSeconds,
            predictionSeconds = predictionInfo?.linearizedProgress?.secondsRemaining,
            estimatedCoreCelsius = predictionInfo?.estimatedCoreTemperature
        )
    }

    private fun updateTemperatures(
        temperatures: ProbeTemperatures,
        sensors: ProbeVirtualSensors,
        overheatingSensors: OverheatingSensors,
        currentProbe: Probe,
    ): Probe {
        var probe = currentProbe.copy(
            temperaturesCelsius = temperatures,
            virtualSensors = sensors,
            coreTemperatureCelsius = temperatures.coreTemperatureCelsius(sensors),
            surfaceTemperatureCelsius = temperatures.surfaceTemperatureCelsius(sensors),
            ambientTemperatureCelsius = temperatures.ambientTemperatureCelsius(sensors),
            overheatingSensors = overheatingSensors.values,
        )

        if (instantReadMonitor.isIdle(PROBE_INSTANT_READ_IDLE_TIMEOUT_MS)) {
            probe = probe.copy(
                instantReadCelsius = null,
                instantReadFahrenheit = null,
                instantReadRawCelsius = null,
            )
        }

        return probe
    }

    private fun updateFoodSafe(
        foodSafeData: FoodSafeData?,
        foodSafeStatus: FoodSafeStatus?,
        currentProbe: Probe,
    ): Probe {
        return currentProbe.copy(
            foodSafeData = foodSafeData,
            foodSafeStatus = foodSafeStatus,
        )
    }

    private fun updateThermometerPreferences(
        thermometerPrefs: ThermometerPreferences,
        currentProbe: Probe,
    ): Probe {
        return currentProbe.copy(
            thermometerPrefs = thermometerPrefs,
        )
    }

    private fun updateHighLowAlarms(
        probeHighLowAlarmStatus: ProbeHighLowAlarmStatus?,
        currentProbe: Probe,
    ): Probe {
        // ignore if probeHighLowAlarmStatus is null since possibly should be a nonnull value that was not supported by firmware
        return probeHighLowAlarmStatus?.let {
            currentProbe.copy(
                highLowAlarmStatus = it,
            )
        } ?: currentProbe
    }

    private fun updateLink(currentProbe: Probe): Probe {
        return arbitrator.preferredMeatNetLink?.let {
            currentProbe.copy(
                hopCount = it.hopCount,
                preferredLink = it.mac
            )
        } ?: run {
            currentProbe.copy(
                hopCount = 0u,
                preferredLink = ""
            )
        }
    }

    private fun updateBatteryIdColor(
        battery: ProbeBatteryStatus,
        id: ProbeID,
        color: ProbeColor,
        currentProbe: Probe,
    ): Probe {
        return currentProbe.copy(
            batteryStatus = battery,
            id = id,
            color = color
        )
    }

    private fun updateSequenceNumbers(
        minSequenceNumber: UInt,
        maxSequenceNumber: UInt,
        currentProbe: Probe,
    ): Probe {
        return currentProbe.copy(
            minSequence = minSequenceNumber,
            maxSequence = maxSequenceNumber
        )
    }
}