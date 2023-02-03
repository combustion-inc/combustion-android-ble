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
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.device.*
import inc.combustion.framework.ble.device.DeviceInformationBleDevice
import inc.combustion.framework.ble.device.ProbeBleDevice
import inc.combustion.framework.ble.device.ProbeBleDeviceBase
import inc.combustion.framework.ble.device.RepeatedProbeBleDevice
import inc.combustion.framework.ble.scanning.CombustionAdvertisingData
import inc.combustion.framework.ble.uart.LogResponse
import inc.combustion.framework.service.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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
        private const val PROBE_PREDICTION_IDLE_TIMEOUT_MS = 15000L
        private val MAX_PREDICTION_SECONDS = 60u * 60u * 4u
        private val LOW_RESOLUTION_CUTOFF_SECONDS = 60u * 5u
        private val LOW_RESOLUTION_PRECISION_SECONDS = 15u
        private val PREDICTION_TIME_UPDATE_COUNT = 3u
    }

    // encapsulates logic for managing network data links
    private val arbitrator = DataLinkArbitrator(settings)

    // manages long-running coroutine scopes for data handling
    private val jobManager = JobManager()

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

    private var predictionCountdownSeconds: UInt? = null

    fun addJob(job: Job) = jobManager.addJob(job)

    fun addProbe(probe: ProbeBleDevice, baseDevice: DeviceInformationBleDevice) {
        if(arbitrator.addProbe(probe, baseDevice)) {
            observe(probe)
            Log.i(LOG_TAG, "PM($serialNumber) is managing link ${probe.linkId}")
        }
    }

    fun addRepeatedProbe(repeatedProbe: RepeatedProbeBleDevice, repeater: DeviceInformationBleDevice) {
        if(arbitrator.addRepeatedProbe(repeatedProbe, repeater))  {
            observe(repeatedProbe)
            Log.i(LOG_TAG, "PM($serialNumber) is managing link ${repeatedProbe.linkId}")
        }
    }

    fun connect() {
        arbitrator.getNodesNeedingConnection(true).forEach { node ->
            node.connect()
        }
    }

    fun disconnect() {
        arbitrator.getNodesNeedingDisconnect(true).forEach {node ->
            node.disconnect()
        }
    }

    fun setProbeColor(color: ProbeColor, completionHandler: (Boolean) -> Unit) {
        // TODO: use preferred meatnet link when the messages are implemented in firmware (see setPrediction)
        arbitrator.getDirectLink()?.sendSetProbeColor(color) { status, _ ->
            completionHandler(status)
        } ?: run {
            completionHandler(false)
        }
    }

    fun setProbeID(id: ProbeID, completionHandler: (Boolean) -> Unit) {
        // TODO: use preferred meatnet link when the messages are implemented in firmware (see setPrediction)
        arbitrator.getDirectLink()?.sendSetProbeID(id) { status, _ ->
            completionHandler(status)
        } ?: run {
            completionHandler(false)
        }
    }

    fun setPrediction(removalTemperatureC: Double, mode: ProbePredictionMode, completionHandler: (Boolean) -> Unit) {
        arbitrator.getPreferredMeatNetLink()?.sendSetPrediction(removalTemperatureC, mode) { status, _ ->
            completionHandler(status)
        } ?: run {
            completionHandler(false)
        }
    }

    fun sendLogRequest(startSequenceNumber: UInt, endSequenceNumber: UInt) {
        // TODO: use preferred meatnet link when the messages are implemented in firmware (see setPrediction)
        arbitrator.getDirectLink()?.sendLogRequest(startSequenceNumber, endSequenceNumber) {
            _logResponseFlow.emit(it)
        }
    }

    fun finish() {
        arbitrator.finish()
        jobManager.cancelJobs()
    }

    private fun observe(base: ProbeBleDeviceBase) {
        _probe.value = _probe.value.copy(baseDevice = _probe.value.baseDevice.copy(mac = base.mac))

        base.observeAdvertisingPackets(serialNumber, base.mac) { advertisement -> handleAdvertisingPackets(base, advertisement) }
        base.observeConnectionState { state -> handleConnectionState(base, state) }
        base.observeOutOfRange(OUT_OF_RANGE_TIMEOUT){ handleOutOfRange(base) }
        base.observeProbeStatusUpdates { status -> handleProbeStatus(base, status) }
        base.observeRemoteRssi { rssi ->  handleRemoteRssi(base, rssi) }
    }

    private suspend fun handleProbeStatus(device: ProbeBleDeviceBase, status: ProbeStatus) {
        if(arbitrator.shouldUpdateDataFromProbeStatus(device)) {
            updateDataFromProbeStatus(status)
        }

        // TODO: MeatNet Log Transfer: hard-coded to direct link for now.
        if(arbitrator.getDirectLink() == device) {
            _probeStatusFlow.emit(status)
        }
    }

    private fun handleAdvertisingPackets(device: ProbeBleDeviceBase, advertisement: CombustionAdvertisingData) {
        if(!arbitrator.shouldHandleAdvertisingPacket(device)) {
            return
        }

        if(arbitrator.shouldUpdateDataFromAdvertisingPacket(device)) {
            updateDataFromAdvertisement(advertisement)
        }

        if(arbitrator.shouldUpdateConnectionStateFromAdvertisingPacket(device)) {
            updateConnectionStateFromAdvertisement(
                connectable = advertisement.isConnectable
            )
        }

        if(arbitrator.shouldConnect(device)) {
            Log.i(LOG_TAG, "PM($serialNumber) automatically connecting to ${device.id}")
            device.connect()
        }
    }

    private fun handleConnectionState(device: ProbeBleDeviceBase, state: DeviceConnectionState) {
        val isConnected = state == DeviceConnectionState.CONNECTED
        val isDisconnected = state == DeviceConnectionState.DISCONNECTED

        arbitrator.handleConnectionState(device, state)

        if(isConnected) {
            Log.i(LOG_TAG, "PM($serialNumber): ${device.productType}[${device.id}] is connected.")
            handleConnectedState(device)
        }

        if(isDisconnected) {
            // perform any cleanup
            device.disconnect()

            // update
            if(arbitrator.shouldUpdateOnDisconnect(device)) {
                updateOnDisconnected()
            }

            // remove this item from the list of firmware details for the network
            dfuDisconnectedNodeCallback(device.id)
        }

        if(arbitrator.shouldUpdateConnectionState(device, state)) {
            _probe.value = _probe.value.copy(baseDevice = _probe.value.baseDevice.copy(connectionState = state))
        }
    }

    private fun handleOutOfRange(device: ProbeBleDeviceBase) {
        arbitrator.handleOutOfRange(device)
        if(arbitrator.shouldUpdateOnOutOfRange(device)) {
            updateStateOnOutOfRange()
        }
    }

    private fun handleRemoteRssi(device: ProbeBleDeviceBase, rssi: Int) {
        if(arbitrator.shouldUpdateOnRemoteRssi(device, rssi)) {
            _probe.value = _probe.value.copy(baseDevice = _probe.value.baseDevice.copy(rssi = rssi))
        }
    }

    private fun handleConnectedState(device: ProbeBleDeviceBase) {
        var didReadDeviceInfo = false
        owner.lifecycleScope.launch {
            device.deviceInfoFirmwareVersion ?: run {
                didReadDeviceInfo = true
                device.readFirmwareVersion()
            }
            device.deviceInfoSerialNumber ?: run {
                didReadDeviceInfo = true
                device.readSerialNumber()
            }
            device.deviceInfoHardwareRevision ?: run {
                didReadDeviceInfo = true
                device.readHardwareRevision()
            }
        }.invokeOnCompletion {
            // if we read any of the device information characteristics above
            if(didReadDeviceInfo) {

                // if we should update the current external state of the probe base on device info data
                if(arbitrator.shouldUpdateOnDeviceInfoRead(device)) {
                    _probe.value = _probe.value.copy(baseDevice = _probe.value.baseDevice.copy(
                        fwVersion = device.deviceInfoFirmwareVersion,
                        hwRevision = device.deviceInfoHardwareRevision
                    ))
                }

                device.deviceInfoFirmwareVersion?.let {
                    dfuConnectedNodeCallback(FirmwareState.Node(
                        id = device.id,
                        type = device.productType,
                        firmwareVersion = it
                    ))
                }
            }
        }

        // TODO: MeatNet Log Transfer: hard-coded to direct link for now.
        arbitrator.getDirectLink()?.let {
            if(sessionInfo == null) {
                it.sendSessionInformationRequest { status, info ->
                    if(status && info is SessionInformation) {
                        updateSessionInfo(info)
                    }
                }
            }
        }
    }

    private fun updateStateOnOutOfRange()  {
        _probe.value = _probe.value.copy(
            baseDevice = _probe.value.baseDevice.copy(connectionState = DeviceConnectionState.OUT_OF_RANGE)
        )
    }

    private fun updateConnectionStateFromAdvertisement(connectable: Boolean) {
        val advertisingState = if(connectable) DeviceConnectionState.ADVERTISING_CONNECTABLE else DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE

        _probe.value = _probe.value.copy(
            baseDevice = _probe.value.baseDevice.copy(connectionState = advertisingState),
        )
    }

    private fun updateDataFromAdvertisement(advertisement: CombustionAdvertisingData) {
        _probe.value = _probe.value.copy(
            baseDevice = _probe.value.baseDevice.copy(rssi = advertisement.rssi),
            hopCount = advertisement.hopCount,
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
            updateTemperatures(status.temperatures, status.virtualSensors)
            updatePredictionStatus(status.predictionStatus, _probe.value.maxSequenceNumber)
        }

        updateBatteryIdColor(status.batteryStatus, status.id, status.color)
        updateSequenceNumbers(status.minSequenceNumber, status.maxSequenceNumber)
    }

    private fun updateInstantRead(value: Double?) {
        // TODO: Keep Track of Instant Read Activity
        _probe.value = _probe.value.copy(
            instantReadCelsius = value
        )
    }

    private fun updatePredictionStatus(predictionStatus: PredictionStatus?, maxSequenceNumber: UInt) {
        // TODO prediction activity monitor

        // handle large predictions and prediction resolution
        predictionStatus?.let {
            val rawPrediction = it.predictionValueSeconds

            predictionCountdownSeconds = if(rawPrediction > MAX_PREDICTION_SECONDS) {
                null
            }
            else if(rawPrediction < LOW_RESOLUTION_CUTOFF_SECONDS) {
                rawPrediction
            }
            else if(predictionCountdownSeconds == null || (maxSequenceNumber % PREDICTION_TIME_UPDATE_COUNT) == 0u) {
                val remainder = rawPrediction % LOW_RESOLUTION_PRECISION_SECONDS
                if(remainder > (LOW_RESOLUTION_PRECISION_SECONDS / 2u)) {
                    // round up
                    rawPrediction + (LOW_RESOLUTION_PRECISION_SECONDS - remainder)
                }
                else {
                    // round down
                    rawPrediction - remainder
                }
            }
            else {
                predictionCountdownSeconds
            }
        }

        // TODO
        // Calculate if prediction has gone stale
//        val predictionStale = predictionStatusMonitor.isIdle(PROBE_PREDICTION_IDLE_TIMEOUT_MS)

        _probe.value = _probe.value.copy(
            predictionState = predictionStatus?.predictionState,
            predictionMode = predictionStatus?.predictionMode,
            predictionType = predictionStatus?.predictionType,
            setPointTemperatureCelsius = predictionStatus?.setPointTemperature,
            heatStartTemperatureCelsius = predictionStatus?.heatStartTemperature,
            rawPredictionSeconds = predictionStatus?.predictionValueSeconds,
            predictionSeconds = predictionCountdownSeconds,
            estimatedCoreCelsius = predictionStatus?.estimatedCoreTemperature
        )
    }

    private fun updateTemperatures(temperatures: ProbeTemperatures, sensors: ProbeVirtualSensors) {
        // TODO: Keep Track of Instant Read Stale/Idle/Timeout
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
            }
        )
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

    private fun updateOnDisconnected() {
        sessionInfo = null
        _probe.value = _probe.value.copy(
            baseDevice = _probe.value.baseDevice.copy(fwVersion = null, hwRevision = null),
            sessionInfo = null,
        )
    }

    private fun updateOnPredictionStale() {
        _probe.value = _probe.value.copy(
            predictionState = null,
            predictionMode = null,
            predictionType = null,
            setPointTemperatureCelsius = null,
            heatStartTemperatureCelsius = null,
            predictionSeconds = null,
            rawPredictionSeconds = null,
            estimatedCoreCelsius = null
        )
    }
}