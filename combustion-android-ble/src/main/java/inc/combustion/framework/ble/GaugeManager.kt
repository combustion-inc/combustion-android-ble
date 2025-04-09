/*
 * Project: Combustion Inc. Android Framework
 * File: GaugeManager.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2025. Combustion Inc.
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
import inc.combustion.framework.ble.device.DeviceID
import inc.combustion.framework.ble.device.DeviceInformationBleDevice
import inc.combustion.framework.ble.device.GaugeBleDevice
import inc.combustion.framework.ble.device.SimulatedGaugeBleDevice
import inc.combustion.framework.ble.device.UartCapableGauge
import inc.combustion.framework.ble.scanning.GaugeAdvertisingData
import inc.combustion.framework.ble.uart.meatnet.NodeReadGaugeLogsResponse
import inc.combustion.framework.service.DeviceConnectionState
import inc.combustion.framework.service.DeviceManager
import inc.combustion.framework.service.FirmwareVersion
import inc.combustion.framework.service.Gauge
import inc.combustion.framework.service.HighLowAlarmStatus
import inc.combustion.framework.service.ModelInformation
import inc.combustion.framework.service.ProbeUploadState
import inc.combustion.framework.service.SessionInformation
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class GaugeManager(
    mac: String,
    serialNumber: String,
    val owner: LifecycleOwner,
    private val settings: DeviceManager.Settings,
    private val dfuDisconnectedNodeCallback: (DeviceID) -> Unit,
) : BleManager() {
    companion object {
        private const val GAUGE_STATUS_NOTIFICATIONS_IDLE_POLL_RATE_MS = 1000L
        private const val GAUGE_STATUS_NOTIFICATIONS_IDLE_TIMEOUT_MS =
            Gauge.GAUGE_STATUS_NOTIFICATIONS_IDLE_TIMEOUT_MS
        private const val GAUGE_STATUS_NOTIFICATIONS_POLL_DELAY_MS = 30000L
    }

    // encapsulates logic for managing network data links
    override val arbitrator = GaugeDataLinkArbitrator()

    // idle monitors
    private val statusNotificationsMonitor = IdleMonitor()

    // holds the current state and data for this probe
    private val _gauge = MutableStateFlow(Gauge.create(serialNumber = serialNumber, mac = mac))

    // the flow that is consumed to get state and date updates
    val gaugeFlow = _gauge.asStateFlow()

    val gauge: Gauge
        get() {
            return _gauge.value
        }

    // the flow that produces LogResponses from MeatNet
    private val _logResponseFlow = MutableSharedFlow<NodeReadGaugeLogsResponse>(
        replay = 0, extraBufferCapacity = 50, BufferOverflow.SUSPEND
    )

    // the flow that is consumed to get LogResponses from MeatNet
    override val logResponseFlow = _logResponseFlow.asSharedFlow()


    // current upload state for this probe, determined by LogManager
    override var uploadState: ProbeUploadState
        get() {
            return _gauge.value.uploadState
        }
        set(value) {
            if (value != _gauge.value.uploadState) {
                _gauge.update { it.copy(uploadState = value) }
            }
        }
    override var recordsDownloaded: Int
        get() {
            return _gauge.value.recordsDownloaded
        }
        set(value) {
            if (value != _gauge.value.recordsDownloaded) {
                _gauge.update { it.copy(recordsDownloaded = value) }
            }
        }

    override var logUploadPercent: UInt
        get() {
            return _gauge.value.logUploadPercent
        }
        set(value) {
            if (value != _gauge.value.logUploadPercent) {
                _gauge.update { it.copy(logUploadPercent = value) }
            }
        }

    val connectionState: DeviceConnectionState
        get() {
            return arbitrator.bleDevice?.connectionState ?: DeviceConnectionState.NO_ROUTE
        }

    private val fwVersion: FirmwareVersion?
        get() {
            simulatedGauge?.let { return it.deviceInfoFirmwareVersion }
            return arbitrator.bleDevice?.deviceInfoFirmwareVersion
        }

    private val hwRevision: String?
        get() {
            simulatedGauge?.let { return it.deviceInfoHardwareRevision }
            return arbitrator.bleDevice?.deviceInfoHardwareRevision
        }

    private val modelInformation: ModelInformation?
        get() {
            simulatedGauge?.let { return it.deviceInfoModelInformation }
            return arbitrator.bleDevice?.deviceInfoModelInformation
        }

    // serial number of the probe that is being managed by this manager
    override val serialNumber: String
        get() {
            return _gauge.value.serialNumber
        }

    // the flow that produces ProbeStatus updates from MeatNet
    private val _normalModeProbeStatusFlow = MutableSharedFlow<GaugeStatus>(
        replay = 0, extraBufferCapacity = 10, BufferOverflow.DROP_OLDEST
    )

    override val normalModeStatusFlow: SharedFlow<SpecializedDeviceStatus> =
        _normalModeProbeStatusFlow.asSharedFlow()

    override val minSequenceNumber: UInt?
        get() {
            return _gauge.value.minSequence
        }

    override val maxSequenceNumber: UInt?
        get() {
            return _gauge.value.maxSequence
        }

    // tracks if we've run into a message timeout condition getting
    // session info
    private var sessionInfoTimeout: Boolean = false

    private var simulatedGauge: SimulatedGaugeBleDevice? = null

    init {
        monitorStatusNotifications()
    }

    private fun monitorStatusNotifications() {
        addJob(
            serialNumber,
            owner.lifecycleScope.launch(
                CoroutineName("${serialNumber}.monitorStatusNotifications") + Dispatchers.IO
            ) {
                // Wait before starting to monitor prediction status, this allows for initial
                // connection time
                delay(GAUGE_STATUS_NOTIFICATIONS_POLL_DELAY_MS)

                while (isActive) {
                    delay(GAUGE_STATUS_NOTIFICATIONS_IDLE_POLL_RATE_MS)

                    val statusNotificationsStale =
                        statusNotificationsMonitor.isIdle(GAUGE_STATUS_NOTIFICATIONS_IDLE_TIMEOUT_MS)
                    val shouldUpdate =
                        statusNotificationsStale != _gauge.value.statusNotificationsStale

                    if (shouldUpdate) {
                        _gauge.update {
                            it.copy(
                                statusNotificationsStale = statusNotificationsStale,
                            )
                        }
                    }
                }
            }
        )
    }

    fun connect() {
        arbitrator.getNodesNeedingConnection(true).forEach { node ->
            node.connect()
        }
        simulatedGauge?.shouldConnect = true
        simulatedGauge?.connect()
    }

    fun disconnect(canDisconnectFromMeatNetDevices: Boolean = false) {
        arbitrator.getNodesNeedingDisconnect(
            canDisconnectFromMeatNetDevices = canDisconnectFromMeatNetDevices
        ).forEach { node ->
            // Since this is an explicit disconnect, we want to also disable the auto-reconnect flag
            arbitrator.setShouldAutoReconnect(false)
            node.disconnect()
        }

        arbitrator.directLinkDiscoverTimestamp = null

        simulatedGauge?.shouldConnect = false
        simulatedGauge?.disconnect()
    }

    private fun updateDataFromAdvertisement(
        advertisement: GaugeAdvertisingData,
        currentGauge: Gauge,
    ): Gauge {
        val updatedGauge = currentGauge.copy(
            baseDevice = _gauge.value.baseDevice.copy(rssi = advertisement.rssi),
        )

        return updatedGauge.copy(
            gaugeStatusFlags = advertisement.gaugeStatusFlags,
            temperatureCelsius = if (advertisement.gaugeStatusFlags.sensorPresent) advertisement.gaugeTemperature else null,
            batteryPercentage = advertisement.batteryPercentage,
            highLowAlarmStatus = advertisement.highLowAlarmStatus,
        )
    }

    fun hasGauge(): Boolean = arbitrator.bleDevice != null

    fun addGauge(
        gauge: GaugeBleDevice,
        baseDevice: DeviceInformationBleDevice,
        advertisement: GaugeAdvertisingData
    ) {
        if (IGNORE_GAUGES) return

        if (arbitrator.addDevice(gauge, baseDevice)) {
            handleAdvertisingPackets(gauge, advertisement)
            observe(gauge)
        }
    }

    fun addSimulatedGauge(simGauge: SimulatedGaugeBleDevice) {
        if (simulatedGauge != null) return
        var updatedGauge = _gauge.value

        // process simulated connection state changes
        simGauge.observeConnectionState { state ->
            updatedGauge = handleConnectionState(simGauge, state, updatedGauge)
        }

        // process simulated out of range
        simGauge.observeOutOfRange(OUT_OF_RANGE_TIMEOUT) {
            updatedGauge = handleOutOfRange(updatedGauge)
        }

        // process simulated advertising packets
        simGauge.observeAdvertisingPackets(serialNumber, simGauge.mac) { advertisement ->
            if (advertisement is GaugeAdvertisingData) {
                updatedGauge = updateDataFromAdvertisement(advertisement, updatedGauge)
                if (settings.autoReconnect && simGauge.shouldConnect) {
                    simGauge.connect()
                }
            }
        }

        simulatedGauge = simGauge

        _gauge.update {
            updatedGauge.copy(baseDevice = it.baseDevice.copy(mac = simGauge.mac))
        }
    }

    private fun handleAdvertisingPackets(
        device: GaugeBleDevice,
        advertisement: GaugeAdvertisingData,
    ) {
        val state = connectionState
        val networkIsAdvertisingAndNotConnected =
            (state == DeviceConnectionState.ADVERTISING_CONNECTABLE || state == DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE ||
                    state == DeviceConnectionState.CONNECTING)

        if (networkIsAdvertisingAndNotConnected) {
            val updatedDevice =
                if (arbitrator.shouldUpdateDataFromAdvertisingPacket(device, advertisement)) {
                    updateDataFromAdvertisement(advertisement, _gauge.value)
                } else {
                    _gauge.value
                }

            _gauge.update {
                updatedDevice.copy(
                    baseDevice = _gauge.value.baseDevice.copy(connectionState = state),
                )
            }
        }

        if (arbitrator.shouldConnect(device)) {
            Log.i(
                LOG_TAG,
                "PM($serialNumber) automatically connecting to ${device.id} (${device.productType})", // on link ${device.linkId}"
            )
            device.connect()
        }
    }

    private fun handleConnectionState(
        device: UartCapableGauge,
        state: DeviceConnectionState,
        currentGauge: Gauge,
    ): Gauge {
        val isConnected = state == DeviceConnectionState.CONNECTED
        val isDisconnected = state == DeviceConnectionState.DISCONNECTED

        if (isConnected) {
            Log.i(LOG_TAG, "PM($serialNumber): ${device.productType}[${device.id}] is connected.")
            fetchDeviceInfo()
        }

        var updatedGauge = currentGauge

        if (isDisconnected) {
            Log.i(
                LOG_TAG,
                "PM($serialNumber): ${device.productType}[${device.id}] is disconnected."
            )

            // perform any cleanup
            device.disconnect()

            // reset the discover timestamp upon disconnection
            arbitrator.directLinkDiscoverTimestamp = null

            // Invalidate FW version so it's re-read on connection after DFU
            updatedGauge = updatedGauge.copy(
                baseDevice = updatedGauge.baseDevice.copy(
                    fwVersion = null
                )
            )

            // remove this item from the list of firmware details for the network
            dfuDisconnectedNodeCallback(device.id)
        }

        // use the arbitrated connection state, fw version, hw revision, model information
        return updateConnectionState(updatedGauge)
    }

    private fun fetchDeviceInfo() {
        fetchFirmwareVersion()
        fetchHardwareRevision()
        fetchModelInformation()
    }

    private fun fetchFirmwareVersion() {
        // if we don't know the probe's firmware version
        if (_gauge.value.fwVersion == null) {

            // if direct link, then get the probe version over that link
            arbitrator.directLink?.readFirmwareVersionAsync { fwVersion ->
                // update firmware version on completion of read
                _gauge.update {
                    it.copy(baseDevice = _gauge.value.baseDevice.copy(fwVersion = fwVersion))
                }
            }
        }
    }

    private fun fetchHardwareRevision() {
        // if we don't know the probe's hardware revision
        if (_gauge.value.hwRevision == null) {

            // if direct link, then get the probe revision over that link
            arbitrator.directLink?.readHardwareRevisionAsync { hwRevision ->

                // update firmware version on completion of read
                _gauge.update {
                    it.copy(baseDevice = it.baseDevice.copy(hwRevision = hwRevision))
                }
            }
        }
    }

    private fun fetchModelInformation() {
        // if we don't know the probe's model information
        if (_gauge.value.modelInformation == null) {

            // if direct link, then get the probe model info over that link
            arbitrator.directLink?.readModelInformationAsync { info ->

                // update firmware version on completion of read
                _gauge.update {
                    it.copy(
                        baseDevice = it.baseDevice.copy(modelInformation = info)
                    )
                }
            }
        }
    }

    private fun updateConnectionState(currentGauge: Gauge): Gauge {
        return currentGauge.copy(
            baseDevice = _gauge.value.baseDevice.copy(
                connectionState = connectionState,
                fwVersion = fwVersion,
                hwRevision = hwRevision,
                modelInformation = modelInformation
            )
        )
    }

    private fun handleOutOfRange(currentGauge: Gauge): Gauge {
        // if the arbitrated connection state is out of range, then update.
        return if (connectionState == DeviceConnectionState.OUT_OF_RANGE) {
            currentGauge.copy(
                baseDevice = _gauge.value.baseDevice.copy(
                    connectionState = DeviceConnectionState.OUT_OF_RANGE
                )
            )
        } else {
            currentGauge
        }
    }

    private fun handleRemoteRssi(
        device: GaugeBleDevice,
        rssi: Int,
        currentGauge: Gauge
    ): Gauge {
        return if (arbitrator.shouldUpdateOnRemoteRssi(device)) {
            currentGauge.copy(baseDevice = currentGauge.baseDevice.copy(rssi = rssi))
        } else {
            currentGauge
        }
    }

    private fun handleSessionInfo(
        info: SessionInformation,
        minSequenceNumber: UInt,
        maxSequenceNumber: UInt,
    ): Gauge {
        sessionInfoTimeout = false
        var minSequence: UInt? = minSequenceNumber
        var maxSequence: UInt? = maxSequenceNumber

        // if the session information has changed, then we need to finish the previous log session.
        if (sessionInfo != info) {
            logTransferCompleteCallback()
            uploadState = ProbeUploadState.Unavailable

            minSequence = null
            maxSequence = null

            Log.i(LOG_TAG, "PM($serialNumber): finished log transfer.")
        }

        sessionInfo = info
        return _gauge.value.copy(
            minSequence = minSequence,
            maxSequence = maxSequence,
            sessionInfo = info,
        )
    }

    private fun handleStatus(status: GaugeStatus) {
        if (arbitrator.shouldUpdateDataFromStatusForNormalMode(status, sessionInfo)) {
            statusNotificationsMonitor.activity()

            var updatedGauge = handleSessionInfo(
                status.sessionInformation,
                minSequenceNumber = status.minSequenceNumber,
                maxSequenceNumber = status.maxSequenceNumber,
            )

            updatedGauge = updatedGauge.copy(
                batteryPercentage = status.batteryPercentage,
                highLowAlarmStatus = status.highLowAlarmStatus,
                gaugeStatusFlags = status.gaugeStatusFlags,
                temperatureCelsius = if (status.gaugeStatusFlags.sensorPresent) status.temperature else null,
            )

            // redundantly check for device information
            fetchDeviceInfo()

            // These log-related items can be updated outside of this function--specifically, these
            // are updated by the LogManager when we emit a new status to the
            // normalModeProbeStatusFlow.
            _gauge.update { updatedGauge }
        }
    }

    private fun observe(guage: GaugeBleDevice) {
        _gauge.update {
            it.copy(
                baseDevice = it.baseDevice.copy(
                    mac = guage.mac,
                )
            )
        }

        guage.observeAdvertisingPackets(serialNumber, guage.mac) { advertisement ->
            if (advertisement is GaugeAdvertisingData) {
                handleAdvertisingPackets(guage, advertisement)
            }
        }

        guage.observeConnectionState { state ->
            _gauge.update { handleConnectionState(guage, state, it) }
        }

        guage.observeOutOfRange(OUT_OF_RANGE_TIMEOUT) {
            _gauge.update { handleOutOfRange(it) }
        }

        guage.observeGaugeStatusUpdates { status ->
            handleStatus(status)
        }

        guage.observeRemoteRssi { rssi ->
            _gauge.update { handleRemoteRssi(guage, rssi, it) }
        }
    }

    fun setHighLowAlarmStatus(
        highLowAlarmStatus: HighLowAlarmStatus,
        completionHandler: (Boolean) -> Unit
    ) {
        val onCompletion: (Boolean) -> Unit = { success ->
            if (success) {
                _gauge.update {
                    _gauge.value.copy(
                        highLowAlarmStatus = highLowAlarmStatus,
                    )
                }
            }
            completionHandler(success)
        }

        val requestId = makeRequestId()
        simulatedGauge?.sendSetHighLowAlarmStatus(highLowAlarmStatus, requestId) { status, _ ->
            onCompletion(status)
        } ?: run {
            // if there is a direct link to the probe, then use that
            arbitrator.directLink?.sendSetHighLowAlarmStatus(
                highLowAlarmStatus,
                requestId,
            ) { status, _ ->
                onCompletion(status)
            } ?: run {
                onCompletion(false)
            }
        }
    }

    override fun sendLogRequest(startSequenceNumber: UInt, endSequenceNumber: UInt) {
        simulatedGauge?.sendGaugeLogRequest(startSequenceNumber, endSequenceNumber) {
            _logResponseFlow.emit(it)
        } ?: arbitrator.directLink?.sendGaugeLogRequest(startSequenceNumber, endSequenceNumber) {
            _logResponseFlow.emit(it)
        }
    }
}