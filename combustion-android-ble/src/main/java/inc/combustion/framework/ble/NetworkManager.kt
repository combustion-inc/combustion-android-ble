/*
 * Project: Combustion Inc. Android Example
 * File: NetworkManager.kt
 * Author:
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
import inc.combustion.framework.ble.scanning.DeviceScanner
import inc.combustion.framework.ble.scanning.ProbeAdvertisingData
import inc.combustion.framework.ble.scanning.RepeaterAdvertisingData
import inc.combustion.framework.service.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.*

internal class NetworkManager(
    private val owner: LifecycleOwner,
    private val adapter: BluetoothAdapter?
) {
    private val jobManager = JobManager()

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
                            mutableNetworkEventFlow.tryEmit(NetworkEvent.ScanningOff)
                            mutableNetworkEventFlow.tryEmit(NetworkEvent.BluetoothOff)
                        }
                        BluetoothAdapter.STATE_TURNING_OFF -> {
                            LegacyDeviceScanner.stopProbeScanning()
                        }
                        BluetoothAdapter.STATE_ON -> {
                            mutableNetworkEventFlow.tryEmit(NetworkEvent.BluetoothOn)
                        }
                        BluetoothAdapter.STATE_TURNING_ON -> {}
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
            return TODO() // _probes.keys.toList()
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

    fun startDfuMode(): Boolean {
        if(!dfuModeEnabled) {
            // disconnect any active connection
            dfuModeEnabled = true
            mutableNetworkEventFlow.tryEmit(NetworkEvent.DfuModeOn)
        }
        TODO()
    }

    fun stopDfuMode(): Boolean {
        if(dfuModeEnabled) {
            // try to reconnect to everything in our collections
            dfuModeEnabled = false
            mutableNetworkEventFlow.tryEmit(NetworkEvent.DfuModeOff)
        }
        TODO()
    }

    fun addSimulatedProbe() {
        TODO()
        /*
        // Create SimulatedLegacyProbeManager
        val simulatedProbeManager = SimulatedLegacyProbeManager.create(this@CombustionService,
            (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter)

        // Add to probe list
        _probes[simulatedProbeManager.probe.serialNumber] = simulatedProbeManager

        // emit into the discovered probes flow
        lifecycleScope.launch {
            mutableDiscoveredProbesFlow.emit(
                ProbeDiscoveredEvent.ProbeDiscovered(simulatedProbeManager.probe.serialNumber)
            )
        }
         */
    }

    internal fun probeFlow(serialNumber: String): SharedFlow<Probe>? {
        //_probes[serialNumber]?.probeStateFlow
        //TODO()
        return null
    }

    internal fun probeState(serialNumber: String): Probe? {
        //_probes[serialNumber]?.probe
        TODO()
    }

    internal fun connect(serialNumber: String) {
        // _probes[serialNumber]?.connect()
        TODO()
    }

    internal fun disconnect(serialNumber: String) {
        // _probes[serialNumber]?.disconnect()
        TODO()
    }

    internal fun setProbeColor(serialNumber: String, color: ProbeColor, completionHandler: (Boolean) -> Unit) {
        /*
        _probes[serialNumber]?.sendSetProbeColor(color, completionHandler) ?: run {
            completionHandler(false)
        }
         */
        TODO()
    }

    internal fun setProbeID(serialNumber: String, id: ProbeID, completionHandler: (Boolean) -> Unit) {
        /*
        _probes[serialNumber]?.sendSetProbeID(id, completionHandler) ?: run {
            completionHandler(false)
        }
         */
        TODO()
    }

    internal fun setRemovalPrediction(serialNumber: String, removalTemperatureC: Double, completionHandler: (Boolean) -> Unit) {
        /*
        _probes[serialNumber]?.sendSetPrediction(removalTemperatureC, ProbePredictionMode.TIME_TO_REMOVAL, completionHandler) ?: run {
            completionHandler(false)
        }
         */
        TODO()
    }

    internal fun cancelPrediction(serialNumber: String, completionHandler: (Boolean) -> Unit) {
        /*
        _probes[serialNumber]?.sendSetPrediction(DeviceManager.MINIMUM_PREDICTION_SETPOINT_CELSIUS, ProbePredictionMode.NONE, completionHandler) ?: run {
            completionHandler(false)
        }
         */
        TODO()
    }

    fun clearDevices() {
        /*
        _probes.forEach { (_, probe) -> probe.finish() }
        _probes.clear()
         */
        mutableDiscoveredProbesFlow.tryEmit(ProbeDiscoveredEvent.DevicesCleared)
        TODO()
    }

    fun finish() {
        stopScan()
        jobManager.cancelJobs()
    }

    private fun startScan() {
        DeviceScanner.scan(owner)
    }

    private fun stopScan() {
        DeviceScanner.stop()
    }

    private suspend fun collectAdvertisingData() {
        DeviceScanner.advertisements.collect {
            if(scanningForProbes) {
                when(it) {
                    is ProbeAdvertisingData -> {
                        mutableDiscoveredProbesFlow.emit(
                            ProbeDiscoveredEvent.ProbeDiscovered(it.probeSerialNumber)
                        )
                    }
                    is RepeaterAdvertisingData -> {
                        if(it.probeSerialNumber != "0") {
                            mutableDiscoveredProbesFlow.emit(
                                ProbeDiscoveredEvent.ProbeDiscovered(it.probeSerialNumber)
                            )
                        }
                    }
                }
            }
            /*
            if(scanningForProbes) {
                when(it) {
                    is ProbeAdvertisingData -> {
                        // see if we are currently tracking it.serial number
                        // if not, then create a new probe manager for it.serial
                        // create a ProbeBleDevice for it.serial
                        // add it to the new Probe manager

                        // if we created a new ProbeBleDeive
                        /*
                            _discoveredProbesFlow.emit(
                                ProbeDiscoveredEvent.ProbeDiscovered(it.serialNumber)
                            )

                         */
                    }
                    is RepeaterAdvertisingData -> {
                        // see if we are currently tracking it.serial number
                        // if not, then create a new probe manager for it.serial
                        // create a RepeatedProbeBleDevice for it.serial
                        // add it to the new Probe manager

                        // if we created a new ProbeBleDeive
                        /*
                        _discoveredProbesFlow.emit(
                            ProbeDiscoveredEvent.ProbeDiscovered(it.serialNumber)
                        )
                         */
                    }
                }
            }
             */
        }
    }
}