/*
 * Project: Combustion Inc. Android Framework
 * File: DeviceManager.kt
 * Author: https://github.com/miwright2
 *
 * MIT License
 *
 * Copyright (c) 2022. Combustion Inc.
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
package inc.combustion.framework.service

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import inc.combustion.framework.LOG_TAG
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton instance for managing communications with Combustion Inc. devices.
 */
class DeviceManager {
    private val onBoundInitList = mutableListOf<() -> Unit>()
    private lateinit var service: CombustionService

    companion object {
        private lateinit var INSTANCE: DeviceManager
        private lateinit var app : Application
        private lateinit var onServiceBound : (deviceManager: DeviceManager) -> Unit
        private val initialized = AtomicBoolean(false)
        private val bound = AtomicBoolean(false)

        private val connection = object : ServiceConnection {

            override fun onServiceConnected(className: ComponentName, serviceBinder: IBinder) {
                val binder = serviceBinder as CombustionService.CombustionServiceBinder
                INSTANCE.service = binder.getService()

                bound.set(true)
                onServiceBound(INSTANCE)

                INSTANCE.onBoundInitList.forEach{ initCallback ->
                    initCallback()
                }
            }

            override fun onServiceDisconnected(arg0: ComponentName) {
                bound.set(false)
                bindCombustionService()
            }
        }

        /**
         * Instance property for the singleton
         */
        val instance: DeviceManager get() = INSTANCE

        /**
         * Initializes the singleton
         * @param application Application context.
         * @param onBound Lambda to be called when the service is bound to an Activity.
         */
        fun initialize(application: Application, onBound: (deviceManager: DeviceManager) -> Unit) {
            if(!initialized.getAndSet(true)) {
                app = application
                onServiceBound = onBound
                INSTANCE = DeviceManager()
            }
        }

        /**
         * Starts the Combustion Android Service
         */
        fun startCombustionService() {
            CombustionService.start(app.applicationContext)
        }

        /**
         * Binds to the Combustion Android Service
         */
        fun bindCombustionService() {
            if(!bound.get()) {
                CombustionService.bind(app.applicationContext, connection)
            }
        }

        /**
         * Unbinds from the Combustion Android Service
         */
        fun unbindCombustionService() {
            app.unbindService(connection)
            bound.set(false)
        }

        /**
         * Stops the Combustion Android Sevice
         */
        fun stopCombustionService() {
            CombustionService.stop(app.applicationContext)
        }
    }

    /**
     * Kotlin flow for collecting device discovery events that occur when the service
     * is scanning for devices.  This is a hot flow.
     *
     * @see DeviceDiscoveredEvent
     */
    val discoveredProbesFlow : SharedFlow<DeviceDiscoveredEvent>
        get() = service.discoveredProbesFlow

    /**
     * True if scanning for devices.  False otherwise.
     */
    val isScanningForDevices: Boolean
        get() = service.isScanningForProbes

    /**
     * Returns a list of device serial numbers, consisting of all devices that have been
     * discovered.
     */
    val discoveredProbes: List<String>
        get() = service.discoveredProbes

    /**
     * Registers a lambda to be called by the DeviceManager upon binding with the
     * Combustion Service and completing initialization.
     *
     * @param callback Lambda to be called by the Device Manager upon completing initialization.
     */
    fun registerOnBoundInitialization(callback : () -> Unit) {
        // if service is already bound, then run the callback right away.
        if(bound.get()) {
            callback()
        }
        // otherwise queue the callback to be run when the service is bound.
        else {
            onBoundInitList.add(callback)
        }
    }

    /**
     * Starts scanning for temperature probes.  Will generate a DeviceDiscoveredEvent
     * to the discoveredProbesFlow property.
     *
     * @return true if scanning has started, false otherwise.
     *
     * @see discoveredProbesFlow
     * @see DeviceDiscoveredEvent
     * @see DeviceDiscoveredEvent.ScanningOn
     */
    fun startScanningForProbes() = service.startScanningForProbes()

    /**
     * Stops scanning for temperature probes.  Will generate a DeviceDiscoveredEvent
     * to the discoveredProbFlow property.
     *
     * @return true if scanning has stopped, false otherwise.
     *
     * @see discoveredProbesFlow
     * @see DeviceDiscoveredEvent
     * @see DeviceDiscoveredEvent.ScanningOff
     */
    fun stopScanningForProbes() = service.stopScanningForProbes()

