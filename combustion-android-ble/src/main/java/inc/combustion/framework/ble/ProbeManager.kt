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
    private val dfuConnectedNodeCallback: (FirmwareState.Node) -> Unit,
    private val dfuDisconnectedNodeCallback: (DeviceID) -> Unit
) {
    companion object {
        const val OUT_OF_RANGE_TIMEOUT = 15000L
        private const val PROBE_STATUS_NOTIFICATIONS_IDLE_POLL_RATE_MS = 1000L
        private const val PROBE_STATUS_NOTIFICATIONS_IDLE_TIMEOUT_MS = 15000L
        private const val PREDICTION_IDLE_TIMEOUT_MS = 60000L
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
    private val _probeStatusFlow = MutableSharedFlow<ProbeStatus>(
        replay = 0, extraBufferCapacity = 10, BufferOverflow.DROP_OLDEST)

    // the flow that is consumed to get ProbeStatus updates from MeatNet
    val probeStatusFlow = _probeStatusFlow.asSharedFlow()

    // the flow that produces LogResponses from MeatNet
    private val _logResponseFlow = MutableSharedFlow<LogResponse>(
        replay = 0, extraBufferCapacity = 50, BufferOverflow.SUSPEND)

    // tracks the link that is processing the most recent log transfer
    private var logTransferLink: ProbeBleDeviceBase? = null

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
                _probe.value = _probe.value.copy(uploadState = value)
            }
        }

    var recordsDownloaded: Int
        get() {
            return _probe.value.recordsDownloaded
        }
        set(value) {
            if(value != _probe.value.recordsDownloaded) {
                _probe.value = _probe.value.copy(recordsDownloaded = value)
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

            // if all devices are out of range, then connection state is Out of Range
            if(arbitrator.meatNetIsOutOfRange) {
                return DeviceConnectionState.OUT_OF_RANGE
            }

            // else, if have a preferred meatnet link (connected with route), then use that connection state
            arbitrator.preferredMeatNetLink?.let {
                return it.connectionState
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
    val minSequenceNumber: UInt
        get() {
            return _probe.value.minSequenceNumber
        }

    // current maximum sequence number for the probe
    val maxSequenceNumber: UInt
        get() {
            return _probe.value.maxSequenceNumber
        }

    // signals when logs are no longer being added to LogManager.
    var logTransferCompleteCallback: () -> Unit = { }

    private val predictionManager = PredictionManager()

    private val instantReadFilter = InstantReadFilter()

    init {
        predictionManager.setPredictionCallback {
            updatePredictionInfo(it)
        }

        monitorStatusNotifications()
    }

    fun addJob(job: Job) = jobManager.addJob(job)

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

            // process simulated device status notifications
            simProbe.observeProbeStatusUpdates { status ->
                updateDataFromProbeStatus(status)
                _probeStatusFlow.emit(status)
            }

            // process simulated connection state changes
            simProbe.observeConnectionState { state ->
                handleConnectionState(simProbe, state)
            }

            // process simulated out of range
            simProbe.observeOutOfRange(OUT_OF_RANGE_TIMEOUT){
                handleOutOfRange()
            }

            // process simulated advertising packets
            simProbe.observeAdvertisingPackets(serialNumber, simProbe.mac) { advertisement ->
                updateDataFromAdvertisement(advertisement)
                if(settings.autoReconnect && simProbe.shouldConnect) {
                    simProbe.connect()
                }
            }

            // process simulated rssi
            simProbe.observeRemoteRssi { rssi ->
                _probe.value = _probe.value.copy(baseDevice = _probe.value.baseDevice.copy(rssi = rssi))
            }

            simulatedProbe = simProbe

            _probe.value = _probe.value.copy(baseDevice = _probe.value.baseDevice.copy(mac = simProbe.mac))
        }
    }

    fun connect() {
        arbitrator.getNodesNeedingConnection(true).forEach { node ->
            node.connect()
        }

        simulatedProbe?.shouldConnect = true
        simulatedProbe?.connect()
    }

    fun disconnect() {
        arbitrator.getNodesNeedingDisconnect(true).forEach {node ->
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
            arbitrator.preferredMeatNetLink?.sendSetPrediction(removalTemperatureC, mode) { status, _ ->
                completionHandler(status)
            } ?: run {
                completionHandler(false)
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

    fun finish() {
        arbitrator.finish()
        jobManager.cancelJobs()
    }

    private fun observe(base: ProbeBleDeviceBase) {
        if(base is ProbeBleDevice) {
            _probe.value = _probe.value.copy(baseDevice = _probe.value.baseDevice.copy(mac = base.mac))
        }

        base.observeAdvertisingPackets(serialNumber, base.mac) { advertisement -> handleAdvertisingPackets(base, advertisement) }
        base.observeConnectionState { state -> handleConnectionState(base, state) }
        base.observeOutOfRange(OUT_OF_RANGE_TIMEOUT){ handleOutOfRange() }
        base.observeProbeStatusUpdates { status -> handleProbeStatus(base, status) }
        base.observeRemoteRssi { rssi ->  handleRemoteRssi(base, rssi) }
    }

    private fun monitorStatusNotifications() {
        jobManager.addJob(owner.lifecycleScope.launch(Dispatchers.IO) {
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
                    _probe.value = _probe.value.copy(
                        statusNotificationsStale = statusNotificationsStale,
                        predictionStale = predictionStale
                    )
                }
            }
        })
    }

    private suspend fun handleProbeStatus(device: ProbeBleDeviceBase, status: ProbeStatus) {
        if(arbitrator.shouldUpdateDataFromProbeStatus(device)) {
            updateDataFromProbeStatus(status)

            // redundantly check for device information, if we don't have that data, then we
            // want to keep requesting it until it makes it across the network.
            fetchDeviceInfo(device)
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

        _probeStatusFlow.emit(status)
    }

    private fun handleAdvertisingPackets(device: ProbeBleDeviceBase, advertisement: CombustionAdvertisingData) {
        val state = connectionState
        val networkIsAdvertisingAndNotConnected =
            (state == DeviceConnectionState.ADVERTISING_CONNECTABLE || state == DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE ||
                    state == DeviceConnectionState.CONNECTING)

        if(networkIsAdvertisingAndNotConnected) {
            if(arbitrator.shouldUpdateDataFromAdvertisingPacket(device, advertisement)) {
                updateDataFromAdvertisement(advertisement)
            }

            _probe.value = _probe.value.copy(
                baseDevice = _probe.value.baseDevice.copy(connectionState = state)
            )
        }

        if(arbitrator.shouldConnect(device)) {
            Log.i(LOG_TAG, "PM($serialNumber) automatically connecting to ${device.id} (${device.productType}) on link ${device.linkId}")
            device.connect()
        }
    }

    private fun handleConnectionState(device: ProbeBleDeviceBase, state: DeviceConnectionState) {
        val isConnected = state == DeviceConnectionState.CONNECTED
        val isDisconnected = state == DeviceConnectionState.DISCONNECTED

        if(isConnected) {
            Log.i(LOG_TAG, "PM($serialNumber): ${device.productType}[${device.id}] is connected.")
            handleConnectedState(device)
        }

        if(isDisconnected) {
            Log.i(LOG_TAG, "PM($serialNumber): ${device.productType}[${device.id}] is disconnected.")

            // perform any cleanup
            device.disconnect()

            // if a probe, then reset the discover timestamp upon disconnection
            if(device is ProbeBleDevice) {
                arbitrator.directLinkDiscoverTimestamp = null
            }

            if(connectionState != DeviceConnectionState.CONNECTED) {
                Log.i(LOG_TAG, "PM($serialNumber): ${device.productType}[${device.id}] No longer connected!  Clearing session info!")

                sessionInfo = null

                if(uploadState != ProbeUploadState.Unavailable) {
                    finishLogTransfer()
                }
            }

            // remove this item from the list of firmware details for the network
            dfuDisconnectedNodeCallback(device.id)
        }

        // if we no longer have a UART route to the probe, then null SessionInfo so that it can
        // be requested/refreshed when a route is established.  We also no longer have a downstream
        // link to the probe, so finish of the log transfer.
        if(state == DeviceConnectionState.NO_ROUTE) {
            if(arbitrator.hasNoUartRoute) {
                Log.i(LOG_TAG, "PM($serialNumber): No UART route to probe.  Clearing session info.")

                sessionInfo = null

                if(uploadState != ProbeUploadState.Unavailable) {
                    finishLogTransfer()
                }
            }
        }

        // use the arbitrated connection state, fw version, hw revision, model information
        _probe.value = _probe.value.copy(
            baseDevice = _probe.value.baseDevice.copy(
                connectionState = connectionState,
                fwVersion = fwVersion,
                hwRevision = hwRevision,
                modelInformation = modelInformation
            ),
            // event out current session info and the preferred link
            sessionInfo = sessionInfo,
            preferredLink = arbitrator.preferredMeatNetLink?.mac ?: ""
        )
    }

    private fun finishLogTransfer() {
        logTransferCompleteCallback()

        logTransferLink = null

        uploadState = ProbeUploadState.Unavailable

        Log.i(LOG_TAG, "PM($serialNumber): finished log transfer.")
    }

    private fun handleOutOfRange() {
        // if the arbitrated connection state is out of range, then update.
        if(connectionState == DeviceConnectionState.OUT_OF_RANGE) {
            _probe.value = _probe.value.copy(
                baseDevice = _probe.value.baseDevice.copy(
                    connectionState = DeviceConnectionState.OUT_OF_RANGE
                )
            )
        }
    }

    private fun handleRemoteRssi(device: ProbeBleDeviceBase, rssi: Int) {
        if(arbitrator.shouldUpdateOnRemoteRssi(device)) {
            _probe.value = _probe.value.copy(baseDevice = _probe.value.baseDevice.copy(rssi = rssi))
        }
    }

    private fun handleConnectedState(device: ProbeBleDeviceBase) {
        fetchDeviceInfo(device)
        fetchSessionInfo()
    }

    private fun fetchDeviceInfo(device: ProbeBleDeviceBase) {
        fetchFirmwareVersion(device)
        fetchSerialNumber(device)
        fetchHardwareRevision(device)
        fetchModelInformation(device)
    }

    private fun fetchFirmwareVersion(device: ProbeBleDeviceBase) {
        var didReadDeviceInfo = false
        owner.lifecycleScope.launch {
            device.deviceInfoFirmwareVersion ?: run {
                didReadDeviceInfo = true
                when(device) {
                    is ProbeBleDevice -> device.readFirmwareVersion()
                    is RepeatedProbeBleDevice -> device.readProbeFirmwareVersion()
                }
            }
        }.invokeOnCompletion {
            if(didReadDeviceInfo) {
                _probe.value = _probe.value.copy(baseDevice = _probe.value.baseDevice.copy(
                    fwVersion = fwVersion,
                ))

                device.deviceInfoFirmwareVersion?.let {
                    dfuConnectedNodeCallback(FirmwareState.Node(
                        id = device.id,
                        type = device.productType,
                        firmwareVersion = it
                    ))
                }
            }
        }
    }

    private fun fetchSerialNumber(device: ProbeBleDeviceBase) {
        owner.lifecycleScope.launch {
            device.deviceInfoSerialNumber ?: run {
                when (device) {
                    is ProbeBleDevice -> device.readSerialNumber()
                }
            }
        }
    }

    private fun fetchHardwareRevision(device: ProbeBleDeviceBase) {
        var didReadDeviceInfo = false
        owner.lifecycleScope.launch {
            device.deviceInfoHardwareRevision ?: run {
                didReadDeviceInfo = true
                when (device) {
                    is ProbeBleDevice -> device.readHardwareRevision()
                    is RepeatedProbeBleDevice -> device.readProbeHardwareRevision()
                }
            }
        }.invokeOnCompletion {
            if(didReadDeviceInfo) {
                _probe.value = _probe.value.copy(baseDevice = _probe.value.baseDevice.copy(
                    hwRevision = hwRevision,
                ))
            }
        }
    }

    private fun fetchModelInformation(device: ProbeBleDeviceBase) {
        var didReadDeviceInfo = false
        owner.lifecycleScope.launch {
            device.deviceInfoModelInformation ?: run {
                didReadDeviceInfo = true
                when (device) {
                    is ProbeBleDevice -> device.readModelInformation()
                    is RepeatedProbeBleDevice -> device.readProbeModelInformation()
                }
            }
        }.invokeOnCompletion {
            if(didReadDeviceInfo) {
                _probe.value = _probe.value.copy(baseDevice = _probe.value.baseDevice.copy(
                    modelInformation = modelInformation
                ))
            }
        }
    }

    fun fetchSessionInfo() {
        simulatedProbe?.let {
            if(sessionInfo == null) {
                it.sendSessionInformationRequest { status, info ->
                    if(status && info is SessionInformation) {
                        updateSessionInfo(info)
                    }
                }
            }
        } ?: run {
            arbitrator.preferredMeatNetLink?.let {
                if(sessionInfo == null) {
                    it.sendSessionInformationRequest { status, info ->
                        if(status && info is SessionInformation) {
                            updateSessionInfo(info)
                        }
                    }
                }
            }
        }
    }

    private fun updateDataFromAdvertisement(advertisement: CombustionAdvertisingData) {
        _probe.value = _probe.value.copy(
            baseDevice = _probe.value.baseDevice.copy(rssi = advertisement.rssi),
        )

        if(advertisement.mode == ProbeMode.INSTANT_READ) {
            updateInstantRead(advertisement.probeTemperatures.values[0])
        } else if(advertisement.mode == ProbeMode.NORMAL) {
            updateTemperatures(advertisement.probeTemperatures, advertisement.virtualSensors)
        }

        updateBatteryIdColor(advertisement.batteryStatus, advertisement.probeID, advertisement.color)
    }

    private fun updateDataFromProbeStatus(status: ProbeStatus) {
        if(status.mode == ProbeMode.INSTANT_READ) {
            updateInstantRead(status.temperatures.values[0])
        } else if(status.mode == ProbeMode.NORMAL) {
            statusNotificationsMonitor.activity()
            predictionMonitor.activity()

            updateTemperatures(status.temperatures, status.virtualSensors)
            predictionManager.updatePredictionStatus(status.predictionStatus, status.maxSequenceNumber)
        }

        arbitrator.preferredMeatNetLink?.let {
            _probe.value = _probe.value.copy(
                hopCount = it.hopCount
            )
        }

        updateBatteryIdColor(status.batteryStatus, status.id, status.color)
        updateSequenceNumbers(status.minSequenceNumber, status.maxSequenceNumber)
    }

    private fun updateInstantRead(value: Double?) {
        instantReadFilter.addReading(value)
        _probe.value = _probe.value.copy(
            instantReadCelsius = instantReadFilter.values?.first,
            instantReadFahrenheit = instantReadFilter.values?.second,
            instantReadRawCelsius = value
        )
        instantReadMonitor.activity()
    }

    private fun updatePredictionInfo(predictionInfo: PredictionManager.PredictionInfo?) {
        _probe.value = _probe.value.copy(
            predictionState = predictionInfo?.predictionState,
            predictionMode = predictionInfo?.predictionMode,
            predictionType = predictionInfo?.predictionType,
            setPointTemperatureCelsius = predictionInfo?.predictionSetPointTemperature,
            heatStartTemperatureCelsius = predictionInfo?.heatStartTemperature,
            rawPredictionSeconds = predictionInfo?.rawPredictionSeconds,
            predictionSeconds = predictionInfo?.secondsRemaining,
            estimatedCoreCelsius = predictionInfo?.estimatedCoreTemperature
        )
    }

    private fun updateTemperatures(temperatures: ProbeTemperatures, sensors: ProbeVirtualSensors) {
        _probe.value = _probe.value.copy(
            temperaturesCelsius = temperatures,
            virtualSensors = sensors,
            coreTemperatureCelsius = when(sensors.virtualCoreSensor) {
                ProbeVirtualSensors.VirtualCoreSensor.T1 -> temperatures.values[0]
                ProbeVirtualSensors.VirtualCoreSensor.T2 -> temperatures.values[1]
                ProbeVirtualSensors.VirtualCoreSensor.T3 -> temperatures.values[2]
                ProbeVirtualSensors.VirtualCoreSensor.T4 -> temperatures.values[3]
                ProbeVirtualSensors.VirtualCoreSensor.T5 -> temperatures.values[4]
                ProbeVirtualSensors.VirtualCoreSensor.T6 -> temperatures.values[5]
            },
            surfaceTemperatureCelsius = when(sensors.virtualSurfaceSensor) {
                ProbeVirtualSensors.VirtualSurfaceSensor.T4 -> temperatures.values[3]
                ProbeVirtualSensors.VirtualSurfaceSensor.T5 -> temperatures.values[4]
                ProbeVirtualSensors.VirtualSurfaceSensor.T6 -> temperatures.values[5]
                ProbeVirtualSensors.VirtualSurfaceSensor.T7 -> temperatures.values[6]
            },
            ambientTemperatureCelsius = when(sensors.virtualAmbientSensor) {
                ProbeVirtualSensors.VirtualAmbientSensor.T5 -> temperatures.values[4]
                ProbeVirtualSensors.VirtualAmbientSensor.T6 -> temperatures.values[5]
                ProbeVirtualSensors.VirtualAmbientSensor.T7 -> temperatures.values[6]
                ProbeVirtualSensors.VirtualAmbientSensor.T8 -> temperatures.values[7]
            },
            overheatingSensors = temperatures.overheatingSensors,
        )

        if(instantReadMonitor.isIdle(PROBE_INSTANT_READ_IDLE_TIMEOUT_MS)) {
            _probe.value = _probe.value.copy(
                instantReadCelsius = null,
                instantReadFahrenheit = null,
                instantReadRawCelsius = null,
            )
        }
    }

    private fun updateBatteryIdColor(battery: ProbeBatteryStatus, id: ProbeID, color: ProbeColor) {
        _probe.value = _probe.value.copy(
            batteryStatus = battery,
            id = id,
            color = color
        )
    }

    private fun updateSequenceNumbers(minSequenceNumber: UInt, maxSequenceNumber: UInt) {
        _probe.value = _probe.value.copy(
            minSequenceNumber = minSequenceNumber,
            maxSequenceNumber = maxSequenceNumber
        )
    }

    private fun updateSessionInfo(info: SessionInformation) {
        sessionInfo = info
        _probe.value = _probe.value.copy(
            sessionInfo = info
        )
    }
}