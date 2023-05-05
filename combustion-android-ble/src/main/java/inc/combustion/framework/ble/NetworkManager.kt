/*
 * Project: Combustion Inc. Android Framework
 * File: NetworkManager.kt
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

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import inc.combustion.framework.ble.device.*
import inc.combustion.framework.ble.device.ProbeBleDevice
import inc.combustion.framework.ble.scanning.CombustionAdvertisingData
import inc.combustion.framework.ble.scanning.DeviceScanner
import inc.combustion.framework.log.LogManager
import inc.combustion.framework.service.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal class NetworkManager(
    private var owner: LifecycleOwner,
    private var adapter: BluetoothAdapter,
    private var settings: DeviceManager.Settings
) {
    sealed class DeviceHolder {
        data class ProbeHolder(val probe: ProbeBleDevice) : DeviceHolder()
        data class RepeaterHolder(val repeater: UartBleDevice) : DeviceHolder()
    }

    sealed class LinkHolder {
        data class ProbeHolder(val probe: ProbeBleDevice) : LinkHolder()
        data class RepeatedProbeHolder(val repeatedProbe: RepeatedProbeBleDevice) : LinkHolder()
    }

    internal data class FlowHowHolder(
        var mutableDiscoveredProbesFlow: MutableSharedFlow<ProbeDiscoveredEvent>,
        var mutableNetworkState: MutableStateFlow<NetworkState>,
        var mutableFirmwareUpdateState: MutableStateFlow<FirmwareState>,
    )

    companion object {
        private const val FLOW_CONFIG_REPLAY = 5
        private const val FLOW_CONFIG_BUFFER = FLOW_CONFIG_REPLAY * 2

        // when a repeater doesn't have connections, it uses the following serial number in its advertising data.
        internal const val REPEATER_NO_PROBES_SERIAL_NUMBER = "0"

        private lateinit var INSTANCE: NetworkManager
        private val initialized = AtomicBoolean(false)

        internal var flowHolder = FlowHowHolder(
            MutableSharedFlow(FLOW_CONFIG_REPLAY, FLOW_CONFIG_BUFFER, BufferOverflow.SUSPEND),
            MutableStateFlow(NetworkState()),
            MutableStateFlow(FirmwareState(listOf()))
        )

        fun initialize(owner: LifecycleOwner, adapter: BluetoothAdapter, settings: DeviceManager.Settings)  {
            if(!initialized.getAndSet(true)) {
                INSTANCE = NetworkManager(owner, adapter, settings)
            }
            else {
                // assumption is that CombustionService will correctly manage things as part of its
                // lifecycle, clearing this manager when the service is stopped.
                INSTANCE.owner = owner
                INSTANCE.adapter = adapter
                INSTANCE.settings = settings
            }

            INSTANCE.initialize()
        }

        val instance: NetworkManager
            get() {
                if(!initialized.get()) {
                    throw IllegalStateException("NetworkManager has not been initialized")
                }

                return INSTANCE
            }

        val discoveredProbesFlow: SharedFlow<ProbeDiscoveredEvent>
            get() {
                return flowHolder.mutableDiscoveredProbesFlow.asSharedFlow()
            }

        val networkStateFlow: StateFlow<NetworkState>
            get() {
                return flowHolder.mutableNetworkState.asStateFlow()
            }

        val firmwareUpdateStateFlow:StateFlow<FirmwareState>
            get() {
                return flowHolder.mutableFirmwareUpdateState.asStateFlow()
            }
    }

    private val jobManager = JobManager()
    private val devices = hashMapOf<DeviceID, DeviceHolder>()
    private val meatNetLinks = hashMapOf<LinkID, LinkHolder>()
    private val probeManagers = hashMapOf<String, ProbeManager>()
    private val deviceInformationDevices = hashMapOf<DeviceID, DeviceInformationBleDevice>()

    internal val bluetoothAdapterStateIntentFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
    internal val bluetoothAdapterStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            val action = intent.action
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR
                )
                when (state) {
                    BluetoothAdapter.STATE_OFF -> {
                        if(scanningForProbes) {
                            DeviceScanner.stop()
                        }
                        flowHolder.mutableNetworkState.value = NetworkState()
                    }
                    BluetoothAdapter.STATE_ON -> {
                        if(scanningForProbes) {
                            DeviceScanner.scan(owner)
                        }
                        flowHolder.mutableNetworkState.value = flowHolder.mutableNetworkState.value.copy(bluetoothOn = true)
                    }
                    else -> { }
                }
            }
        }
    }

    // map tracking the firmware of devices
    private val firmwareStateOfNetwork = hashMapOf<DeviceID, FirmwareState.Node>()

    internal val bluetoothIsEnabled: Boolean
        get() {
            return adapter.isEnabled
        }

    internal var scanningForProbes: Boolean = false
        private set

    internal var dfuModeEnabled: Boolean = false
        private set

    internal val discoveredProbes: List<String>
        get() {
            return  probeManagers.keys.toList()
        }

    fun startScanForProbes(): Boolean {
        if(bluetoothIsEnabled) {
            scanningForProbes = true
        }

        if(scanningForProbes) {
            DeviceScanner.scan(owner)
            flowHolder.mutableNetworkState.value = flowHolder.mutableNetworkState.value.copy(scanningOn = true)
        }

        return scanningForProbes
    }

    fun stopScanForProbes(): Boolean {
        if(bluetoothIsEnabled) {
            scanningForProbes = false
        }

        if(!scanningForProbes) {
            flowHolder.mutableNetworkState.value = flowHolder.mutableNetworkState.value.copy(scanningOn = false)
        }

        return scanningForProbes
    }

    fun startDfuMode() {
        if(!dfuModeEnabled) {

            // if MeatNet is managing network, then stop returning probe scan results
            if(settings.meatNetEnabled) {
                stopScanForProbes()
            }

            // disconnect any active connection
            dfuModeEnabled = true

            // disconnect everything at this level
            devices.forEach { (_, device) ->
                when(device) {
                    is DeviceHolder.ProbeHolder -> {
                        device.probe.isInDfuMode = true
                        device.probe.disconnect()
                    }
                    is DeviceHolder.RepeaterHolder -> {
                        device.repeater.isInDfuMode = true
                        device.repeater.disconnect()
                    }
                }
            }

            flowHolder.mutableNetworkState.value = flowHolder.mutableNetworkState.value.copy(dfuModeOn = true)
        }
    }

    fun stopDfuMode() {
        if(dfuModeEnabled) {
            dfuModeEnabled = false

            // if MeatNet is managing network, then start returning probe scan results
            if(settings.meatNetEnabled) {
                startScanForProbes()
            }

            // take all of the devices out of DFU mode.  we will automatically reconnect to
            // them as they are discovered during scanning
            devices.forEach { (_, device) ->
                when(device) {
                    is DeviceHolder.ProbeHolder -> {
                        device.probe.isInDfuMode = false
                    }
                    is DeviceHolder.RepeaterHolder -> {
                        device.repeater.isInDfuMode = false
                    }
                }
            }

            flowHolder.mutableNetworkState.value = flowHolder.mutableNetworkState.value.copy(dfuModeOn = false)
        }
    }

    fun addSimulatedProbe() {
        val probe = SimulatedProbeBleDevice(owner)
        val manager = ProbeManager(
            serialNumber = probe.probeSerialNumber,
            owner = owner,
            settings = settings,
            dfuConnectedNodeCallback = { },
            dfuDisconnectedNodeCallback = { }
        )

        probeManagers[manager.serialNumber] = manager
        LogManager.instance.manage(owner, manager)

        manager.addSimulatedProbe(probe)

        owner.lifecycleScope.launch {
            flowHolder.mutableDiscoveredProbesFlow.emit(
                ProbeDiscoveredEvent.ProbeDiscovered(probe.probeSerialNumber)
            )
        }
    }

    internal fun probeFlow(serialNumber: String): StateFlow<Probe>? {
        return probeManagers[serialNumber]?.probeFlow
    }

    internal fun probeState(serialNumber: String): Probe? {
        return probeManagers[serialNumber]?.probe
    }

    internal fun connect(serialNumber: String) {
        probeManagers[serialNumber]?.connect()
    }

    internal fun disconnect(serialNumber: String) {
        probeManagers[serialNumber]?.disconnect()
    }

    internal fun setProbeColor(serialNumber: String, color: ProbeColor, completionHandler: (Boolean) -> Unit) {
        probeManagers[serialNumber]?.setProbeColor(color, completionHandler) ?: run {
            completionHandler(false)
        }
    }

    internal fun setProbeID(serialNumber: String, id: ProbeID, completionHandler: (Boolean) -> Unit) {
        probeManagers[serialNumber]?.setProbeID(id, completionHandler) ?: run {
            completionHandler(false)
        }
    }

    internal fun setRemovalPrediction(serialNumber: String, removalTemperatureC: Double, completionHandler: (Boolean) -> Unit) {
        probeManagers[serialNumber]?.setPrediction(removalTemperatureC, ProbePredictionMode.TIME_TO_REMOVAL, completionHandler) ?: run {
            completionHandler(false)
        }
    }

    internal fun cancelPrediction(serialNumber: String, completionHandler: (Boolean) -> Unit) {
        probeManagers[serialNumber]?.setPrediction(DeviceManager.MINIMUM_PREDICTION_SETPOINT_CELSIUS, ProbePredictionMode.NONE, completionHandler) ?: run {
            completionHandler(false)
        }
    }

    @ExperimentalCoroutinesApi
    fun clearDevices() {
        probeManagers.forEach { (_, probe) -> probe.finish() }
        deviceInformationDevices.forEach{ (_, device) -> device.finish() }
        deviceInformationDevices.clear()
        probeManagers.clear()
        devices.clear()
        meatNetLinks.clear()

        // clear the discovered probes flow
        flowHolder.mutableDiscoveredProbesFlow.resetReplayCache()
        flowHolder.mutableDiscoveredProbesFlow.tryEmit(ProbeDiscoveredEvent.DevicesCleared)

        flowHolder.mutableNetworkState.value = NetworkState()
        flowHolder.mutableFirmwareUpdateState.value = FirmwareState(listOf())
    }

    fun finish() {
        DeviceScanner.stop()
        @Suppress("OPT_IN_USAGE")
        clearDevices()
        jobManager.cancelJobs()
    }

    private fun manageMeatNetDevice(advertisement: CombustionAdvertisingData): Boolean {
        var discoveredProbe = false
        val serialNumber = advertisement.probeSerialNumber
        val deviceId = advertisement.id
        val linkId = ProbeBleDeviceBase.makeLinkId(advertisement)

        // if MeatNet isn't enabled and we see anything other than a probe, then return out now
        // because that product type isn't supported in this mode.
        if(!settings.meatNetEnabled && advertisement.productType != CombustionProductType.PROBE) {
            return false
        }

        // if we have not seen this device by its uid, then track in the device map.
        if(!devices.containsKey(deviceId)) {
            val deviceHolder = when(advertisement.productType) {
                CombustionProductType.PROBE -> {
                    DeviceHolder.ProbeHolder(
                        ProbeBleDevice(advertisement.mac, owner, advertisement, adapter)
                    )
                }
                CombustionProductType.DISPLAY, CombustionProductType.CHARGER -> {
                    DeviceHolder.RepeaterHolder(
                        UartBleDevice(advertisement.mac, advertisement, owner, adapter)
                    )
                }
                else -> NOT_IMPLEMENTED("Unknown type of advertising data")
            }

            devices[deviceId] = deviceHolder
        }

        // if we haven't seen this serial number, then create a manager for it
        if(!probeManagers.containsKey(serialNumber)) {
             val manager = ProbeManager(
                serialNumber = serialNumber,
                owner = owner,
                settings = settings,
                // called by the ProbeManager whenever it connects to a new meatnet node,
                // providing it's firmware details
                dfuConnectedNodeCallback = {
                    // keep track of each nodes firmware details
                    if(!firmwareStateOfNetwork.containsKey(it.id)) {
                        // Add the node to the list and...
                        firmwareStateOfNetwork[it.id] = it

                        // publish the list of firmware details for the network
                        flowHolder.mutableFirmwareUpdateState.value = FirmwareState(
                            nodes = firmwareStateOfNetwork.values.toList()
                        )
                    }
                },
                // called by the ProbeManager whenever a meatnet node is disconnected
                dfuDisconnectedNodeCallback = {
                    firmwareStateOfNetwork.remove(it)

                    // publish the list of firmware details for the network
                    flowHolder.mutableFirmwareUpdateState.value = FirmwareState(
                        nodes = firmwareStateOfNetwork.values.toList()
                    )
                }
            )

            probeManagers[serialNumber] = manager
            LogManager.instance.manage(owner, manager)

            discoveredProbe = true
        }

        // if we haven't seen this link before, then create it and add it to the right probe manager
        if(!meatNetLinks.containsKey(linkId)) {
            // get reference to the source device for this link
            val device = devices[deviceId]
            val probeManger = probeManagers[serialNumber]
            when(device) {
                is DeviceHolder.ProbeHolder -> {
                    meatNetLinks[linkId] = LinkHolder.ProbeHolder(device.probe)
                    probeManger?.addProbe(device.probe, device.probe.baseDevice, advertisement)
                }
                is DeviceHolder.RepeaterHolder -> {
                    val repeatedProbe = RepeatedProbeBleDevice(serialNumber, device.repeater, advertisement)
                    meatNetLinks[linkId] = LinkHolder.RepeatedProbeHolder(repeatedProbe)
                    probeManger?.addRepeatedProbe(repeatedProbe, device.repeater, advertisement)
                }
                else -> NOT_IMPLEMENTED("Unknown type of device holder")
            }
        }

        return discoveredProbe
    }

    private suspend fun collectAdvertisingData() {
        DeviceScanner.advertisements.collect {advertisingData ->
            if(scanningForProbes) {
                if(advertisingData.probeSerialNumber != REPEATER_NO_PROBES_SERIAL_NUMBER) {
                    if(manageMeatNetDevice(advertisingData)) {
                        flowHolder.mutableDiscoveredProbesFlow.emit(
                            ProbeDiscoveredEvent.ProbeDiscovered(advertisingData.probeSerialNumber)
                        )
                    }
                }
                else {
                    if(!firmwareStateOfNetwork.containsKey(advertisingData.id)) {
                        handleMeatNetLinkWithoutProbe(advertisingData)
                    }
                }
            }
        }
    }

    private fun handleMeatNetLinkWithoutProbe(advertisement: CombustionAdvertisingData) {
        if(!deviceInformationDevices.containsKey(advertisement.id)) {
            // construct a device info so that we can read the details
            val device = DeviceInformationBleDevice(advertisement.id, advertisement, owner, adapter)
            deviceInformationDevices[device.id] = device

            // read details once connected
            device.observeConnectionState { connectionState ->
                if(connectionState == DeviceConnectionState.CONNECTED) {
                    device.firmwareVersion ?: run {
                        device.readFirmwareVersion()

                        device.firmwareVersion?.let { firmwareVersion ->
                            // Add to firmware state map
                            val node = FirmwareState.Node(device.id, device.productType, firmwareVersion)
                            firmwareStateOfNetwork[device.id] = node
                        }
                    }

                    // free up resources
                    device.finish()

                    // if we don't have the details at this point, we are going to want to try again
                    // on the next advertising packet
                    if(!firmwareStateOfNetwork.containsKey(device.id)) {
                        deviceInformationDevices.remove(device.id)
                    }
                }
            }

            // Connect to device
            if(device.isDisconnected.get() && device.isConnectable.get()) {
                device.connect()
            }
        }
    }

    private fun initialize() {
        jobManager.addJob(owner.lifecycleScope.launch {
            collectAdvertisingData()
        })

        // if MeatNet is enabled,then automatically start and stop scanning state
        // based on current bluetooth state.  this code cannot handle application permissions
        // in that case, the client app needs to start scanning once bluetooth permissions are allowed.
        if(settings.meatNetEnabled) {
            jobManager.addJob(owner.lifecycleScope.launch{
                var bluetoothIsOn = false
                networkStateFlow.collect {
                    if(!bluetoothIsOn && it.bluetoothOn) {
                        startScanForProbes()
                    }

                    if(bluetoothIsOn && !it.bluetoothOn) {
                        stopScanForProbes()
                    }

                    bluetoothIsOn = it.bluetoothOn
                }
            })
        }

        if(bluetoothIsEnabled) {
            flowHolder.mutableNetworkState.value = flowHolder.mutableNetworkState.value.copy(bluetoothOn = true)

            if(settings.meatNetEnabled) {
                // MeatNet automatically starts scanning without API call.
                DeviceScanner.scan(owner)
                startScanForProbes()
            }
        }
        else {
            flowHolder.mutableNetworkState.value = flowHolder.mutableNetworkState.value.copy(bluetoothOn = false)
        }
    }
}