    /**
     * Retrieves the Kotlin flow for collecting Probe state updates for the specified
     * probe serial number.  Note, this is a hot flow.
     *
     * @param serialNumber the serial number of the probe.
     * @return Kotlin flow for collecting Probe state updates.
     *
     * @see Probe
     */
    fun probeFlow(serialNumber: String) = service.probeFlow(serialNumber)

    /**
     * Retrieves the current probe state for the specified probe serial number.
     *
     * @param serialNumber the serial number of the probe.
     * @return current ProbeState of the probe.
     *
     * @see Probe
     */
    fun probe(serialNumber: String): Probe? = service.probeState(serialNumber)

    /**
     * Initiates a BLE connection to the probe with the specified serial number.  Upon
     * connection the probe's connection state is update and that state change is
     * produced to the probe's state update flow.
     *
     * @param serialNumber the serial number of the probe.
     * @return true if the connection process was started, false otherwise.
     *
     * @see Probe
     * @see Probe.connectionState
     * @see DeviceConnectionState.CONNECTED
     * @see DeviceConnectionState.CONNECTING
     * @see probeFlow
     */
    fun connect(serialNumber : String) = service.connect(serialNumber)

    /**
     * Initiates a BLE disconnection from the probe with the specified serial number.  Upon
     * disconnection the probe's connection state is updated and that state change
     * is produced to the probe's state update flow.
     *
     * @param serialNumber the serial number of the probe.
     * @return true if the connection process was started, false otherwise.
     *
     * @see Probe
     * @see Probe.connectionState
     * @see DeviceConnectionState.DISCONNECTED
     * @see DeviceConnectionState.DISCONNECTING
     * @see probeFlow
     */
    fun disconnect(serialNumber: String) = service.disconnect(serialNumber)

    /**
     * Initiates a record transfer from the device to the service for the specified serial
     * number.  The upload state and progress are observed as state changes to the probes state.
     * A record transfer from a probe can only be started when it is connected.
     *
     * @param serialNumber the serial number of the probe.
     *
     * @see Probe
     * @see Probe.uploadState
     * @see ProbeUploadState.ProbeUploadNeeded
     * @see ProbeUploadState.ProbeUploadInProgress
     * @see ProbeUploadState.ProbeUploadComplete
     * @see ProbeUploadState.Unavailable
     * @see probeFlow
     */
    fun startRecordTransfer(serialNumber: String) = service.requestLogsFromDevice(serialNumber)

    /**
     * Retrieves the current temperature log as a list of LoggedProbeDataPoint for the specified
     * serial number.
     *
     * @param serialNumber the serial number of the probe.
     * @return list of LoggedProbeDataPoints.
     *
     * @see LoggedProbeDataPoint
     */
    fun exportLogsForDevice(serialNumber: String): List<LoggedProbeDataPoint>? =
        service.exportLogsForDevice(serialNumber)

    /**
     * Retrieves the current temperature log as a Kotlin flow of LoggedProbeDataPoint for
     * the specified serial number.  All logs previously transferred are produced to the flow
     * and new log records are produced to the flow as they are retrieved in real-time from
     * the probe.
     *
     * @param serialNumber the serial number of the probe.
     * @return Kotlin flow of LoggedProbeDataPoint for the probe.
     *
     * @see LoggedProbeDataPoint
     */
    fun createLogFlowForDevice(serialNumber: String): Flow<LoggedProbeDataPoint> =
        service.createLogFlowForDevice(serialNumber)

    /**
     * Clears all probes, logged data from the DeviceManager.  Closes all active connections
     * and disposes related resources.
     */
    fun clearDevices() = service.clearDevices()

    /**
     * Creates a simulated probe.  The simulated probe will generate events to the
     * discoveredProbesFlow.  The simulated probe has a state flow that can be collected
     * use the probeFlow method.
     *
     * @see DeviceDiscoveredEvent
     * @see discoveredProbesFlow
     * @see probeFlow
     */
    fun addSimulatedProbe() = service.addSimulatedProbe()
}