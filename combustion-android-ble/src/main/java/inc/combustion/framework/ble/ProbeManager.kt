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
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import inc.combustion.framework.InstantReadFilter
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.device.*
import inc.combustion.framework.ble.device.DeviceInformationBleDevice
import inc.combustion.framework.ble.device.ProbeBleDevice
import inc.combustion.framework.ble.device.ProbeBleDeviceBase
import inc.combustion.framework.ble.device.RepeatedProbeBleDevice
import inc.combustion.framework.ble.scanning.CombustionAdvertisingData
import inc.combustion.framework.ble.uart.LogResponse
import inc.combustion.framework.service.*
import inc.combustion.framework.service.utils.DefaultLinearizationTimerImpl
import inc.combustion.framework.service.utils.PredictionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*

/**
 * This class is responsible for managing and arbitrating the data links to a temperature
 * probe.  When MeatNet is enabled that includes data links through repeater devices over
 * MeatNet and direct links to temperature probes.  When MeatNet is disabled, this class
 * manages only direct links to temperature probes.  The class is responsible for presenting
 * a common interface over both scenarios.
 *
 * @property owner LifecycleOnwer for coroutine scope.
 * @property settings Service settings.
 * @constructor
 * Constructs a probe manager
 *
 * @param serialNumber The serial number of the probe being managed.
 */
