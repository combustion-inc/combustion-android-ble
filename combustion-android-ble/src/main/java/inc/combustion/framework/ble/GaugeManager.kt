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
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.ProbeManager.Companion.OUT_OF_RANGE_TIMEOUT
import inc.combustion.framework.ble.device.DeviceID
import inc.combustion.framework.ble.device.DeviceInformationBleDevice
import inc.combustion.framework.ble.device.GaugeBleDevice
import inc.combustion.framework.ble.device.SimulatedGaugeBleDevice
import inc.combustion.framework.ble.scanning.GaugeAdvertisingData
import inc.combustion.framework.service.DeviceConnectionState
import inc.combustion.framework.service.DeviceManager
import inc.combustion.framework.service.FirmwareVersion
import inc.combustion.framework.service.Gauge
import inc.combustion.framework.service.ModelInformation
import inc.combustion.framework.service.ProbeUploadState
import inc.combustion.framework.service.SessionInformation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class GaugeManager(
    mac: String,
    serialNumber: String,
    val owner: LifecycleOwner,
    private val settings: DeviceManager.Settings,
    private val dfuDisconnectedNodeCallback: (DeviceID) -> Unit,
) {
    companion object {
        private const val IGNORE_GAUGES = false
    }

    // encapsulates logic for managing network data links
    private val arbitrator = GaugeDataLinkArbitrator()

    // holds the current state and data for this probe
    private val _gauge = MutableStateFlow(Gauge.create(serialNumber = serialNumber, mac = mac))

    // the flow that is consumed to get state and date updates
    val gaugeFlow = _gauge.asStateFlow()

    val gauge: Gauge
        get() {
            return _gauge.value
        }

    // current session information for the probe
    var sessionInfo: SessionInformation? = null
        private set

    // current upload state for this probe, determined by LogManager
    var uploadState: ProbeUploadState
        get() {
            return _gauge.value.uploadState
        }
        set(value) {
            if (value != _gauge.value.uploadState) {
                _gauge.update { it.copy(uploadState = value) }
            }
        }

    val connectionState: DeviceConnectionState
        get() {
            // if this is a simulated probe, no need to arbitrate
            simulatedGauge?.let {
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
    val serialNumber: String
        get() {
            return _gauge.value.serialNumber
        }

    // tracks if we've run into a message timeout condition getting
    // session info
    private var sessionInfoTimeout: Boolean = false

    // TODO : implement

    private var simulatedGauge: SimulatedGaugeBleDevice? = null

    fun connect() {
//        arbitrator.getNodesNeedingConnection(true).forEach { node ->
//            node.connect()
//        }

        simulatedGauge?.shouldConnect = true
        simulatedGauge?.connect()
    }

    fun disconnect(canDisconnectFromMeatNetDevices: Boolean = false) {
//        arbitrator.getNodesNeedingDisconnect(
////            canDisconnectFromMeatNetDevices = canDisconnectFromMeatNetDevices
////        ).forEach { node ->
////            Log.d(LOG_TAG, "ProbeManager: disconnecting $node")
////
////            // Since this is an explicit disconnect, we want to also disable the auto-reconnect flag
////            arbitrator.setShouldAutoReconnect(false)
////            node.disconnect()
////        }

//        arbitrator.directLinkDiscoverTimestamp = null

        simulatedGauge?.shouldConnect = false
        simulatedGauge?.disconnect()
    }

    private fun updateDataFromAdvertisement(
        advertisement: GaugeAdvertisingData,
        currentGauge: Gauge,
    ): Gauge {
        var updatedGauge = currentGauge.copy(
            baseDevice = _gauge.value.baseDevice.copy(rssi = advertisement.rssi),
        )

        // TODO

        return updatedGauge
    }

    fun addGauge(
        gauge: GaugeBleDevice,
        baseDevice: DeviceInformationBleDevice,
        advertisement: GaugeAdvertisingData
    ) {
        if (IGNORE_GAUGES) return

        if (arbitrator.addDevice(gauge, baseDevice)) {
            handleAdvertisingPackets(gauge, advertisement)
            observe(gauge)
//            Log.i(LOG_TAG, "PM($serialNumber) is managing link ${gauge.linkId}")
        }
    }

    fun addSimulatedGauge(simGauge: SimulatedGaugeBleDevice) {
        if (simulatedGauge != null) return
        var updatedGauge = _gauge.value

        // process simulated connection state changes
//        simGauge.observeConnectionState { state ->
//            updatedGauge = handleConnectionState(simProbe, state, updatedProbe)
//        }

        // process simulated out of range
//        simGauge.observeOutOfRange(OUT_OF_RANGE_TIMEOUT) {
//            updatedGauge = handleOutOfRange(updatedProbe)
//        }

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
        Log.v(LOG_TAG, "GaugeManager.handleAdvertisingPackets($device, $advertisement)")
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
                    baseDevice = _gauge.value.baseDevice.copy(connectionState = state)
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
        device: GaugeBleDevice,
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
        fetchSessionInfo()
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

    private fun fetchSessionInfo() {
        // TODO
//        simulatedGauge?.let { device ->
//            if (sessionInfo == null) {
//                device.sendSessionInformationRequest { status, info ->
//                    if (status && info is SessionInformation) {
//                        sessionInfo = info
//                        _gauge.update { it.copy(sessionInfo = info) }
//                    }
//                }
//            }
//        }
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

    private fun observe(base: GaugeBleDevice) {
        _gauge.update {
            it.copy(
                baseDevice = it.baseDevice
            )
        }

        base.observeAdvertisingPackets(serialNumber, base.mac) { advertisement ->
            if (advertisement is GaugeAdvertisingData) {
                handleAdvertisingPackets(base, advertisement)
            }
        }

        base.observeConnectionState { state ->
            _gauge.update { handleConnectionState(base, state, it) }
        }

        base.observeOutOfRange(OUT_OF_RANGE_TIMEOUT) {
            _gauge.update { handleOutOfRange(it) }
        }

        // TODO
//        base.observeProbeStatusUpdates(hopCount = base.hopCount) { status, hopCount ->
//            handleProbeStatus(
//                status,
//                hopCount
//            )
//        }

        base.observeRemoteRssi { rssi ->
            _gauge.update { handleRemoteRssi(base, rssi, it) }
        }
    }
}