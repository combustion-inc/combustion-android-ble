/*
 * Project: Combustion Inc. Android Framework
 * File: NodeHybridManager.kt
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
import inc.combustion.framework.ble.device.*
import inc.combustion.framework.ble.scanning.DeviceAdvertisingData
import inc.combustion.framework.service.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

internal abstract class NodeHybridManager<
    T : NodeHybridBleDevice,
    D : DeviceAdvertisingData,
    S : SpecializedDevice,
    SIM : SimulatedNodeHybridBleDevice,
>(
    val scope: CoroutineScope,
    protected val settings: DeviceManager.Settings,
    protected val dfuDisconnectedNodeCallback: (DeviceID) -> Unit,
) : BleManager() {

    protected abstract val _deviceFlow: MutableStateFlow<S>
    abstract override val arbitrator: NodeHybridDataLinkArbitrator<T, D>

    // Abstract copy helpers for type-specific state updates
    protected abstract fun S.withBaseDevice(baseDevice: Device): S
    protected abstract fun S.withStatusNotificationsStale(stale: Boolean): S
    protected abstract fun S.withUploadState(state: ProbeUploadState): S
    protected abstract fun S.withRecordsDownloaded(count: Int): S
    protected abstract fun S.withLogUploadPercent(percent: UInt): S
    protected abstract fun S.withSessionInfo(info: SessionInformation, minSequenceNumber: UInt, maxSequenceNumber: UInt): S

    // Abstract advertisement handling
    protected abstract fun castToAdvertisementType(advertisement: DeviceAdvertisingData): D?
    protected abstract fun updateDataFromAdvertisement(advertisement: D, current: S): S

    protected open fun handleAdvertisingPackets(device: T, advertisement: D) {
        val state = connectionState
        val networkIsAdvertisingAndNotConnected =
            (state == DeviceConnectionState.ADVERTISING_CONNECTABLE || state == DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE ||
                    state == DeviceConnectionState.CONNECTING)

        if (networkIsAdvertisingAndNotConnected) {
            val updatedDevice =
                if (arbitrator.shouldUpdateDataFromAdvertisingPacket(device, advertisement)) {
                    updateDataFromAdvertisement(advertisement, _deviceFlow.value)
                } else {
                    _deviceFlow.value
                }

            _deviceFlow.update {
                updatedDevice.withBaseDevice(_deviceFlow.value.baseDevice.copy(connectionState = state))
            }
        }

        checkAutoConnect(device)
    }

    protected open fun updateDataFromSimulatedAdvertisement(simDevice: SIM, advertisement: D, current: S): S =
        updateDataFromAdvertisement(advertisement, current)

    protected val statusNotificationsMonitor = IdleMonitor()

    protected var simulatedDevice: SIM? = null
        private set

    override val serialNumber: String
        get() = _deviceFlow.value.serialNumber

    override val device: SpecializedDevice
        get() = _deviceFlow.value

    override val deviceFlow: StateFlow<SpecializedDevice>
        get() = _deviceFlow

    override val minSequenceNumber: UInt?
        get() = _deviceFlow.value.minSequence

    override val maxSequenceNumber: UInt?
        get() = _deviceFlow.value.maxSequence

    override var uploadState: ProbeUploadState
        get() = _deviceFlow.value.uploadState
        set(value) {
            if (value != _deviceFlow.value.uploadState) {
                _deviceFlow.update { it.withUploadState(value) }
            }
        }

    override var recordsDownloaded: Int
        get() = _deviceFlow.value.recordsDownloaded
        set(value) {
            if (value != _deviceFlow.value.recordsDownloaded) {
                _deviceFlow.update { it.withRecordsDownloaded(value) }
            }
        }

    override var logUploadPercent: UInt
        get() = _deviceFlow.value.logUploadPercent
        set(value) {
            if (value != _deviceFlow.value.logUploadPercent) {
                _deviceFlow.update { it.withLogUploadPercent(value) }
            }
        }

    val connectionState: DeviceConnectionState
        get() = arbitrator.bleDevice?.connectionState ?: DeviceConnectionState.NO_ROUTE

    protected open val fwVersion: FirmwareVersion?
        get() {
            simulatedDevice?.let { return it.deviceInfoFirmwareVersion }
            return arbitrator.bleDevice?.deviceInfoFirmwareVersion
        }

    protected open val hwRevision: String?
        get() {
            simulatedDevice?.let { return it.deviceInfoHardwareRevision }
            return arbitrator.bleDevice?.deviceInfoHardwareRevision
        }

    protected open val modelInformation: ModelInformation?
        get() {
            simulatedDevice?.let { return it.deviceInfoModelInformation }
            return arbitrator.bleDevice?.deviceInfoModelInformation
        }

    fun addRepeaters(repeaters: () -> List<NodeBleDevice>) {
        arbitrator.addRepeaterNodes(repeaters)
    }

    fun hasDevice(): Boolean = arbitrator.bleDevice != null

    open fun connect() {
        arbitrator.getNodesNeedingConnection(true).forEach { node -> node.connect() }
        simulatedDevice?.shouldConnect = true
        simulatedDevice?.connect()
    }

    open fun disconnect(canDisconnectFromMeatNetDevices: Boolean = false) {
        arbitrator.getNodesNeedingDisconnect(
            canDisconnectFromMeatNetDevices = canDisconnectFromMeatNetDevices
        ).forEach { node ->
            arbitrator.setShouldAutoReconnect(false)
            node.disconnect()
        }
        arbitrator.directLinkDiscoverTimestamp = null
        simulatedDevice?.shouldConnect = false
        simulatedDevice?.disconnect()
    }

    protected fun fetchDeviceInfo() {
        fetchFirmwareVersion()
        fetchHardwareRevision()
        fetchModelInformation()
    }

    private fun fetchFirmwareVersion() {
        if (!_deviceFlow.value.fwVersion.isValid()) {
            arbitrator.directLink?.readFirmwareVersionAsync { fwVersion ->
                _deviceFlow.update {
                    it.withBaseDevice(_deviceFlow.value.baseDevice.copy(fwVersion = fwVersion))
                }
            }
        }
    }

    private fun fetchHardwareRevision() {
        if (_deviceFlow.value.hwRevision == null) {
            arbitrator.directLink?.readHardwareRevisionAsync { hwRevision ->
                _deviceFlow.update {
                    it.withBaseDevice(it.baseDevice.copy(hwRevision = hwRevision))
                }
            }
        }
    }

    private fun fetchModelInformation() {
        if (_deviceFlow.value.modelInformation == null) {
            arbitrator.directLink?.readModelInformationAsync { info ->
                _deviceFlow.update {
                    it.withBaseDevice(it.baseDevice.copy(modelInformation = info))
                }
            }
        }
    }

    protected fun monitorStatusNotifications() {
        addJob(
            serialNumber,
            scope.launch(
                CoroutineName("${serialNumber}.monitorStatusNotifications") + Dispatchers.IO
            ) {
                delay(STATUS_NOTIFICATIONS_POLL_DELAY_MS)
                while (isActive) {
                    delay(STATUS_NOTIFICATIONS_IDLE_POLL_RATE_MS)
                    val stale = statusNotificationsMonitor.isIdle(STATUS_NOTIFICATIONS_IDLE_TIMEOUT_MS)
                    val shouldUpdate = stale != _deviceFlow.value.statusNotificationsStale
                    if (shouldUpdate) {
                        _deviceFlow.update { it.withStatusNotificationsStale(stale) }
                    }
                }
            }
        )
    }

    protected fun handleSessionInfo(
        info: SessionInformation,
        minSequenceNumber: UInt,
        maxSequenceNumber: UInt,
    ): S {
        if (sessionInfo != info) {
            logTransferCompleteCallback()
            uploadState = ProbeUploadState.Unavailable
            Log.i(LOG_TAG, "PM($serialNumber): finished log transfer.")
        }
        sessionInfo = info
        val updated = _deviceFlow.value.withSessionInfo(info, minSequenceNumber, maxSequenceNumber)
        _deviceFlow.update { updated }
        return updated
    }

    protected fun updateConnectionState(current: S): S {
        return current.withBaseDevice(
            _deviceFlow.value.baseDevice.copy(
                connectionState = connectionState,
                fwVersion = fwVersion,
                hwRevision = hwRevision,
                modelInformation = modelInformation,
            )
        )
    }

    protected fun handleOutOfRange(current: S): S {
        return if (connectionState == DeviceConnectionState.OUT_OF_RANGE) {
            current.withBaseDevice(
                _deviceFlow.value.baseDevice.copy(
                    connectionState = DeviceConnectionState.OUT_OF_RANGE
                )
            )
        } else {
            current
        }
    }

    protected fun handleConnectionState(
        device: UartCapableSpecializedDevice,
        state: DeviceConnectionState,
        current: S,
    ): S {
        val isConnected = state == DeviceConnectionState.CONNECTED
        val isDisconnected = state == DeviceConnectionState.DISCONNECTED

        if (isConnected) {
            Log.i(LOG_TAG, "PM($serialNumber): ${device.productType}[${device.id}] is connected.")
            fetchDeviceInfo()
        }

        var updated = current

        if (isDisconnected) {
            Log.i(LOG_TAG, "PM($serialNumber): ${device.productType}[${device.id}] is disconnected.")
            device.disconnect()
            arbitrator.directLinkDiscoverTimestamp = null
            updated = updated.withBaseDevice(updated.baseDevice.copy(fwVersion = null))
            dfuDisconnectedNodeCallback(device.id)
        }

        return updateConnectionState(updated)
    }

    protected fun handleRemoteRssi(device: T, rssi: Int, current: S): S {
        return if (arbitrator.shouldUpdateOnRemoteRssi(device)) {
            current.withBaseDevice(current.baseDevice.copy(rssi = rssi))
        } else {
            current
        }
    }

    protected fun checkAutoConnect(device: T) {
        if (arbitrator.shouldConnect(device)) {
            Log.i(LOG_TAG, "PM($serialNumber) automatically connecting to ${device.id} (${device.productType})")
            device.connect()
        }
    }

    protected fun observe(device: T) {
        _deviceFlow.update {
            it.withBaseDevice(it.baseDevice.copy(mac = device.mac))
        }

        device.observeAdvertisingPackets(serialNumber, device.mac) { advertisement ->
            castToAdvertisementType(advertisement)?.let {
                handleAdvertisingPackets(device, it)
            }
        }

        device.observeConnectionState { state ->
            _deviceFlow.update { handleConnectionState(device, state, it) }
            if (state == DeviceConnectionState.CONNECTED) {
                _nodeConnectionFlow.emit(setOf(device.nodeParent.id))
            }
        }

        device.observeOutOfRange(OUT_OF_RANGE_TIMEOUT) {
            _deviceFlow.update { handleOutOfRange(it) }
        }

        device.observeRemoteRssi { rssi ->
            _deviceFlow.update { handleRemoteRssi(device, rssi, it) }
        }
    }

    fun addDevice(
        device: T,
        baseDevice: DeviceInformationBleDevice,
        advertisement: D,
    ) {
        if (IGNORE_GAUGES) return
        if (arbitrator.addDevice(device, baseDevice)) {
            handleAdvertisingPackets(device, advertisement)
            observe(device)
        }
    }

    fun addSimulatedDevice(simDevice: SIM) {
        if (simulatedDevice != null) return
        var updated = _deviceFlow.value

        simDevice.observeConnectionState { state ->
            updated = handleConnectionState(simDevice, state, updated)
        }

        simDevice.observeOutOfRange(OUT_OF_RANGE_TIMEOUT) {
            updated = handleOutOfRange(updated)
        }

        simDevice.observeAdvertisingPackets(serialNumber, simDevice.mac) { advertisement ->
            castToAdvertisementType(advertisement)?.let { typedAdv ->
                updated = updateDataFromSimulatedAdvertisement(simDevice, typedAdv, updated)
                if (settings.autoReconnect && simDevice.shouldConnect) {
                    simDevice.connect()
                }
            }
        }

        simulatedDevice = simDevice

        _deviceFlow.update {
            it.withBaseDevice(it.baseDevice.copy(mac = simDevice.mac))
        }
    }
}
