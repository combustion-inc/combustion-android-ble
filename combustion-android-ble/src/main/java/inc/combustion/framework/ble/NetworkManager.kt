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

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.device.DeviceID
import inc.combustion.framework.ble.device.DeviceInformationBleDevice
import inc.combustion.framework.ble.device.GaugeBleDevice
import inc.combustion.framework.ble.device.LinkID
import inc.combustion.framework.ble.device.NodeBleDevice
import inc.combustion.framework.ble.device.NodeHybridDevice
import inc.combustion.framework.ble.device.ProbeBleDevice
import inc.combustion.framework.ble.device.ProximityDevice
import inc.combustion.framework.ble.device.RepeatedProbeBleDevice
import inc.combustion.framework.ble.device.SimulatedGaugeBleDevice
import inc.combustion.framework.ble.device.SimulatedProbeBleDevice
import inc.combustion.framework.ble.device.UartCapableProbe
import inc.combustion.framework.ble.scanning.DeviceAdvertisingData
import inc.combustion.framework.ble.scanning.DeviceScanner
import inc.combustion.framework.ble.scanning.GaugeAdvertisingData
import inc.combustion.framework.ble.scanning.ProbeAdvertisingData
import inc.combustion.framework.ble.uart.meatnet.GenericNodeRequest
import inc.combustion.framework.ble.uart.meatnet.GenericNodeResponse
import inc.combustion.framework.ble.uart.meatnet.NodeReadFeatureFlagsResponse
import inc.combustion.framework.ble.uart.meatnet.NodeUARTMessage
import inc.combustion.framework.log.LogManager
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.CombustionProductType.GAUGE
import inc.combustion.framework.service.CombustionProductType.NODE
import inc.combustion.framework.service.CombustionProductType.PROBE
import inc.combustion.framework.service.DeviceConnectionState
import inc.combustion.framework.service.DeviceDiscoveryEvent
import inc.combustion.framework.service.DeviceInProximityEvent
import inc.combustion.framework.service.DeviceManager
import inc.combustion.framework.service.FirmwareState
import inc.combustion.framework.service.FoodSafeData
import inc.combustion.framework.service.Gauge
import inc.combustion.framework.service.HighLowAlarmStatus
import inc.combustion.framework.service.NetworkState
import inc.combustion.framework.service.Probe
import inc.combustion.framework.service.ProbeColor
import inc.combustion.framework.service.ProbeID
import inc.combustion.framework.service.ProbePowerMode
import inc.combustion.framework.service.ProbePredictionMode
import inc.combustion.framework.service.utils.StateFlowMutableMap
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private val SPECIALIZED_DEVICES = setOf(PROBE, GAUGE)

private val SUPPORTED_ADVERTISING_PRODUCTS = setOf(
    PROBE,
    NODE,
    GAUGE,
)

