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
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import inc.combustion.framework.ble.device.*
import inc.combustion.framework.ble.device.ProbeBleDevice
import inc.combustion.framework.ble.scanning.BaseAdvertisingData
import inc.combustion.framework.ble.scanning.DeviceScanner
import inc.combustion.framework.ble.scanning.ProbeAdvertisingData
import inc.combustion.framework.ble.scanning.RepeaterAdvertisingData
import inc.combustion.framework.service.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

internal class NetworkManager(
    private val owner: LifecycleOwner,
    private val adapter: BluetoothAdapter,
) {
    sealed class DeviceHolder {
        data class ProbeHolder(val probe: ProbeBleDevice) : DeviceHolder()
        data class RepeaterHolder(val repeater: UartBleDevice) : DeviceHolder()
    }

    sealed class LinkHolder {
        data class ProbeHolder(val probe: ProbeBleDevice) : LinkHolder()
        data class RepeatedProbeHolder(val repeatedProbe: RepeatedProbeBleDevice) : LinkHolder()
    }

    private val jobManager = JobManager()
    private val devices = hashMapOf<DeviceID, DeviceHolder>()
    private val meatNetLinks = hashMapOf<LinkID, LinkHolder>()
    private val probeManagers = hashMapOf<String, ProbeManager>()

    companion object {
        private const val FLOW_CONFIG_REPLAY = 5
        private const val FLOW_CONFIG_BUFFER = FLOW_CONFIG_REPLAY * 2

        private val mutableDiscoveredProbesFlow = MutableSharedFlow<ProbeDiscoveredEvent>(
            FLOW_CONFIG_REPLAY, FLOW_CONFIG_BUFFER, BufferOverflow.SUSPEND
        )
        internal val DISCOVERED_PROBES_FLOW = mutableDiscoveredProbesFlow.asSharedFlow()

        private val mutableNetworkEventFlow = MutableSharedFlow<NetworkEvent>(
            FLOW_CONFIG_REPLAY, FLOW_CONFIG_BUFFER, BufferOverflow.SUSPEND
        )
        internal val NETWORK_EVENT_FLOW = mutableNetworkEventFlow.asSharedFlow()

        internal val BLUETOOTH_ADAPTER_STATE_INTENT_FILTER = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        internal val BLUETOOTH_ADAPTER_STATE_RECEIVER: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                val action = intent.action
                if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR
                    )
                    when (state) {
                        BluetoothAdapter.STATE_OFF -> {
                            DeviceScanner.stop()
                            mutableNetworkEventFlow.tryEmit(NetworkEvent.ScanningOff)
                            mutableNetworkEventFlow.tryEmit(NetworkEvent.BluetoothOff)
                        }
                        BluetoothAdapter.STATE_ON -> {
                            mutableNetworkEventFlow.tryEmit(NetworkEvent.BluetoothOn)
                        }
                        else -> { }
                    }
                }
            }
        }

        var SETTINGS = DeviceManager.Settings()
    }

    internal val bluetoothIsEnabled: Boolean
        get() {
            return adapter?.isEnabled ?: false
        }

    internal var scanningForProbes: Boolean = false
        private set

    internal var dfuModeEnabled: Boolean = false
        private set

    internal val discoveredProbes: List<String>
        get() {
            return  probeManagers.keys.toList()
        }

    init {
        jobManager.addJob(owner.lifecycleScope.launch {
            collectAdvertisingData()
        })

        if(bluetoothIsEnabled) {
            startScan()
            mutableNetworkEventFlow.tryEmit(NetworkEvent.BluetoothOn)
            mutableNetworkEventFlow.tryEmit(NetworkEvent.ScanningOn)
        }
        else {
            mutableNetworkEventFlow.tryEmit(NetworkEvent.BluetoothOff)
        }
    }

    fun startScanForProbes(): Boolean {
        if(bluetoothIsEnabled) {
            scanningForProbes = true
        }

        if(scanningForProbes) {
            mutableNetworkEventFlow.tryEmit(NetworkEvent.ScanningOn)
        }

        return scanningForProbes
    }

    fun stopScanForProbes(): Boolean {
        if(bluetoothIsEnabled) {
            scanningForProbes = false
        }

        if(!scanningForProbes) {
            mutableNetworkEventFlow.tryEmit(NetworkEvent.ScanningOff)
        }

        return scanningForProbes
    }

    fun startDfuMode() {
        if(!dfuModeEnabled) {
            // disconnect any active connection
            dfuModeEnabled = true

            // disconnect everything at this level
            devices.forEach { (_, device) ->
                when(device) {
                    is DeviceHolder.ProbeHolder -> device.probe.disconnect()
                    is DeviceHolder.RepeaterHolder -> device.repeater.disconnect()
                }
            }

            mutableNetworkEventFlow.tryEmit(NetworkEvent.DfuModeOn)
        }
    }

    fun stopDfuMode() {
        if(dfuModeEnabled) {
            dfuModeEnabled = false

            // Have ProbeManager reconnect to the network nodes based on the current MeatNet
            // connection management scheme.
            probeManagers.forEach { (_, probe) ->
                probe.postDfuReconnect()
            }
            mutableNetworkEventFlow.tryEmit(NetworkEvent.DfuModeOff)
        }
    }

    fun addSimulatedProbe() {
        TODO("Need to implement simulated probes")
    }

    internal fun probeFlow(serialNumber: String): SharedFlow<Probe>? {
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

    fun clearDevices() {
        probeManagers.forEach { (_, probe) -> probe.finish() }
        probeManagers.clear()
        devices.clear()
        meatNetLinks.clear()
        mutableDiscoveredProbesFlow.tryEmit(ProbeDiscoveredEvent.DevicesCleared)
    }

    fun finish() {
        stopScan()
        clearDevices()
        jobManager.cancelJobs()
    }

    private fun startScan() {
        DeviceScanner.scan(owner)
    }

    private fun stopScan() {
        DeviceScanner.stop()
    }

    private fun manageMeatNetDevice(advertisement: BaseAdvertisingData): Boolean {
        var discoveredProbe = false
        val (serialNumber, deviceId, linkId) = when(advertisement) {
            is ProbeAdvertisingData -> {
                Triple(advertisement.probeSerialNumber, advertisement.id, ProbeBleDeviceBase.makeLinkId(advertisement))
            }
            is RepeaterAdvertisingData -> {
                Triple(advertisement.probeSerialNumber, advertisement.id, ProbeBleDeviceBase.makeLinkId(advertisement))
            }
            else -> NOT_IMPLEMENTED("Unknown type of advertising data")
        }

        // if we have not seen this device by its uid, then track in the device map.
        if(!devices.containsKey(deviceId)) {
            val deviceHolder = when(advertisement) {
                is ProbeAdvertisingData -> {
                    DeviceHolder.ProbeHolder(
                        ProbeBleDevice(advertisement.mac, owner, advertisement, adapter)
                    )
                }
                is RepeaterAdvertisingData -> {
                    DeviceHolder.RepeaterHolder(
                        UartBleDevice(advertisement.mac, owner, advertisement, adapter)
                    )
                }
                else -> NOT_IMPLEMENTED("Unknown type of advertising data")
            }

            devices[deviceId] = deviceHolder
        }

        // if we haven't seen this serial number, then create a manager for it
        if(!probeManagers.containsKey(serialNumber)) {
            probeManagers[serialNumber] = ProbeManager(serialNumber, owner, SETTINGS)
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
                    probeManger?.addProbe(device.probe)
                }
                is DeviceHolder.RepeaterHolder -> {
                    val repeaterAdvertisement = advertisement as RepeaterAdvertisingData
                    val repeatedProbe = RepeatedProbeBleDevice(repeaterAdvertisement, device.repeater)
                    meatNetLinks[linkId] = LinkHolder.RepeatedProbeHolder(repeatedProbe)
                    probeManger?.addRepeatedProbe(repeatedProbe)
                }
                else -> NOT_IMPLEMENTED("Unknown type of device holder")
            }
        }

        return discoveredProbe
    }

    private suspend fun collectAdvertisingData() {
        DeviceScanner.advertisements.collect {
            if(scanningForProbes) {
                when(it) {
                    is ProbeAdvertisingData -> {
                        if(manageMeatNetDevice(it)) {
                            mutableDiscoveredProbesFlow.emit(
                                ProbeDiscoveredEvent.ProbeDiscovered(it.probeSerialNumber)
                            )
                        }
                    }
                    is RepeaterAdvertisingData -> {
                        if(it.probeSerialNumber != "0") {
                            if(manageMeatNetDevice(it)) {
                                mutableDiscoveredProbesFlow.emit(
                                    ProbeDiscoveredEvent.ProbeDiscovered(it.probeSerialNumber)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}