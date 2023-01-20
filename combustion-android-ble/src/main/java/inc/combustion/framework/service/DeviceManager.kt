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
import android.app.Notification
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import inc.combustion.framework.Combustion
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.NetworkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import java.lang.StringBuilder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Singleton instance for managing communications with Combustion Inc. devices.
 */
class DeviceManager(
    val settings: Settings
) {
    private val onBoundInitList = mutableListOf<() -> Unit>()
    private lateinit var service: CombustionService

    data class Settings(
        val autoReconnect: Boolean = false,
        val autoLogTransfer: Boolean = false,
        val meatNetEnabled: Boolean = false
    )

    companion object {
        private lateinit var INSTANCE: DeviceManager
        private lateinit var app : Application
        private lateinit var onServiceBound : (deviceManager: DeviceManager) -> Unit
        private val initialized = AtomicBoolean(false)
        private val connected = AtomicBoolean(false)
        private val bindingCount = AtomicInteger(0)

        private val connection = object : ServiceConnection {

            override fun onServiceConnected(className: ComponentName, serviceBinder: IBinder) {
                val binder = serviceBinder as CombustionService.CombustionServiceBinder
                INSTANCE.service = binder.getService()

                connected.set(true)
                onServiceBound(INSTANCE)

                INSTANCE.onBoundInitList.forEach{ initCallback ->
                    initCallback()
                }
            }

            override fun onServiceDisconnected(arg0: ComponentName) {
                connected.set(false)
            }
        }

        const val MINIMUM_PREDICTION_SETPOINT_CELSIUS = 0.0
        const val MAXIMUM_PREDICTION_SETPOINT_CELSIUS = 100.0

        /**
         * Instance property for the singleton
         */
        val instance: DeviceManager get() = INSTANCE

        /**
         * Initializes the singleton
         * @param application Application context.
         * @param onBound Optional lambda to be called when the service is connected to an Activity.
         */
        fun initialize(application: Application, settings: Settings = Settings(), onBound: (deviceManager: DeviceManager) -> Unit = {_ -> }) {
            if(!initialized.getAndSet(true)) {
                app = application
                onServiceBound = onBound
                INSTANCE = DeviceManager(settings)
            }
        }

        /**
         * Starts the Combustion Android Service as a Foreground Service
         *
         * @param notification Optional notification for the service.  If provided the service is run
         *  in the foreground.
         * @return notification ID
         */
        fun startCombustionService(notification: Notification?): Int {
            if(!connected.get()) {
                if(DebugSettings.DEBUG_LOG_SERVICE_LIFECYCLE)
                    Log.d(LOG_TAG, "Start Service")

                return CombustionService.start(app.applicationContext, notification, INSTANCE.settings)
            }
            return 0;
        }

        /**
         * Binds to the Combustion Android Service
         */
        fun bindCombustionService() {
            val count = bindingCount.getAndIncrement()
            if(count <= 0) {
                if(DebugSettings.DEBUG_LOG_SERVICE_LIFECYCLE)
                    Log.d(LOG_TAG, "Binding Service")

                CombustionService.bind(app.applicationContext, connection)
            }

            if(DebugSettings.DEBUG_LOG_SERVICE_LIFECYCLE)
                Log.d(LOG_TAG, "Binding Reference Count (${count + 1})")
        }

        /**
         * Unbinds from the Combustion Android Service
         */
        fun unbindCombustionService() {
            val count = bindingCount.decrementAndGet()

            if(DebugSettings.DEBUG_LOG_SERVICE_LIFECYCLE) {
                Log.d(LOG_TAG, "Unbinding Reference Count ($count)")
            }

            if(connected.get() && count <= 0) {
                stopCombustionService()
            }
        }

        /**
         * Stops the Combustion Android Service
         */
        fun stopCombustionService() {
            if(DebugSettings.DEBUG_LOG_SERVICE_LIFECYCLE)
                Log.d(LOG_TAG, "Unbinding & Stopping Service")

            bindingCount.set(0)
            app.unbindService(connection)
            CombustionService.stop(app.applicationContext)
            connected.set(false)
        }
    }

    /**
     * Return true if the default Bluetooth adapter is enabled, false otherwise.
     */
    val bluetoothIsEnabled: Boolean
        get() {
            return service.networkManager?.bluetoothIsEnabled ?: false
        }

    /**
     * True if scanning for thermometers.  False otherwise.
     */
    val scanningForProbes: Boolean
        get() {
            return service.networkManager?.scanningForProbes ?: false
        }

    /**
     * True if in DFU mode. False otherwise.
     */
    val dfuModeIsEnabled: Boolean
        get() {
            return service.networkManager?.dfuModeEnabled ?: false
        }

    /**
     * Kotlin flow for collecting ProbeDiscoveredEvents that occur when the service
     * is scanning for thermometers.  This is a hot flow.
     *
     * @see ProbeDiscoveredEvent
     */
    val discoveredProbesFlow : SharedFlow<ProbeDiscoveredEvent>
        get() {
            return NetworkManager.DISCOVERED_PROBES_FLOW
        }

    /**
     * Kotlin flow for collecting NetworkEvents that are emitted upon system state changes.
     *
     * @see NetworkEvent
     */
    val networkFlow : SharedFlow<NetworkEvent>
        get() {
            return NetworkManager.NETWORK_EVENT_FLOW
        }

    /**
     * Returns a list of device serial numbers, consisting of all devices that have been
     * discovered.
     */
    val discoveredProbes: List<String>
        get() {
            return service.networkManager?.discoveredProbes ?: listOf("")
        }

    /**
     * Registers a lambda to be called by the DeviceManager upon binding with the
     * Combustion Service and completing initialization.
     *
     * @param callback Lambda to be called by the Device Manager upon completing initialization.
     */
    fun registerOnBoundInitialization(callback : () -> Unit) {
        // if service is already connected, then run the callback right away.
        if(connected.get()) {
            callback()
        }
        // otherwise queue the callback to be run when the service is bound.
        else {
            onBoundInitList.add(callback)
        }
    }

    /**
     * Starts scanning for temperature probes.  Will generate a ProbeDiscoveredEvent
     * to the discoveredProbesFlow property.
     *
     * @return true if scanning has started, false otherwise.
     *
     * @see discoveredProbesFlow
     * @see ProbeDiscoveredEvent
     * @see ProbeDiscoveredEvent.ScanningOn
     */
    fun startScanningForProbes(): Boolean {
        return service.networkManager?.startScanForProbes() ?: false
    }

    /**
     * Stops scanning for temperature probes.  Will generate a ProbeDiscoveredEvent
     * to the discoveredProbFlow property.
     *
     * @return true if scanning has stopped, false otherwise.
     *
     * @see discoveredProbesFlow
     * @see ProbeDiscoveredEvent
     * @see ProbeDiscoveredEvent.ScanningOff
     */
    fun stopScanningForProbes(): Boolean {
        return service.networkManager?.stopScanForProbes() ?: false
    }

    /**
     * Retrieves the Kotlin flow for collecting Probe state updates for the specified
     * probe serial number.  Note, this is a hot flow.
     *
     * @param serialNumber the serial number of the probe.
     * @return Kotlin flow for collecting Probe state updates.
     *
     * @see Probe
     */
    fun probeFlow(serialNumber: String): SharedFlow<Probe>? {
        return service.networkManager?.probeFlow(serialNumber)
    }

    /**
     * Retrieves the current probe state for the specified probe serial number.
     *
     * @param serialNumber the serial number of the probe.
     * @return current ProbeState of the probe.
     *
     * @see Probe
     */
    fun probe(serialNumber: String): Probe? {
        return service.networkManager?.probeState(serialNumber)
    }

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
    fun connect(serialNumber : String) {
        service.networkManager?.connect(serialNumber)
    }

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
    fun disconnect(serialNumber: String) {
        service.networkManager?.disconnect(serialNumber)
    }

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
     * Retrieves the current temperature log as a comma separate value string with header.
     *
     * @param serialNumber Serial number to export
     * @param appNameAndVersion Name and version to include in the CSV header
     * @return Pair: first is suggested name for the exported file, second is the CSV data.
     */
    fun exportLogsForDeviceAsCsv(serialNumber: String, appNameAndVersion: String): Pair<String, String> {
        val logs = exportLogsForDevice(serialNumber)
        val probe = probe(serialNumber)

        return probeDataToCsv(probe, logs, appNameAndVersion)
    }

    /**
     * Retrieves the number of records downloaded  from the probe and available in the
     * log buffer.
     *
     * @param serialNumber the serial number of the probe.
     * @return Count of downloaded records
     */
    fun recordsDownloaded(serialNumber: String): Int =
        service.recordsDownloaded(serialNumber)

    /**
     * Retrieves the wall clock timestamp for the first record retrieved from the device
     * in the log buffer, or default Date() if no logs have been retrieved.
     *
     * @param serialNumber the serial number of the probe.
     * @return Timestamp or default Date()
     */
    fun logStartTimestampForDevice(serialNumber: String): Date =
        service.logStartTimestampForDevice(serialNumber)
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
     * @see ProbeDiscoveredEvent
     * @see discoveredProbesFlow
     * @see probeFlow
     */
    fun addSimulatedProbe() = service.addSimulatedProbe()

    /**
     * Sends a request to the device to the set the probe color. The completion handler will
     * be called when a response is received or after timeout.
     *
     * @param serialNumber the serial number of the probe.
     * @param color the color to set the probe.
     * @param completionHandler completion handler to be called operation is complete
     *
     */
    fun setProbeColor(serialNumber: String, color: ProbeColor, completionHandler: (Boolean) -> Unit) {
        service.networkManager?.setProbeColor(serialNumber, color, completionHandler) ?: run{
            completionHandler(false)
        }
    }

    /**
     * Sends a request to the device to the set the probe ID. The completion handler will
     * be called when a response is received or after timeout.
     *
     * @param serialNumber the serial number of the probe.
     * @param id the ID to set the probe.
     * @param completionHandler completion handler to be called operation is complete
     *
     */
    fun setProbeID(serialNumber: String, id: ProbeID, completionHandler: (Boolean) -> Unit) {
        service.networkManager?.setProbeID(serialNumber, id, completionHandler) ?: run {
            completionHandler(false)
        }
    }

    /**
     * Sends a request to the device to set/change the set point temperature for the time to
     * removal prediction.  If a prediction is not currently active, it will be started.  If a
     * removal prediction is currently active, then the set point will be modified.  If another
     * type of prediction is active, then the probe will start predicting removal.
     *
     * @param serialNumber the serial number of the probe.
     * @param removalTemperatureC the target removal temperature in Celsius.
     * @param completionHandler completion handler to be called operation is complete
     */
    fun setRemovalPrediction(serialNumber: String, removalTemperatureC: Double, completionHandler: (Boolean) -> Unit) {
        if(removalTemperatureC > MAXIMUM_PREDICTION_SETPOINT_CELSIUS || removalTemperatureC < MINIMUM_PREDICTION_SETPOINT_CELSIUS) {
            completionHandler(false)
            return
        }
        service.networkManager?.setRemovalPrediction(serialNumber, removalTemperatureC, completionHandler) ?: run{
            completionHandler(false)
        }
    }

    /**
     * Sends a request to the device to set the prediction mode to none, stopping any active prediction.
     *
     * @param serialNumber the serial number of the probe.
     * @param completionHandler completion handler to be called operation is complete
     */
    fun cancelPrediction(serialNumber: String, completionHandler: (Boolean) -> Unit) {
        service.networkManager?.cancelPrediction(serialNumber, completionHandler) ?: run {
            completionHandler(false)
        }
    }

    private fun probeDataToCsv(probe: Probe?, probeData: List<LoggedProbeDataPoint>?, appNameAndVersion: String): Pair<String, String> {
        val csvVersion = 3
        val sb = StringBuilder()
        val serialNumber = probe?.serialNumber ?: "UNKNOWN"
        val firmwareVersion = probe?.fwVersion ?: "UNKNOWN"
        val hardwareVersion = probe?.hwRevision ?: "UNKNOWN"
        val samplePeriod = probe?.sessionInfo?.samplePeriod ?: 0
        val frameworkVersion = "Android ${Combustion.FRAMEWORK_VERSION_NAME} ${Combustion.FRAMEWORK_BUILD_TYPE}"

        val now = LocalDateTime.now()
        val headerDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val createdDateTime = now.format(headerDateTimeFormatter)

        // header
        sb.appendLine("Combustion Inc. Probe Data")
        sb.appendLine("App: $appNameAndVersion")
        sb.appendLine("CSV version: $csvVersion")
        sb.appendLine("Probe S/N: $serialNumber")
        sb.appendLine("Probe FW version: $firmwareVersion")
        sb.appendLine("Probe HW revision: $hardwareVersion")
        sb.appendLine("Framework: $frameworkVersion")
        sb.appendLine("Sample Period: $samplePeriod")
        sb.appendLine("Created: $createdDateTime")
        sb.appendLine()

        // column headers
        sb.appendLine("Timestamp,SessionID,SequenceNumber,T1,T2,T3,T4,T5,T6,T7,T8")

        // the data
        val startTime = probeData?.first()?.timestamp?.time ?: 0
        probeData?.forEach { dataPoint ->
            val timestamp = dataPoint.timestamp.time
            val elapsed = (timestamp - startTime) / 1000.0f
            sb.appendLine(
                String.format(
                    "%.3f,%s,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f",
                    elapsed,
                    dataPoint.sessionId.toString(),
                    dataPoint.sequenceNumber.toString(),
                    dataPoint.temperatures.values[0],
                    dataPoint.temperatures.values[1],
                    dataPoint.temperatures.values[2],
                    dataPoint.temperatures.values[3],
                    dataPoint.temperatures.values[4],
                    dataPoint.temperatures.values[5],
                    dataPoint.temperatures.values[6],
                    dataPoint.temperatures.values[7]
                )
            )
        }

        // recommended file name
        val fileDateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        val recommendedFileName = "ProbeData_${serialNumber}_${now.format(fileDateTimeFormatter)}.csv"

        return recommendedFileName to sb.toString()
    }
}