internal class NetworkManager(
    private var owner: LifecycleOwner,
    private var adapter: BluetoothAdapter,
    private var settings: DeviceManager.Settings,
) {
    private sealed class DeviceHolder {
        data class ProbeHolder(val probe: ProbeBleDevice) : DeviceHolder()
        data class RepeaterHolder(val repeater: NodeBleDevice) : DeviceHolder()
    }

    private sealed class LinkHolder {
        data class ProbeHolder(val probe: ProbeBleDevice) : LinkHolder()
        data class RepeatedProbeHolder(val repeatedProbe: RepeatedProbeBleDevice) : LinkHolder()
    }

    internal data class FlowHolder(
        val mutableDiscoveredDevicesFlow: MutableSharedFlow<DeviceDiscoveryEvent>,
        val mutableNetworkState: MutableStateFlow<NetworkState>,
        val mutableFirmwareUpdateState: MutableStateFlow<FirmwareState>,
        val mutableDeviceInProximityFlow: MutableSharedFlow<DeviceInProximityEvent>,
        val mutableGenericNodeMessageFlow: MutableSharedFlow<NodeUARTMessage>,
    )

    private inner class NodeDeviceManager(
        val owner: LifecycleOwner,
    ) {
        private val connectedNodes = StateFlowMutableMap<String, NodeBleDevice>()
        private val ignoredNodes: MutableSet<String> = mutableSetOf()
        private val nodeRequestMutexMap: MutableMap<String, Semaphore> = ConcurrentHashMap()
        private val coroutineScope = CoroutineScope(SupervisorJob())

        val discoveredNodesFlow: StateFlow<List<String>> by lazy {
            val mutableDiscoveredNodesFlow = MutableStateFlow<List<String>>(emptyList())
            connectedNodes.stateFlow.onEach { nodes ->
                mutableDiscoveredNodesFlow.value = nodes.keys.toList()
            }.launchIn(coroutineScope)
            mutableDiscoveredNodesFlow.asStateFlow()
        }

        private fun getNodeRequestMutex(deviceId: String): Semaphore =
            nodeRequestMutexMap[deviceId] ?: (Semaphore(1).also {
                nodeRequestMutexMap[deviceId] = it
            })

        suspend fun sendNodeRequest(
            deviceId: String,
            request: GenericNodeRequest,
            completionHandler: (Boolean, GenericNodeResponse?) -> Unit,
        ) {
            connectedNodes[deviceId]?.let {
                val mutex = getNodeRequestMutex(deviceId)
                mutex.acquire()
                it.sendNodeRequest(request) { status, data ->
                    mutex.release()
                    completionHandler(status, data as? GenericNodeResponse)
                }
            } ?: run {
                completionHandler(false, null)
            }
        }

        fun subscribeToNodeFlow(probeManager: BleManager) {
            owner.lifecycleScope.launch(
                CoroutineName("NodeConnectionFlow")
            ) {
                probeManager.nodeConnectionFlow
                    .collect { deviceIds ->
                        deviceIds.forEach { deviceId ->
                            when (val node = devices[deviceId]) {
                                is DeviceHolder.ProbeHolder -> NOT_IMPLEMENTED("Unsupported device type")
                                is DeviceHolder.RepeaterHolder -> {
                                    updateConnectedNodes(node.repeater)
                                }

                                else -> NOT_IMPLEMENTED("Unknown device type")
                            }
                        }
                    }
            }
        }

        fun discoveredNodes(): List<String> = discoveredNodesFlow.value

        private suspend fun updateConnectedNodes(node: NodeBleDevice) {
            if (node.deviceInfoSerialNumber == null) {
                node.readSerialNumber()
            }
            node.deviceInfoSerialNumber?.let {
                if (!connectedNodes.containsKey(it) && !ignoredNodes.contains(it)) {
                    node.sendFeatureFlagRequest(null) { success: Boolean, data: Any? ->
                        if (success) {
                            val featureFlags =
                                data as NodeReadFeatureFlagsResponse
                            if (featureFlags.wifi) {
                                Log.d(
                                    LOG_TAG,
                                    "Node $it supports WiFi feature flag: add to connectedNodes",
                                )
                                connectedNodes[it] = node
                                UUID.randomUUID().toString().let { key ->
                                    node.observeDisconnected(key) {
                                        Log.d(
                                            LOG_TAG,
                                            "Node $it disconnected: remove from connectedNodes",
                                        )
                                        connectedNodes.remove(it)
                                        node.removeDisconnectedObserver(key)
                                    }
                                }
                            } else {
                                Log.d(LOG_TAG, "Node doesn't support WiFi feature flag: $it")
                                ignoredNodes.add(it)
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val FLOW_CONFIG_NO_REPLAY = 0
        private const val FLOW_CONFIG_REPLAY = 5
        private const val FLOW_CONFIG_BUFFER = FLOW_CONFIG_REPLAY * 2

        // when a repeater doesn't have connections, it uses the following serial number in its advertising data.
        internal const val REPEATER_NO_PROBES_SERIAL_NUMBER = "0"

        private lateinit var INSTANCE: NetworkManager
        private val initialized = AtomicBoolean(false)

        internal var flowHolder = FlowHolder(
            mutableDiscoveredDevicesFlow = MutableSharedFlow(
                FLOW_CONFIG_REPLAY, FLOW_CONFIG_BUFFER, BufferOverflow.SUSPEND
            ),
            mutableNetworkState = MutableStateFlow(NetworkState()),
            mutableFirmwareUpdateState = MutableStateFlow(FirmwareState(listOf())),
            mutableDeviceInProximityFlow = MutableSharedFlow(
                FLOW_CONFIG_REPLAY, FLOW_CONFIG_BUFFER, BufferOverflow.SUSPEND
            ),
            mutableGenericNodeMessageFlow = MutableSharedFlow(
                FLOW_CONFIG_NO_REPLAY, FLOW_CONFIG_BUFFER, BufferOverflow.DROP_OLDEST
            ),
        )

        fun initialize(
            owner: LifecycleOwner,
            adapter: BluetoothAdapter,
            settings: DeviceManager.Settings,
        ) {
            if (!initialized.getAndSet(true)) {
                INSTANCE = NetworkManager(owner, adapter, settings)
                _instanceFlow.value = INSTANCE
            } else {
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
                if (!initialized.get()) {
                    throw IllegalStateException("NetworkManager has not been initialized")
                }

                return INSTANCE
            }

        private val _instanceFlow = MutableStateFlow<NetworkManager?>(null)
        val instanceFlow: StateFlow<NetworkManager?> = _instanceFlow.asStateFlow()

        val discoveredDevicesFlow: SharedFlow<DeviceDiscoveryEvent>
            get() {
                return flowHolder.mutableDiscoveredDevicesFlow.asSharedFlow()
            }

        val networkStateFlow: StateFlow<NetworkState>
            get() {
                return flowHolder.mutableNetworkState.asStateFlow()
            }

        val firmwareUpdateStateFlow: StateFlow<FirmwareState>
            get() {
                return flowHolder.mutableFirmwareUpdateState.asStateFlow()
            }

        val deviceProximityFlow: SharedFlow<DeviceInProximityEvent>
            get() {
                return flowHolder.mutableDeviceInProximityFlow.asSharedFlow()
            }

        val genericNodeMessageFlow: SharedFlow<NodeUARTMessage>
            get() {
                return flowHolder.mutableGenericNodeMessageFlow.asSharedFlow()
            }
    }

    private val jobManager = JobManager()
    private val devices = hashMapOf<DeviceID, DeviceHolder>()
    private val meatNetLinks = hashMapOf<LinkID, LinkHolder>()
    private val probeManagers = hashMapOf<String, ProbeManager>()
    private val gaugeManagers = hashMapOf<String, GaugeManager>()
    private val deviceInformationDevices = hashMapOf<DeviceID, DeviceInformationBleDevice>()
    private var proximityDevices = hashMapOf<String, ProximityDevice>()
    private var nodeDeviceManager = NodeDeviceManager(owner)

    var deviceAllowlist: Set<String>? = settings.probeAllowlist
        private set

    internal val bluetoothAdapterStateIntentFilter =
        IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
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
                        if (scanningForDevices) {
                            DeviceScanner.stop()
                        }
                        flowHolder.mutableNetworkState.value = NetworkState()
                    }

                    BluetoothAdapter.STATE_ON -> {
                        if (scanningForDevices) {
                            DeviceScanner.scan(owner)
                        }
                        flowHolder.mutableNetworkState.value =
                            flowHolder.mutableNetworkState.value.copy(bluetoothOn = true)
                    }

                    else -> {}
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

    internal var scanningForDevices: Boolean = false
        private set

    internal var dfuModeEnabled: Boolean = false
        private set

    internal val discoveredProbes: List<String>
        get() {
            return probeManagers.keys.toList()
        }

    internal val discoveredGauges: List<String>
        get() {
            return gaugeManagers.keys.toList()
        }

    internal val discoveredSpecializedDevices: List<String>
        get() {
            return discoveredProbes + discoveredGauges
        }

    internal val discoveredNodes: List<String>
        get() {
            return nodeDeviceManager.discoveredNodes()
        }

    internal val discoveredNodesFlow: StateFlow<List<String>>
        get() = nodeDeviceManager.discoveredNodesFlow

    fun setDeviceAllowlist(allowlist: Set<String>?) {
        // Make sure that the new allowlist is set before we get another advertisement sneak in and
        // try to kick off a connection to a probe that is no longer in the allowlist.
        deviceAllowlist = allowlist

        Log.i(LOG_TAG, "Setting the probe allowlist to $deviceAllowlist")

        // If the allowlist is null, we don't want to remove any probes.
        if (allowlist == null) {
            return
        }

        // We want to remove probes that are in the discoveredProbes list but not the allowlist.
        discoveredSpecializedDevices.filterNot {
            allowlist.contains(it)
        }.forEach { serialNumber ->
            unlinkDevice(serialNumber)
        }
    }

    fun startScanForDevices(): Boolean {
        if (bluetoothIsEnabled) {
            scanningForDevices = true
        }

        if (scanningForDevices) {
            DeviceScanner.scan(owner)
            flowHolder.mutableNetworkState.value =
                flowHolder.mutableNetworkState.value.copy(scanningOn = true)
        }

        return scanningForDevices
    }

    fun stopScanForDevices(): Boolean {
        if (bluetoothIsEnabled) {
            scanningForDevices = false
        }

        if (!scanningForDevices) {
            flowHolder.mutableNetworkState.value =
                flowHolder.mutableNetworkState.value.copy(scanningOn = false)
        }

        return scanningForDevices
    }

    fun startDfuMode() {
        if (!dfuModeEnabled) {

            // if MeatNet is managing network, then stop returning probe scan results
            if (settings.meatNetEnabled) {
                stopScanForDevices()
            }

            // disconnect any active connection
            dfuModeEnabled = true

            // disconnect everything at this level
            devices.forEach { (_, device) ->
                when (device) {
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

            flowHolder.mutableNetworkState.value =
                flowHolder.mutableNetworkState.value.copy(dfuModeOn = true)
        }
    }

    fun stopDfuMode() {
        if (dfuModeEnabled) {
            dfuModeEnabled = false

            // if MeatNet is managing network, then start returning probe scan results
            if (settings.meatNetEnabled) {
                startScanForDevices()
            }

            // take all of the devices out of DFU mode.  we will automatically reconnect to
            // them as they are discovered during scanning
            devices.forEach { (_, device) ->
                when (device) {
                    is DeviceHolder.ProbeHolder -> {
                        device.probe.isInDfuMode = false
                    }

                    is DeviceHolder.RepeaterHolder -> {
                        device.repeater.isInDfuMode = false
                    }
                }
            }

            flowHolder.mutableNetworkState.value =
                flowHolder.mutableNetworkState.value.copy(dfuModeOn = false)
        }
    }

    fun addSimulatedProbe() {
        val probe = SimulatedProbeBleDevice(owner)
        val manager = ProbeManager(
            serialNumber = probe.serialNumber,
            owner = owner,
            settings = settings,
            dfuDisconnectedNodeCallback = { }
        )

        probeManagers[manager.serialNumber] = manager
        LogManager.instance.manageProbe(owner, manager)

        manager.addSimulatedProbe(probe)

        owner.lifecycleScope.launch {
            flowHolder.mutableDiscoveredDevicesFlow.emit(
                DeviceDiscoveryEvent.ProbeDiscovered(probe.serialNumber)
            )
        }
    }

    fun addSimulatedGauge() {
        val gauge = SimulatedGaugeBleDevice(owner)
        val manager = GaugeManager(
            mac = gauge.mac,
            serialNumber = gauge.serialNumber,
            owner = owner,
            settings = settings,
            dfuDisconnectedNodeCallback = { }
        )

        gaugeManagers[manager.serialNumber] = manager
        LogManager.instance.manageGauge(owner, manager)

        manager.addSimulatedGauge(gauge)

        owner.lifecycleScope.launch {
            flowHolder.mutableDiscoveredDevicesFlow.emit(
                DeviceDiscoveryEvent.GaugeDiscovered(gauge.serialNumber)
            )
        }
    }

    internal fun probeFlow(serialNumber: String): StateFlow<Probe>? {
        return probeManagers[serialNumber]?.deviceFlow
    }

    internal fun probeState(serialNumber: String): Probe? {
        return probeManagers[serialNumber]?.device
    }

    internal fun gaugeFlow(serialNumber: String): StateFlow<Gauge>? {
        return gaugeManagers[serialNumber]?.deviceFlow
    }

    internal fun gaugeState(serialNumber: String): Gauge? {
        return gaugeManagers[serialNumber]?.device
    }

    internal fun deviceSmoothedRssiFlow(serialNumber: String): StateFlow<Double?>? {
        return proximityDevices[serialNumber]?.probeSmoothedRssiFlow
    }

    internal fun connect(serialNumber: String) {
        probeManagers[serialNumber]?.connect()
        gaugeManagers[serialNumber]?.connect()
    }

    internal fun disconnect(serialNumber: String, canDisconnectMeatNetDevices: Boolean = false) {
        probeManagers[serialNumber]?.disconnect(canDisconnectMeatNetDevices)
        gaugeManagers[serialNumber]?.disconnect()
    }

    internal fun setProbeColor(
        serialNumber: String,
        color: ProbeColor,
        completionHandler: (Boolean) -> Unit,
    ) {
        probeManagers[serialNumber]?.setProbeColor(color, completionHandler) ?: run {
            completionHandler(false)
        }
    }

    internal fun setProbeID(
        serialNumber: String,
        id: ProbeID,
        completionHandler: (Boolean) -> Unit,
    ) {
        probeManagers[serialNumber]?.setProbeID(id, completionHandler) ?: run {
            completionHandler(false)
        }
    }

    internal fun setRemovalPrediction(
        serialNumber: String,
        removalTemperatureC: Double,
        completionHandler: (Boolean) -> Unit,
    ) {
        probeManagers[serialNumber]?.setPrediction(
            removalTemperatureC,
            ProbePredictionMode.TIME_TO_REMOVAL,
            completionHandler,
        ) ?: run {
            completionHandler(false)
        }
    }

    internal fun cancelPrediction(serialNumber: String, completionHandler: (Boolean) -> Unit) {
        probeManagers[serialNumber]?.setPrediction(
            DeviceManager.MINIMUM_PREDICTION_SETPOINT_CELSIUS,
            ProbePredictionMode.NONE,
            completionHandler,
        ) ?: run {
            completionHandler(false)
        }
    }

    internal fun configureFoodSafe(
        serialNumber: String,
        foodSafeData: FoodSafeData,
        completionHandler: (Boolean) -> Unit,
    ) {
        probeManagers[serialNumber]?.configureFoodSafe(foodSafeData, completionHandler) ?: run {
            completionHandler(false)
        }
    }

    internal fun resetFoodSafe(serialNumber: String, completionHandler: (Boolean) -> Unit) {
        probeManagers[serialNumber]?.resetFoodSafe(completionHandler) ?: run {
            completionHandler(false)
        }
    }

    internal fun setPowerMode(
        serialNumber: String,
        powerMode: ProbePowerMode,
        completionHandler: (Boolean) -> Unit,
    ) {
        probeManagers[serialNumber]?.setPowerMode(powerMode, completionHandler) ?: run {
            completionHandler(false)
        }
    }

    internal fun resetProbe(serialNumber: String, completionHandler: (Boolean) -> Unit) {
        probeManagers[serialNumber]?.resetProbe(completionHandler) ?: run {
            completionHandler(false)
        }
    }

    internal fun setHighLowAlarmStatus(
        serialNumber: String,
        highLowAlarmStatus: HighLowAlarmStatus,
        completionHandler: (Boolean) -> Unit,
    ) {
        gaugeManagers[serialNumber]?.setHighLowAlarmStatus(highLowAlarmStatus, completionHandler)
            ?: run {
                completionHandler(false)
            }
    }

    internal suspend fun sendNodeRequest(
        deviceId: String,
        request: GenericNodeRequest,
        completionHandler: (Boolean, GenericNodeResponse?) -> Unit,
    ) {
        nodeDeviceManager.sendNodeRequest(deviceId, request, completionHandler)
    }

    @ExperimentalCoroutinesApi
    fun clearDevices() {
        (probeManagers + gaugeManagers).forEach { (_, manager) -> manager.finish() }
        deviceInformationDevices.forEach { (_, device) -> device.finish() }
        deviceInformationDevices.clear()
        probeManagers.clear()
        gaugeManagers.clear()
        devices.clear()
        meatNetLinks.clear()
        proximityDevices.clear()

        // clear the discovered probes flow
        flowHolder.mutableDiscoveredDevicesFlow.resetReplayCache()
        flowHolder.mutableDiscoveredDevicesFlow.tryEmit(DeviceDiscoveryEvent.DevicesCleared)

        flowHolder.mutableNetworkState.value = NetworkState()
        flowHolder.mutableFirmwareUpdateState.value = FirmwareState(listOf())
    }

    fun finish() {
        DeviceScanner.stop()
        @Suppress("OPT_IN_USAGE")
        clearDevices()
        jobManager.cancelJobs()
    }

    /**
     * If we have not seen this device by its uid [deviceId], then track in the device map.
     */
    private fun trackProbeAdvertisingDeviceIfNew(
        deviceId: String,
        advertisement: ProbeAdvertisingData,
    ) {
        if (!devices.containsKey(deviceId)) {
            val deviceHolder = when (advertisement.productType) {
                PROBE -> {
                    DeviceHolder.ProbeHolder(
                        ProbeBleDevice(advertisement.mac, owner, advertisement, adapter)
                    )
                }

                NODE -> {
                    DeviceHolder.RepeaterHolder(createNodeBleDevice(advertisement))
                }

                else -> NOT_IMPLEMENTED("Unknown product type")
            }

            devices[deviceId] = deviceHolder
        }
    }

    private fun trackGaugeDeviceIfNew(
        deviceId: String,
        advertisement: GaugeAdvertisingData,
    ) {
        if (devices[deviceId].hybridDeviceChild == null) {
            val device = (devices[deviceId] as? DeviceHolder.RepeaterHolder)?.repeater
                ?: createNodeBleDevice(advertisement)
            device.createAndAssignNodeHybridDevice { nodeParent ->
                GaugeBleDevice(
                    nodeParent = nodeParent,
                    gaugeAdvertisingData = advertisement,
                )
            }
            val deviceHolder = DeviceHolder.RepeaterHolder(device)
            devices[deviceId] = deviceHolder
        }
    }

    private fun createNodeBleDevice(advertisement: DeviceAdvertisingData): NodeBleDevice {
        val device = NodeBleDevice(advertisement.mac, owner, advertisement, adapter)
        publishNodeFirmwareOnConnectedState(device.getDevice())
        return device
    }

    /**
     * if we haven't seen [probeSerialNumber], then create a manager for it.
     * @return true if new manager was created
     */
    private fun createProbeManagerIfNew(probeSerialNumber: String): Boolean =
        if (!probeManagers.containsKey(probeSerialNumber)) {
            val manager = ProbeManager(
                serialNumber = probeSerialNumber,
                owner = owner,
                settings = settings,
                // called by the ProbeManager whenever a MeatNet node is disconnected
                dfuDisconnectedNodeCallback = {
                    firmwareStateOfNetwork.remove(it)

                    // publish the list of firmware details for the network
                    flowHolder.mutableFirmwareUpdateState.value = FirmwareState(
                        nodes = firmwareStateOfNetwork.values.toList()
                    )
                }
            )

            probeManagers[probeSerialNumber] = manager
            LogManager.instance.manageProbe(owner, manager)

            nodeDeviceManager.subscribeToNodeFlow(manager)
            true
        } else {
            false
        }

    /**
     * if we haven't seen [gaugeSerialNumber], then create a manager for it.
     * @return true if new manager was created
     */
    private fun createGaugeManagerIfNew(mac: String, gaugeSerialNumber: String): Boolean =
        if (!gaugeManagers.containsKey(gaugeSerialNumber)) {
            val manager = GaugeManager(
                mac = mac,
                serialNumber = gaugeSerialNumber,
                owner = owner,
                settings = settings,
                dfuDisconnectedNodeCallback = {
                    firmwareStateOfNetwork.remove(it)

                    // publish the list of firmware details for the network
                    flowHolder.mutableFirmwareUpdateState.value = FirmwareState(
                        nodes = firmwareStateOfNetwork.values.toList()
                    )
                }
            )

            gaugeManagers[gaugeSerialNumber] = manager
            LogManager.instance.manageGauge(owner, manager)

            nodeDeviceManager.subscribeToNodeFlow(manager)
            true
        } else {
            false
        }

    private fun createManagerForDevicesWithoutProbeIfItDoesNotExist() {
        createProbeManagerIfNew(REPEATER_NO_PROBES_SERIAL_NUMBER)
    }

    /**
     * if we haven't seen link [linkId] before, then create it and add it to the right probe manager
     */
    private fun createMeatNetLinkIfNewAndAddToProbeManager(
        linkId: LinkID,
        deviceId: String,
        probeSerialNumber: String,
        advertisement: ProbeAdvertisingData,
    ) {
        if (!meatNetLinks.containsKey(linkId)) {
            // get reference to the source device for this link
            val deviceHolder = devices[deviceId]
            val probeManger = probeManagers[probeSerialNumber]
            when (deviceHolder) {
                is DeviceHolder.ProbeHolder -> {
                    meatNetLinks[linkId] = LinkHolder.ProbeHolder(deviceHolder.probe)
                    probeManger?.addProbe(
                        deviceHolder.probe,
                        deviceHolder.probe.baseDevice,
                        advertisement,
                    )
                }

                is DeviceHolder.RepeaterHolder -> {
                    val repeatedProbe = RepeatedProbeBleDevice(
                        probeSerialNumber,
                        deviceHolder.repeater,
                        advertisement,
                    )
                    meatNetLinks[linkId] = LinkHolder.RepeatedProbeHolder(repeatedProbe)
                    probeManger?.addRepeatedProbe(
                        repeatedProbe,
                        deviceHolder.repeater.getDevice(),
                        advertisement,
                    )
                }

                else -> NOT_IMPLEMENTED("Unknown type of device holder")
            }
        }
    }

    private fun createMeatNetLinkIfNewAndAddToProbelessDevicesManager(
        deviceId: String,
        advertisement: ProbeAdvertisingData,
    ) {
        createMeatNetLinkIfNewAndAddToProbeManager(
            linkId = UartCapableProbe.makeLinkId(advertisement),
            deviceId = deviceId,
            probeSerialNumber = REPEATER_NO_PROBES_SERIAL_NUMBER,
            advertisement = advertisement,
        )
    }

    private fun manageNodeWithoutProbe(
        advertisement: ProbeAdvertisingData,
    ) {
        // Not supported if MeatNet is disabled
        if (!settings.meatNetEnabled) {
            return
        }

        val deviceId = advertisement.id
        trackProbeAdvertisingDeviceIfNew(deviceId, advertisement)
        createManagerForDevicesWithoutProbeIfItDoesNotExist()
        createMeatNetLinkIfNewAndAddToProbelessDevicesManager(
            deviceId = deviceId,
            advertisement = advertisement,
        )
    }

    private fun manageMeatNetDeviceWithProbe(
        advertisement: ProbeAdvertisingData,
    ): Boolean {
        // if MeatNet isn't enabled and we see anything other than a probe, then return out now
        // because that product type isn't supported in this mode.
        if (!settings.meatNetEnabled && advertisement.productType != PROBE) {
            return false
        }

        val probeSerialNumber = advertisement.serialNumber
        val deviceId = advertisement.id
        val linkId = UartCapableProbe.makeLinkId(advertisement)

        // If the advertised probe isn't in the subscriber list, don't bother connecting
        deviceAllowlist?.let {
            if (!it.contains(probeSerialNumber)) {
                return false
            }
        }

        trackProbeAdvertisingDeviceIfNew(deviceId, advertisement)

        val isNewlyDiscovered = createProbeManagerIfNew(probeSerialNumber)

        createMeatNetLinkIfNewAndAddToProbeManager(
            linkId = linkId,
            deviceId = deviceId,
            probeSerialNumber = probeSerialNumber,
            advertisement = advertisement,
        )

        return isNewlyDiscovered
    }

    private fun manageGaugeDevice(
        advertisement: GaugeAdvertisingData,
    ): Boolean {
        val serialNumber = advertisement.serialNumber
        val deviceId = advertisement.id
        deviceAllowlist?.let {
            if (!it.contains(serialNumber)) {
                return false
            }
        }

        trackGaugeDeviceIfNew(deviceId, advertisement)
        val isNewlyDiscovered = createGaugeManagerIfNew(advertisement.mac, serialNumber)

        if (gaugeManagers[serialNumber]?.hasGauge() == false) {
            (devices[deviceId] as? DeviceHolder.RepeaterHolder)?.gauge?.let { gaugeBleDevice ->
                gaugeManagers[serialNumber]?.addGauge(
                    gauge = gaugeBleDevice,
                    baseDevice = gaugeBleDevice.baseDevice,
                    advertisement = advertisement,
                )
            }
        }

        return isNewlyDiscovered
    }

    private suspend fun collectAdvertisingData() {
        DeviceScanner.advertisements.collect { advertisingData ->
            if (scanningForDevices) {
                val serialNumber = advertisingData.serialNumber
                val productType = advertisingData.productType
                if (!SUPPORTED_ADVERTISING_PRODUCTS.contains(productType)) return@collect
                when (advertisingData) {
                    is ProbeAdvertisingData -> if (serialNumber == REPEATER_NO_PROBES_SERIAL_NUMBER) {
                        manageNodeWithoutProbe(advertisingData)
                    } else if (manageMeatNetDeviceWithProbe(advertisingData)) {
                        flowHolder.mutableDiscoveredDevicesFlow.emit(
                            DeviceDiscoveryEvent.ProbeDiscovered(
                                serialNumber
                            )
                        )
                    }

                    is GaugeAdvertisingData -> if (manageGaugeDevice(advertisingData)) {
                        flowHolder.mutableDiscoveredDevicesFlow.emit(
                            DeviceDiscoveryEvent.GaugeDiscovered(
                                serialNumber
                            )
                        )
                    }
                }

                if (SPECIALIZED_DEVICES.contains(productType)) {
                    updateDeviceProximity(serialNumber, productType, advertisingData.rssi)
                }
            }
        }
    }

    private suspend fun updateDeviceProximity(
        serialNumber: String,
        productType: CombustionProductType,
        rssi: Int
    ) {
        // If we haven't seen this probe before, then add it to our list of devices
        // that we track proximity for and publish the addition.
        if (!proximityDevices.contains(serialNumber)) {
            Log.i(LOG_TAG, "Adding ${productType.name} $serialNumber to devices in proximity")

            // Guarantee that the device has a valid RSSI value before flowing out
            // the discovery event.
            proximityDevices[serialNumber] = ProximityDevice()
            proximityDevices[serialNumber]?.handleRssiUpdate(rssi)
            when (productType) {
                PROBE -> DeviceInProximityEvent.ProbeDiscovered(serialNumber)
                GAUGE -> DeviceInProximityEvent.GaugeDiscovered(serialNumber)
                else -> null
            }?.let { inProximityEvent ->
                flowHolder.mutableDeviceInProximityFlow.emit(inProximityEvent)
            }
        } else {
            // Update the smoothed RSSI value for this device
            proximityDevices[serialNumber]?.handleRssiUpdate(rssi)
        }
    }

    private fun publishNodeFirmwareOnConnectedState(
        device: DeviceInformationBleDevice,
        onDeviceConnectedState: () -> Unit = {},
    ): Boolean = if (!deviceInformationDevices.containsKey(device.id)) {
        deviceInformationDevices[device.id] = device

        // read details once connected
        device.observeConnectionState { connectionState ->
            if (connectionState == DeviceConnectionState.CONNECTED) {
                device.firmwareVersion ?: run {
                    device.readFirmwareVersion()

                    device.firmwareVersion?.let { firmwareVersion ->
                        // Add to firmware state map
                        val node =
                            FirmwareState.Node(
                                device.id,
                                device.dfuProductType,
                                firmwareVersion
                            )
                        firmwareStateOfNetwork[device.id] = node

                        // publish the list of firmware details for the network
                        flowHolder.mutableFirmwareUpdateState.value = FirmwareState(
                            nodes = firmwareStateOfNetwork.values.toList()
                        )
                    }
                }
                onDeviceConnectedState()
            }
        }
        true
    } else {
        false
    }

    private fun initialize() {
        jobManager.addJob(
            owner.lifecycleScope.launch(CoroutineName("collectAdvertisingData")) {
                collectAdvertisingData()
            }
        )

        // if MeatNet is enabled,then automatically start and stop scanning state
        // based on current bluetooth state.  this code cannot handle application permissions
        // in that case, the client app needs to start scanning once bluetooth permissions are allowed.
        if (settings.meatNetEnabled) {
            jobManager.addJob(
                owner.lifecycleScope.launch(CoroutineName("networkStateFlow")) {
                    var bluetoothIsOn = false
                    networkStateFlow.collect {
                        if (!bluetoothIsOn && it.bluetoothOn) {
                            startScanForDevices()
                        }

                        if (bluetoothIsOn && !it.bluetoothOn) {
                            stopScanForDevices()
                        }

                        bluetoothIsOn = it.bluetoothOn
                    }
                }
            )
        }

        if (bluetoothIsEnabled) {
            flowHolder.mutableNetworkState.value =
                flowHolder.mutableNetworkState.value.copy(bluetoothOn = true)

            if (settings.meatNetEnabled) {
                // MeatNet automatically starts scanning without API call.
                DeviceScanner.scan(owner)
                startScanForDevices()
            }
        } else {
            flowHolder.mutableNetworkState.value =
                flowHolder.mutableNetworkState.value.copy(bluetoothOn = false)
        }
    }

    private fun unlinkDevice(serialNumber: String) {
        Log.i(LOG_TAG, "Unlinking device: $serialNumber")

        // Remove the device from the discovere devices list
        val deviceManager = probeManagers.remove(serialNumber) ?: gaugeManagers.remove(serialNumber)

        val deviceId = deviceManager?.device?.baseDevice?.id

        // We need to figure out which repeated probe devices (nodes, essentially) are solely
        // repeating the probe that we want to disconnect from. So, for example, from the following
        // list of link IDs to pseudo-RepeatedProbeHolders:
        //
        // "id1_sn1" to RepeatedProbeHolder("id1", "s1"),
        // "id1_sn2" to RepeatedProbeHolder("id1", "s2"),
        //
        // "id2_sn2" to RepeatedProbeHolder("id2", "s2"),
        //
        // "id3_sn1" to RepeatedProbeHolder("id3", "s1"),
        // "id3_sn2" to RepeatedProbeHolder("id3", "s2"),
        // "id3_sn3" to RepeatedProbeHolder("id3", "s3"),
        //
        // We'd want the following list of nodes to disconnect from: [id2]. To do this we get the:
        //
        // - Set of all Device IDs of nodes (repeated devices) that do provide this serial number.
        //   [id1, id2, id3] in this case
        // - Set of all Device IDs of nodes (repeated devices) that provide other serial numbers as
        //   well.
        //   [id1, id3] in this case
        //
        // The difference in these sets is the set of repeated devices that only repeat this serial
        // number.

        // Set of all Device IDs of nodes (repeated devices) that do provide this serial number
        val providers = meatNetLinks.values
            .filterIsInstance<LinkHolder.RepeatedProbeHolder>()
            .filter { it.repeatedProbe.serialNumber == serialNumber }
            .map { it.repeatedProbe.id }
            .toSet()

        // Set of all Device IDs of nodes (repeated devices) that provide other serial numbers
        val nonProviders = meatNetLinks.values
            .filterIsInstance<LinkHolder.RepeatedProbeHolder>()
            .filterNot { it.repeatedProbe.serialNumber == serialNumber }
            .map { it.repeatedProbe.id }
            .toSet()

        val soleProviders = (providers - nonProviders).mapNotNull { id ->
            meatNetLinks.values
                .filterIsInstance<LinkHolder.RepeatedProbeHolder>()
                .firstOrNull { it.repeatedProbe.id == id }
        }

        soleProviders.forEach {
            it.repeatedProbe.disconnect()
        }

        // Remove the MeatNet links supporting this probe
        meatNetLinks.values.removeIf {
            when (it) {
                is LinkHolder.ProbeHolder ->
                    it.probe.serialNumber == serialNumber

                is LinkHolder.RepeatedProbeHolder ->
                    it.repeatedProbe.serialNumber == serialNumber
            }
        }

        // Clean up the device manager's resources, disconnecting only those nodes that are sole
        // providers.
        LogManager.instance.finish(serialNumber)
        deviceManager?.finish(soleProviders.map { it.repeatedProbe.id }.toSet())

        // Remove the device from the device list--this should be the last reference to the held
        // device.
        deviceId?.let {
            devices.remove(it)
        }

        // Event out the removal
        flowHolder.mutableDiscoveredDevicesFlow.tryEmit(
            when (deviceManager) {
                is GaugeManager -> DeviceDiscoveryEvent.GaugeRemoved(serialNumber)
                else -> DeviceDiscoveryEvent.ProbeRemoved(serialNumber)
            }
        )
    }

    private val NodeBleDevice.gaugeHybridDevice: GaugeBleDevice?
        get() = hybridDeviceChild as? GaugeBleDevice

    private val DeviceHolder.RepeaterHolder.gauge: GaugeBleDevice?
        get() = repeater.gaugeHybridDevice

    private val DeviceHolder?.hybridDeviceChild: NodeHybridDevice?
        get() = (this as? DeviceHolder.RepeaterHolder)?.repeater?.hybridDeviceChild
}