internal class ProbeManager(
    serialNumber: String,
    private val owner: LifecycleOwner,
    private val settings: DeviceManager.Settings,
    private val dfuDisconnectedNodeCallback: (DeviceID) -> Unit
) {
    companion object {
        const val OUT_OF_RANGE_TIMEOUT = 15000L
        private const val PROBE_STATUS_NOTIFICATIONS_IDLE_POLL_RATE_MS = 1000L
        private const val PROBE_STATUS_NOTIFICATIONS_IDLE_TIMEOUT_MS = Probe.PROBE_STATUS_NOTIFICATIONS_IDLE_TIMEOUT_MS
        private const val PREDICTION_IDLE_TIMEOUT_MS = Probe.PREDICTION_IDLE_TIMEOUT_MS
        private const val PROBE_STATUS_NOTIFICATIONS_POLL_DELAY_MS = 30000L
        private const val PROBE_INSTANT_READ_IDLE_TIMEOUT_MS = 5000L
        private const val IGNORE_PROBES = false
        private const val IGNORE_REPEATERS = false
    }

    // encapsulates logic for managing network data links
    private val arbitrator = DataLinkArbitrator(settings)

    // manages long-running coroutine scopes for data handling
    private val jobManager = JobManager()

    // idle monitors
    private val instantReadMonitor = IdleMonitor()
    private val statusNotificationsMonitor = IdleMonitor()
    private val predictionMonitor = IdleMonitor()

    // holds the current state and data for this probe
    private var _probe = MutableStateFlow(Probe.create(serialNumber = serialNumber))

    // the flow that is consumed to get state and date updates
    val probeFlow = _probe.asStateFlow()

    // the flow that produces ProbeStatus updates from MeatNet
    private val _normalModeProbeStatusFlow = MutableSharedFlow<ProbeStatus>(
        replay = 0, extraBufferCapacity = 10, BufferOverflow.DROP_OLDEST)

    // the flow that is consumed to get ProbeStatus updates from MeatNet
    val normalModeProbeStatusFlow = _normalModeProbeStatusFlow.asSharedFlow()

    // the flow that produces LogResponses from MeatNet
    private val _logResponseFlow = MutableSharedFlow<LogResponse>(
        replay = 0, extraBufferCapacity = 50, BufferOverflow.SUSPEND)

    // tracks the link that is processing the most recent log transfer
    private var logTransferLink: ProbeBleDeviceBase? = null

    // tracks if we've run into a message timeout condition getting
    // session info
    private var sessionInfoTimeout: Boolean = false

    // the flow that is consumed to get LogResponses from MeatNet
    val logResponseFlow = _logResponseFlow.asSharedFlow()

    // serial number of the probe that is being managed by this manager
    val serialNumber: String
        get() {
            return _probe.value.serialNumber
        }

    // current upload state for this probe, determined by LogManager
    var uploadState: ProbeUploadState
        get() {
            return _probe.value.uploadState
        }
        set(value) {
            if(value != _probe.value.uploadState) {
                _probe.update { it.copy(uploadState = value) }
            }
        }

    var recordsDownloaded: Int
        get() {
            return _probe.value.recordsDownloaded
        }
        set(value) {
            if(value != _probe.value.recordsDownloaded) {
                _probe.update { it.copy(recordsDownloaded = value) }
            }
        }

    var logUploadPercent: UInt
        get() {
            return _probe.value.logUploadPercent
        }
        set(value) {
            if(value != _probe.value.logUploadPercent) {
                _probe.update { it.copy(logUploadPercent = value) }
            }
        }

    val connectionState: DeviceConnectionState
        get() {
            // if this is a simulated probe, no need to arbitrate
            simulatedProbe?.let {
                return it.connectionState
            }

            // if not using meatnet, then we use the direct link
            if(!settings.meatNetEnabled) {
                return arbitrator.probeBleDevice?.connectionState ?: DeviceConnectionState.OUT_OF_RANGE
            }

            // if the MeatNet preferred link is the direct link, then just use the direct link's state.
            if(arbitrator.directLink == arbitrator.preferredMeatNetLink) {
                arbitrator.directLink?.let {
                    return it.connectionState
                }
            }

            // if all devices are out of range, then connection state is Out of Range
            if(arbitrator.meatNetIsOutOfRange) {
                return DeviceConnectionState.OUT_OF_RANGE
            }

            // else, if there is any device that is connected
            arbitrator.getPreferredConnectionState(DeviceConnectionState.CONNECTED)?.let {
                // and we have run into a session info message timeout condition, then we are
                // in a NO_ROUTE scenario.  exclude when we are actively uploading, because we know that
                // can saturate the network and responses might be dropped.
                if(sessionInfoTimeout && (uploadState !is ProbeUploadState.ProbeUploadInProgress)) {
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
            arbitrator.getPreferredConnectionState(DeviceConnectionState.ADVERTISING_CONNECTABLE)?.let {
                return DeviceConnectionState.ADVERTISING_CONNECTABLE
            }

            // else, if there is any device that is advertising not connectable
            arbitrator.getPreferredConnectionState(DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE)?.let {
                return DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE
            }

            // else, all MeatNet devices are connected with no route, then the state is no route
            return DeviceConnectionState.NO_ROUTE
        }

   private val fwVersion: FirmwareVersion?
        get() {
            simulatedProbe?.let { return it.deviceInfoFirmwareVersion }

            arbitrator.probeBleDevice?.let {
                if(it.deviceInfoFirmwareVersion != null) {
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

            arbitrator.probeBleDevice?.let {
                if(it.deviceInfoHardwareRevision != null) {
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

            arbitrator.probeBleDevice?.let {
                if(it.deviceInfoModelInformation != null) {
                    return it.deviceInfoModelInformation
                }
            }

            return arbitrator.repeatedProbeBleDevices.firstOrNull {
                it.deviceInfoModelInformation != null
            }?.deviceInfoModelInformation
        }

    val probe: Probe
        get() {
            return _probe.value
        }

    // current session information for the probe
    var sessionInfo: SessionInformation? = null
        private set

    // current minimum sequence number for the probe
    val minSequenceNumber: UInt?
        get() {
            return _probe.value.minSequence
        }

    // current maximum sequence number for the probe
    val maxSequenceNumber: UInt?
        get() {
            return _probe.value.maxSequence
        }

    // signals when logs are no longer being added to LogManager.
    var logTransferCompleteCallback: () -> Unit = { }

    private val predictionManager = PredictionManager(DefaultLinearizationTimerImpl())

    private val instantReadFilter = InstantReadFilter()

    init {
        predictionManager.setPredictionCallback { prediction, shouldUpdateStatus ->
            if (shouldUpdateStatus) {
                _probe.update { updatePredictionInfo(prediction, it) }
            }
        }

        monitorStatusNotifications()
    }

    fun addJob(serialNumber: String, job: Job) = jobManager.addJob(serialNumber, job)

    fun addProbe(probe: ProbeBleDevice, baseDevice: DeviceInformationBleDevice, advertisement: CombustionAdvertisingData) {
        if(IGNORE_PROBES) return

        if(arbitrator.addProbe(probe, baseDevice)) {
            handleAdvertisingPackets(probe, advertisement)
            observe(probe)
            Log.i(LOG_TAG, "PM($serialNumber) is managing link ${probe.linkId}")
        }
    }

    fun addRepeatedProbe(repeatedProbe: RepeatedProbeBleDevice, repeater: DeviceInformationBleDevice, advertisement: CombustionAdvertisingData) {
        if(IGNORE_REPEATERS) return

        if(arbitrator.addRepeatedProbe(repeatedProbe, repeater))  {
            handleAdvertisingPackets(repeatedProbe, advertisement)
            observe(repeatedProbe)
            Log.i(LOG_TAG, "PM($serialNumber) is managing link ${repeatedProbe.linkId}")
        }
    }

    private var simulatedProbe: SimulatedProbeBleDevice? = null

    fun addSimulatedProbe(simProbe: SimulatedProbeBleDevice) {
        simulatedProbe ?: run {
            var updatedProbe = _probe.value

            // process simulated device status notifications
            simProbe.observeProbeStatusUpdates { status ->
                if(status.mode == ProbeMode.NORMAL) {
                    updatedProbe = updateNormalMode(status, updatedProbe)
                    _normalModeProbeStatusFlow.emit(status)
                }
                else if(status.mode == ProbeMode.INSTANT_READ) {
                    updatedProbe = updateInstantRead(status, updatedProbe)
                }
            }

            // process simulated connection state changes
            simProbe.observeConnectionState { state ->
                updatedProbe = handleConnectionState(simProbe, state, updatedProbe)
            }

            // process simulated out of range
            simProbe.observeOutOfRange(OUT_OF_RANGE_TIMEOUT){
                updatedProbe = handleOutOfRange(updatedProbe)
            }

            // process simulated advertising packets
            simProbe.observeAdvertisingPackets(serialNumber, simProbe.mac) { advertisement ->
                updatedProbe = updateDataFromAdvertisement(advertisement, updatedProbe)
                if(settings.autoReconnect && simProbe.shouldConnect) {
                    simProbe.connect()
                }
            }

            // process simulated rssi
            simProbe.observeRemoteRssi { rssi ->
                updatedProbe = updatedProbe.copy(baseDevice = _probe.value.baseDevice.copy(rssi = rssi))
            }

            simulatedProbe = simProbe

            _probe.update {
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
        ).forEach {node ->
            Log.d(LOG_TAG, "ProbeManager: disconnecting $node")

            // Since this is an explicit disconnect, we want to also disable the auto-reconnect flag
            arbitrator.setShouldAutoReconnect(false)
            node.disconnect()
        }

        arbitrator.directLinkDiscoverTimestamp = null

        simulatedProbe?.shouldConnect = false
        simulatedProbe?.disconnect()
    }

    fun setProbeColor(color: ProbeColor, completionHandler: (Boolean) -> Unit) {
        simulatedProbe?.sendSetProbeColor(color) { status, _ ->
            completionHandler(status)
        } ?: run {
            // Note: not supported by MeatNet
            arbitrator.directLink?.sendSetProbeColor(color) { status, _ ->
                completionHandler(status)
            } ?: run {
                completionHandler(false)
            }
        }
    }

    fun setProbeID(id: ProbeID, completionHandler: (Boolean) -> Unit) {
        simulatedProbe?.sendSetProbeID(id) { status, _ ->
            completionHandler(status)
        } ?: run {
            // Note: not supported by MeatNet
            arbitrator.directLink?.sendSetProbeID(id) { status, _ ->
                completionHandler(status)
            } ?: run {
                completionHandler(false)
            }
        }
    }

    fun setPrediction(removalTemperatureC: Double, mode: ProbePredictionMode, completionHandler: (Boolean) -> Unit) {
        simulatedProbe?.sendSetPrediction(removalTemperatureC, mode) { status, _ ->
            completionHandler(status)
        } ?: run {
            // if there is a direct link to the probe, then use that
            arbitrator.directLink?.sendSetPrediction(removalTemperatureC, mode) { status, _ ->
                completionHandler(status)
            } ?: run {
                val nodeLinks = arbitrator.connectedNodeLinks
                if(nodeLinks.isNotEmpty()) {
                    var handled = false
                    val requestId = makeRequestId()
                    nodeLinks.forEach {
                        it.sendSetPrediction(removalTemperatureC, mode, requestId) {status, _ ->
                            if(!handled) {
                                handled = true
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

    fun configureFoodSafe(foodSafeData: FoodSafeData, completionHandler: (Boolean) -> Unit) {
        simulatedProbe?.sendConfigureFoodSafe(foodSafeData) { status, _ ->
            completionHandler(status)
        } ?: run {
            // if there is a direct link to the probe, then use that
            arbitrator.directLink?.sendConfigureFoodSafe(foodSafeData) { status, _ ->
                completionHandler(status)
            } ?: run {
                val nodeLinks = arbitrator.connectedNodeLinks
                if(nodeLinks.isNotEmpty()) {
                    var handled = false
                    val requestId = makeRequestId()
                    nodeLinks.forEach {
                        it.sendConfigureFoodSafe(foodSafeData, requestId) {status, _ ->
                            if(!handled) {
                                handled = true
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

    fun resetFoodSafe(completionHandler: (Boolean) -> Unit) {
        simulatedProbe?.sendResetFoodSafe() { status, _ ->
            completionHandler(status)
        } ?: run {
            // if there is a direct link to the probe, then use that
            arbitrator.directLink?.sendResetFoodSafe() { status, _ ->
                completionHandler(status)
            } ?: run {
                val nodeLinks = arbitrator.connectedNodeLinks
                if(nodeLinks.isNotEmpty()) {
                    var handled = false
                    val requestId = makeRequestId()
                    nodeLinks.forEach {
                        it.sendResetFoodSafe(requestId) {status, _ ->
                            if(!handled) {
                                handled = true
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

    fun sendLogRequest(startSequenceNumber: UInt, endSequenceNumber: UInt) {
        simulatedProbe?.sendLogRequest(startSequenceNumber, endSequenceNumber) {
            _logResponseFlow.emit(it)
        } ?: run {
            logTransferLink = arbitrator.preferredMeatNetLink
            logTransferLink?.sendLogRequest(startSequenceNumber, endSequenceNumber) {
                _logResponseFlow.emit(it)
            }
        }
    }

    fun finish(deviceIdsToDisconnect: Set<DeviceID>? = null) {
        Log.d(LOG_TAG, "ProbeManager.finish($deviceIdsToDisconnect) for ($serialNumber)")

        arbitrator.finish(
            nodeAction = {
                // There's a couple specific things that come out of unlinking a probe that need to
                // be addressed here:
                //
                // - Jobs are created on repeated probes (nodes) that need to be cancelled so that
                //   we don't continue to obtain data for probes that we're disconnected from. If
                //   all jobs on a node are blindly cancelled, then we'll likely cancel jobs that
                //   are still needed for other probes connected to this node. The [jobKey]
                //   parameter allows for selective cancellation of jobs.
                // - On a related note, we need to be able to selectively disconnect from nodes as
                //   some are still providing data from other probes.
                it.finish(
                    jobKey = serialNumber,
                    disconnect = deviceIdsToDisconnect?.contains(it.id) ?: true
                )
            },
            directConnectionAction = {
                it.disconnect()
            }
        )

        jobManager.cancelJobs()
    }

    private fun observe(base: ProbeBleDeviceBase) {
        if(base is ProbeBleDevice) {
            _probe.update {
                it.copy(
                    baseDevice = it.baseDevice.copy(
                        mac = base.mac,
                        productType = base.productType,
                    )
                )
            }
        }

        base.observeAdvertisingPackets(serialNumber, base.mac) { advertisement ->
            handleAdvertisingPackets(base, advertisement)
        }
        base.observeConnectionState { state ->
            _probe.update { handleConnectionState(base, state, it) }
        }
        base.observeOutOfRange(OUT_OF_RANGE_TIMEOUT) {
            _probe.update { handleOutOfRange(it) }
        }
        base.observeProbeStatusUpdates { status -> handleProbeStatus(status) }
        base.observeRemoteRssi { rssi ->
            _probe.update { handleRemoteRssi(base, rssi, it) }
        }
    }

    private fun monitorStatusNotifications() {
        jobManager.addJob(
            key = serialNumber,
            job = owner.lifecycleScope.launch(
                CoroutineName("${serialNumber}.monitorStatusNotifications") + Dispatchers.IO
            ) {
                // Wait before starting to monitor prediction status, this allows for initial
                // connection time
                delay(PROBE_STATUS_NOTIFICATIONS_POLL_DELAY_MS)

                while(isActive) {
                    delay(PROBE_STATUS_NOTIFICATIONS_IDLE_POLL_RATE_MS)

                    val statusNotificationsStale = statusNotificationsMonitor.isIdle(PROBE_STATUS_NOTIFICATIONS_IDLE_TIMEOUT_MS)
                    val predictionStale = predictionMonitor.isIdle(PREDICTION_IDLE_TIMEOUT_MS) && _probe.value.isPredicting
                    val shouldUpdate = statusNotificationsStale != _probe.value.statusNotificationsStale ||
                            predictionStale != _probe.value.predictionStale

                    if(shouldUpdate) {
                        _probe.update {
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

    private suspend fun handleProbeStatus(status: ProbeStatus) {
        if(arbitrator.shouldUpdateDataFromProbeStatus(status, sessionInfo)) {
            Log.v(LOG_TAG, "ProbeManager.handleProbeStatus: $serialNumber $status")

            var updatedProbe = _probe.value

            updatedProbe = updateLink(updatedProbe)
            updatedProbe = updateBatteryIdColor(status.batteryStatus, status.id, status.color, updatedProbe)
            updatedProbe = updateSequenceNumbers(status.minSequenceNumber, status.maxSequenceNumber, updatedProbe)

            if(status.mode == ProbeMode.NORMAL) {
                updatedProbe = updateNormalMode(status, updatedProbe)

                _normalModeProbeStatusFlow.emit(status)

                // redundantly check for device information
                fetchDeviceInfo()
            }
            else if(status.mode == ProbeMode.INSTANT_READ) {
                updatedProbe = updateInstantRead(status, updatedProbe)
            }

            _probe.update { updatedProbe }
        }

        // optimize MeatNet connection resources.  if we have a direct link to a probe we want to
        // free the resource if we can reach the probe through MeatNet.  we avoid doing that
        // during a log record transfer because there is a fair amount of setup cost for that operation
        // so just let it run to completion before changing topology.
        arbitrator.directLink?.let { directLink ->
            if(uploadState !is ProbeUploadState.ProbeUploadInProgress && arbitrator.hasMeatNetRoute) {
                Log.i(LOG_TAG, "PM($serialNumber): disconnecting from ${directLink.productType}[${directLink.mac}] in favor of MeatNet connection(s).")
                directLink.disconnect()
            }
        }
    }

    private fun handleAdvertisingPackets(device: ProbeBleDeviceBase, advertisement: CombustionAdvertisingData) {
        Log.v(LOG_TAG, "ProbeManager.handleAdvertisingPackets($device, $advertisement)")
        val state = connectionState
        val networkIsAdvertisingAndNotConnected =
            (state == DeviceConnectionState.ADVERTISING_CONNECTABLE || state == DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE ||
                    state == DeviceConnectionState.CONNECTING)

        if(networkIsAdvertisingAndNotConnected) {
            val updatedProbe = if(arbitrator.shouldUpdateDataFromAdvertisingPacket(device, advertisement)) {
                updateDataFromAdvertisement(advertisement, _probe.value)
            } else {
                _probe.value
            }

            _probe.update {
                updatedProbe.copy(
                    baseDevice = _probe.value.baseDevice.copy(connectionState = state)
                )
            }
        }

        if(arbitrator.shouldConnect(device)) {
            Log.i(LOG_TAG, "PM($serialNumber) automatically connecting to ${device.id} (${device.productType}) on link ${device.linkId}")
            device.connect()
        }
    }

    private fun handleConnectionState(
        device: ProbeBleDeviceBase,
        state: DeviceConnectionState,
        currentProbe: Probe
    ): Probe {
        val isConnected = state == DeviceConnectionState.CONNECTED
        val isDisconnected = state == DeviceConnectionState.DISCONNECTED

        if(isConnected) {
            Log.i(LOG_TAG, "PM($serialNumber): ${device.productType}[${device.id}] is connected.")
            fetchDeviceInfo()
        }

        var updatedProbe = currentProbe

        if(isDisconnected) {
            Log.i(LOG_TAG, "PM($serialNumber): ${device.productType}[${device.id}] is disconnected.")

            // perform any cleanup
            device.disconnect()

            // if a probe, then reset the discover timestamp upon disconnection
            if(device is ProbeBleDevice) {
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
        }

        // use the arbitrated connection state, fw version, hw revision, model information
        return updateConnectionState(updatedProbe)
    }

    private fun handleOutOfRange(currentProbe: Probe): Probe {
        // if the arbitrated connection state is out of range, then update.
        return if(connectionState == DeviceConnectionState.OUT_OF_RANGE) {
            currentProbe.copy(
                baseDevice = _probe.value.baseDevice.copy(
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
        return if(arbitrator.shouldUpdateOnRemoteRssi(device)) {
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
        if(_probe.value.fwVersion == null) {

            // if direct link, then get the probe version over that link
            arbitrator.directLink?.readProbeFirmwareVersion { fwVersion ->
                // update firmware version on completion of read
                _probe.update {
                    it.copy(baseDevice = _probe.value.baseDevice.copy(fwVersion = fwVersion))
                }
            } ?: run {

                // otherwise, use MeatNet is there is a connection
                val nodeLinks = arbitrator.connectedNodeLinks
                if(nodeLinks.isNotEmpty()) {
                    var handled = false
                    val requestId = makeRequestId()

                    // for each MeatNet link, send the request
                    nodeLinks.forEach { device ->
                        device.readProbeFirmwareVersion(requestId) {version ->

                            // on first response from network, use the value
                            if(!handled) {
                                handled = true
                                _probe.update {
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
        if(_probe.value.hwRevision == null) {

            // if direct link, then get the probe revision over that link
            arbitrator.directLink?.readProbeHardwareRevision { hwRevision ->

                // update firmware version on completion of read
                _probe.update {
                    it.copy(baseDevice = it.baseDevice.copy(hwRevision = hwRevision))
                }
            } ?: run {

                // otherwise, use MeatNet is there is a connection
                val nodeLinks = arbitrator.connectedNodeLinks
                if(nodeLinks.isNotEmpty()) {
                    var handled = false
                    val requestId = makeRequestId()

                    // for each MeatNet link, send the request
                    nodeLinks.forEach { device ->
                        device.readProbeHardwareRevision(requestId) { revision ->

                            // on first response from network, use the value
                            if(!handled) {
                                handled = true
                                _probe.update {
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
        if(_probe.value.modelInformation == null) {

            // if direct link, then get the probe model info over that link
            arbitrator.directLink?.readProbeModelInformation { info ->

                // update firmware version on completion of read
                _probe.update {
                    it.copy(
                        baseDevice = it.baseDevice.copy(modelInformation = info)
                    )
                }
            } ?: run {

                // otherwise, use MeatNet is there is a connection
                val nodeLinks = arbitrator.connectedNodeLinks
                if(nodeLinks.isNotEmpty()) {
                    var handled = false
                    val requestId = makeRequestId()

                    // for each MeatNet link, send the request
                    nodeLinks.forEach { device ->
                        device.readProbeModelInformation(requestId) { info ->

                            // on first response from network, use the value
                            if(!handled) {
                                handled = true
                                _probe.update {
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
            if(sessionInfo == null) {
                device.sendSessionInformationRequest { status, info ->
                    if(status && info is SessionInformation) {
                        sessionInfo = info
                        _probe.update { it.copy(sessionInfo = info) }
                    }
                }
            }
        } ?: run {
            // if direct link, then get the session info directly from the probe
            arbitrator.directLink?.sendSessionInformationRequest(null, ::handleSessionInfo)
            ?: run {
                // otherwise, use MeatNet is there is a connection
                val nodeLinks = arbitrator.connectedNodeLinks
                if(nodeLinks.isNotEmpty()) {
                    var handled = false
                    val requestId = makeRequestId()
                    nodeLinks.forEach {
                        it.sendSessionInformationRequest(requestId) { b, any ->
                            // on first response from network, use the value
                            if(!handled) {
                                handled = true
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
        if(!status) {
            sessionInfoTimeout = true
            return
        }

        sessionInfoTimeout = false
        val info = any as SessionInformation
        var minSequence = minSequenceNumber
        var maxSequence = maxSequenceNumber

        // if the session information has changed, then we need to finish the previous log session.
        if(sessionInfo != info) {
            logTransferCompleteCallback()
            logTransferLink = null
            uploadState = ProbeUploadState.Unavailable

            minSequence = null
            maxSequence = null

            Log.i(LOG_TAG, "PM($serialNumber): finished log transfer.")
        }

        sessionInfo = info
        _probe.update {
            it.copy(
                minSequence = minSequence,
                maxSequence = maxSequence,
                sessionInfo = info,
            )
        }
    }

    private fun updateDataFromAdvertisement(
        advertisement: CombustionAdvertisingData,
        currentProbe: Probe,
    ): Probe {
        var updatedProbe = currentProbe.copy(
            baseDevice = _probe.value.baseDevice.copy(rssi = advertisement.rssi),
        )

        updatedProbe = when (advertisement.mode) {
            ProbeMode.INSTANT_READ -> {
                updateInstantRead(advertisement.probeTemperatures.values[0], updatedProbe)
            }
            ProbeMode.NORMAL -> {
                updateTemperatures(advertisement.probeTemperatures, advertisement.virtualSensors, updatedProbe)
            }
            else -> {
                updatedProbe
            }
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
        updatedProbe = updateTemperatures(status.temperatures, status.virtualSensors, updatedProbe)
        val predictionInfo = predictionManager.updatePredictionStatus(
            predictionInfo = PredictionManager.PredictionInfo.fromPredictionStatus(
                status.predictionStatus
            ),
            sequenceNumber = status.maxSequenceNumber
        )
        updatedProbe = updatePredictionInfo(predictionInfo, updatedProbe)
        updatedProbe = updateFoodSafe(status.foodSafeData, status.foodSafeStatus, updatedProbe)

        return updatedProbe
    }

    private fun updateConnectionState(currentProbe: Probe): Probe {
        return currentProbe.copy(
            baseDevice = _probe.value.baseDevice.copy(
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
        currentProbe: Probe,
    ): Probe {
        var probe = currentProbe.copy(
            temperaturesCelsius = temperatures,
            virtualSensors = sensors,
            coreTemperatureCelsius = temperatures.coreTemperatureCelsius(sensors),
            surfaceTemperatureCelsius = temperatures.surfaceTemperatureCelsius(sensors),
            ambientTemperatureCelsius = temperatures.ambientTemperatureCelsius(sensors),
            overheatingSensors = temperatures.overheatingSensors,
        )

        if(instantReadMonitor.isIdle(PROBE_INSTANT_READ_IDLE_TIMEOUT_MS)) {
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

    private fun makeRequestId(): UInt {
        return (0u..UInt.MAX_VALUE).random()
    }
}