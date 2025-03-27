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

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.content.ComponentName
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.util.Log
import inc.combustion.framework.Combustion
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.analytics.AnalyticsEvent
import inc.combustion.framework.analytics.AnalyticsTracker
import inc.combustion.framework.analytics.ExceptionEvent
import inc.combustion.framework.ble.NetworkManager
import inc.combustion.framework.ble.device.DeviceID
import inc.combustion.framework.ble.dfu.DfuManager
import inc.combustion.framework.ble.uart.meatnet.GenericNodeRequest
import inc.combustion.framework.ble.uart.meatnet.GenericNodeResponse
import inc.combustion.framework.ble.uart.meatnet.NodeMessage
import inc.combustion.framework.ble.uart.meatnet.NodeMessageType
import inc.combustion.framework.ble.uart.meatnet.NodeUARTMessage
import inc.combustion.framework.log.LogManager
import inc.combustion.framework.service.dfu.DfuProductType
import inc.combustion.framework.service.dfu.DfuSystemState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Singleton instance for managing communications with Combustion Inc. devices.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceManager(
    val settings: Settings,
) {
    private val onBoundInitList = mutableListOf<() -> Unit>()
    private lateinit var service: CombustionService

    /**
     * [probeAllowlist] specifies a set of thermometers that will be used to filter devices
     * flowed to the application. Set to null to disable filtering. This can also be set by calling
     * [setProbeAllowlist].
     */
    data class Settings(
        val autoReconnect: Boolean = false,
        val autoLogTransfer: Boolean = false,
        val meatNetEnabled: Boolean = false,
        val probeAllowlist: Set<String>? = null,
        val messageTypeCallback: (UByte) -> NodeMessage? = { messageType: UByte ->
            NodeMessageType.fromUByte(
                messageType
            )
        }
    )

    companion object {
        private lateinit var INSTANCE: DeviceManager
        private lateinit var app: Application
        private lateinit var onServiceBound: (deviceManager: DeviceManager) -> Unit
        private val initialized = AtomicBoolean(false)
        private val connected = AtomicBoolean(false)
        private val bindingCount = AtomicInteger(0)
        private var coroutineScope: CoroutineScope? = null

        private val connection = object : ServiceConnection {

            override fun onServiceConnected(className: ComponentName, serviceBinder: IBinder) {
                val binder = serviceBinder as CombustionService.CombustionServiceBinder
                INSTANCE.service = binder.getService()

                connected.set(true)
                onServiceBound(INSTANCE)

                INSTANCE.onBoundInitList.forEach { initCallback ->
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
        fun initialize(
            application: Application,
            settings: Settings = Settings(),
            onBound: (deviceManager: DeviceManager) -> Unit = { _ -> }
        ) {
            if (!initialized.getAndSet(true)) {
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
        fun startCombustionService(
            notification: Notification?,
            dfuNotificationTarget: Class<out Activity?>? = null,
            latestFirmware: Map<DfuProductType, Uri> = emptyMap(),
            onServiceStarted: (() -> Unit)? = null,
        ): Int {
            if (!connected.get()) {
                if (DebugSettings.DEBUG_LOG_SERVICE_LIFECYCLE)
                    Log.d(LOG_TAG, "Start Service")
                coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

                return CombustionService.start(
                    app.applicationContext,
                    notification,
                    dfuNotificationTarget,
                    INSTANCE.settings,
                    latestFirmware,
                    onServiceStarted,
                )
            }
            return 0
        }

        /**
         * Binds to the Combustion Android Service
         */
        fun bindCombustionService() {
            val count = bindingCount.getAndIncrement()
            if (count <= 0) {
                if (DebugSettings.DEBUG_LOG_SERVICE_LIFECYCLE)
                    Log.d(LOG_TAG, "Binding Service")

                CombustionService.bind(app.applicationContext, connection)
            }

            if (DebugSettings.DEBUG_LOG_SERVICE_LIFECYCLE)
                Log.d(LOG_TAG, "Binding Reference Count (${count + 1})")
        }

        /**
         * Unbinds from the Combustion Android Service
         */
        fun unbindCombustionService() {
            val count = bindingCount.decrementAndGet()

            if (DebugSettings.DEBUG_LOG_SERVICE_LIFECYCLE) {
                Log.d(LOG_TAG, "Unbinding Reference Count ($count)")
            }

            if (connected.get() && count <= 0) {
                stopCombustionService()
            }
        }

        /**
         * Stops the Combustion Android Service
         */
        fun stopCombustionService() {
            if (DebugSettings.DEBUG_LOG_SERVICE_LIFECYCLE) {
                Log.d(LOG_TAG, "Unbinding & Stopping Service")
            }

            bindingCount.set(0)
            app.unbindService(connection)

            coroutineScope?.cancel()
            coroutineScope = null

            // we might run into situations where the service isn't fully started
            // by the time we reach this call since the operation is asynchronous.
            // do our best effort to stop the service, and log a message if we fall
            // into this occasional situation.
            try {
                CombustionService.stop(app.applicationContext)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Exception stopping service ${e.stackTrace}")
            }

            connected.set(false)
        }
    }

    /**
     * Return true if the default Bluetooth adapter is enabled, false otherwise.
     */
    val bluetoothIsEnabled: Boolean
        get() {
            return NetworkManager.instance.bluetoothIsEnabled
        }

    /**
     * True if scanning for thermometers.  False otherwise.
     */
    val scanningForProbes: Boolean
        get() {
            return NetworkManager.instance.scanningForDevices
        }

    /**
     * True if in DFU mode. False otherwise.
     */
    val dfuModeIsEnabled: Boolean
        get() {
            return NetworkManager.instance.dfuModeEnabled
        }

    @Deprecated(
        message = "discoveredProbesFlow is deprecated, use discoveredDevicesFlow instead.",
        replaceWith = ReplaceWith("discoveredDevicesFlow"),
        level = DeprecationLevel.WARNING
    )
    @Suppress("DEPRECATION")
    val discoveredProbesFlow: SharedFlow<ProbeDiscoveredEvent>
        get() {
            return discoveredDevicesFlow.mapNotNull {
                when (it) {
                    is DeviceDiscoveryEvent.ProbeDiscovered -> ProbeDiscoveredEvent.ProbeDiscovered(
                        it.serialNumber
                    )

                    is DeviceDiscoveryEvent.ProbeRemoved -> ProbeDiscoveredEvent.ProbeRemoved(it.serialNumber)
                    is DeviceDiscoveryEvent.DevicesCleared -> ProbeDiscoveredEvent.DevicesCleared
                    else -> null
                }
            }.shareIn(requireNotNull(coroutineScope), SharingStarted.Lazily)
        }

    /**
     * Kotlin flow for collecting [DeviceDiscoveryEvent] that occur when the service
     * is scanning for devices.  This is a hot flow.
     *
     * @see DeviceDiscoveryEvent
     */
    val discoveredDevicesFlow: SharedFlow<DeviceDiscoveryEvent>
        get() {
            return NetworkManager.discoveredDevicesFlow
        }


    @Deprecated(
        message = "deviceProximityFlow is deprecated, use deviceInProximityFlow instead.",
        replaceWith = ReplaceWith("deviceInProximityFlow"),
        level = DeprecationLevel.WARNING
    )
    @Suppress("DEPRECATION")
    val deviceProximityFlow: SharedFlow<DeviceDiscoveredEvent>
        get() {
            return deviceInProximityFlow.mapNotNull {
                when (it) {
                    is DeviceInProximityEvent.ProbeDiscovered -> DeviceDiscoveredEvent.ProbeDiscovered(
                        it.serialNumber
                    )

                    is DeviceInProximityEvent.NodeDiscovered -> DeviceDiscoveredEvent.NodeDiscovered(
                        it.serialNumber
                    )

                    is DeviceInProximityEvent.DevicesCleared -> DeviceDiscoveredEvent.DevicesCleared
                    else -> null
                }
            }.shareIn(requireNotNull(coroutineScope), SharingStarted.Lazily)
        }

    /**
     * Kotlin flow for collecting [DeviceInProximityEvent] that occur when the service encounters a
     * device that is in proximity. To receive updates to the proximity value, collect on the
     * [deviceSmoothedRssiFlow]. This is a hot flow.
     *
     * Note that this flow will emit events for all devices in proximity, disregarding any allowlist
     * information.
     */
    val deviceInProximityFlow: SharedFlow<DeviceInProximityEvent>
        get() {
            return NetworkManager.deviceProximityFlow
        }

    /**
     * Kotlin flow for collecting NetworkState
     *
     * @see NetworkEvent
     */
    val networkFlow: StateFlow<NetworkState>
        get() {
            return NetworkManager.networkStateFlow
        }

    /**
     * Kotlin flow for collecting GenericNodeRequest messages that are sent from nodes.
     */
    val genericNodeRequestFlow: SharedFlow<NodeUARTMessage>
        get() {
            return NetworkManager.genericNodeMessageFlow
        }

    /**
     * Returns a list of device serial numbers, consisting of all probes that have been
     * discovered.
     */
    val discoveredProbes: List<String>
        get() {
            return NetworkManager.instance.discoveredProbes
        }

    /**
     * Returns a list of device serial numbers, consisting of all gauges that have been
     * discovered.
     */
    val discoveredGauges: List<String>
        get() {
            return NetworkManager.instance.discoveredGauges
        }

    /**
     * Returns a list of node serial numbers, consisting of all nodes that have been discovered.
     * This will exclude any probe devices.
     */
    val discoveredNodes: List<String>
        get() {
            return NetworkManager.instance.discoveredNodes
        }

    /**
     * Returns a stateFlow of a list of node serial numbers, consisting of all nodes that have been discovered.
     * This will exclude any probe devices.
     */
    val discoveredNodesFlow: StateFlow<List<String>>
        get() = NetworkManager.instance.discoveredNodesFlow

    /**
     * Returns a flow of [AnalyticsEvent], tracked analytics events.
     * Note, flow does not suspend and discards old events.
     */
    val analyticsFlow: SharedFlow<AnalyticsEvent>
        get() = AnalyticsTracker.instance.eventFlow

    /**
     * Returns a flow of [ExceptionEvent], tracked analytics exception events.
     * Note, flow does not suspend and discards old events.
     */
    val analyticsExceptionsFlow: SharedFlow<ExceptionEvent>
        get() = AnalyticsTracker.instance.exceptionEventFlow

    /**
     * Registers a lambda to be called by the DeviceManager upon binding with the
     * Combustion Service and completing initialization.
     *
     * @param callback Lambda to be called by the Device Manager upon completing initialization.
     */
    fun registerOnBoundInitialization(callback: () -> Unit) {
        // if service is already connected, then run the callback right away.
        if (connected.get()) {
            callback()
        }
        // otherwise queue the callback to be run when the service is bound.
        else {
            onBoundInitList.add(callback)
        }
    }

    @Deprecated(
        message = "startScanningForProbes is deprecated, use startScanningForDevices instead.",
        replaceWith = ReplaceWith("startScanningForDevices"),
        level = DeprecationLevel.WARNING
    )
    fun startScanningForProbes(): Boolean = startScanningForDevices()

    /**
     * Starts scanning for devices.  Will generate a [DeviceDiscoveryEvent]
     * to the [discoveredDevicesFlow] property.
     *
     * @return true if scanning has started, false otherwise.
     *
     * @see discoveredDevicesFlow
     * @see DeviceDiscoveryEvent
     */
    fun startScanningForDevices(): Boolean {
        return NetworkManager.instance.startScanForDevices()
    }

    @Deprecated(
        message = "setProbeAllowlist is deprecated, use setDeviceAllowList instead.",
        replaceWith = ReplaceWith("setDeviceAllowList"),
        level = DeprecationLevel.WARNING
    )
    fun setProbeAllowlist(allowlist: Set<String>?) {
        setDeviceAllowList(allowlist)
    }

    /**
     * Sets the list of devices that the framework will publish data for to [allowlist]. A
     * discovery event will be emitted to [discoveredDevicesFlow] for every device in the list that
     * isn't currently in the internal list maintained by the framework.
     *
     * Passing null for [allowlist] will cause all devices to be published.
     */
    fun setDeviceAllowList(allowlist: Set<String>?) {
        NetworkManager.instance.setDeviceAllowlist(allowlist)
    }

    @Deprecated(
        message = "setProbeAllowlist is deprecated, use setDeviceAllowList instead.",
        replaceWith = ReplaceWith("setDeviceAllowList"),
        level = DeprecationLevel.WARNING
    )
    val probeAllowlist: Set<String>?
        get() = deviceAllowlist

    /**
     * Returns the current device allowlist.
     */
    val deviceAllowlist: Set<String>?
        get() {
            return NetworkManager.instance.deviceAllowlist
        }


    @Deprecated(
        message = "stopScanningForProbes is deprecated, use stopScanningForDevices instead.",
        replaceWith = ReplaceWith("stopScanningForDevices"),
        level = DeprecationLevel.WARNING
    )
    fun stopScanningForProbes(): Boolean = stopScanningForDevices()

    /**
     * Stops scanning for devices.  Will generate a [DeviceDiscoveryEvent]
     * to the [discoveredDevicesFlow] property.
     *
     * @return true if scanning has stopped, false otherwise.
     *
     * @see discoveredDevicesFlow
     * @see DeviceDiscoveryEvent
     */
    fun stopScanningForDevices(): Boolean {
        return NetworkManager.instance.stopScanForDevices()
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
    fun probeFlow(serialNumber: String): StateFlow<Probe>? {
        return NetworkManager.instance.probeFlow(serialNumber)
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
        return NetworkManager.instance.probeState(serialNumber)
    }

    /**
     * Retrieves the Kotlin flow for collecting Gauge state updates for the specified
     * probe serial number.  Note, this is a hot flow.
     *
     * @param serialNumber the serial number of the gauge.
     * @return Kotlin flow for collecting Gauge state updates.
     *
     * @see Gauge
     */
    fun gaugeFlow(serialNumber: String): StateFlow<Gauge>? {
        return NetworkManager.instance.gaugeFlow(serialNumber)
    }

    /**
     * Retrieves the current probe state for the specified probe serial number.
     *
     * @param serialNumber the serial number of the probe.
     * @return current ProbeState of the probe.
     *
     * @see Gauge
     */
    fun gauge(serialNumber: String): Gauge? {
        return NetworkManager.instance.gaugeState(serialNumber)
    }

    /**
     * Retrieves the Kotlin flow for collecting current smoothed RSSI value updates for the device
     * with serial number [serialNumber]. Note that the value retrieved from this flow disregards
     * MeatNet connections--you may have a MeatNet connection to this device, but if the device
     * itself is nearly out of range, this flow may have a very low value, may not be updating at
     * all, or may emit null to indicate that the device is out of range.
     *
     * The rate that this flow updates is dependent on the frequency with which we receive
     * advertising data, which can come in more or less frequently if the probe is in instant read
     * mode or not.
     *
     * This flow is guaranteed to exist if a [DeviceInProximityEvent.ProbeDiscovered] event is flowed
     * from [deviceInProximityFlow] for [serialNumber].
     */
    fun deviceSmoothedRssiFlow(serialNumber: String): StateFlow<Double?>? {
        return NetworkManager.instance.deviceSmoothedRssiFlow(serialNumber)
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
    fun connect(serialNumber: String) {
        NetworkManager.instance.connect(serialNumber)
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
        NetworkManager.instance.disconnect(serialNumber)
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
    fun startRecordTransfer(serialNumber: String) {
        LogManager.instance.requestLogsFromDevice(serialNumber)
    }

    /**
     * Retrieves the current temperature log as a list of LoggedProbeDataPoint for the specified
     * serial number.
     *
     * @param serialNumber the serial number of the probe.
     * @return list of LoggedProbeDataPoints.
     *
     * @see LoggedProbeDataPoint
     */
    fun exportLogsForDevice(serialNumber: String): List<LoggedProbeDataPoint>? {
        return LogManager.instance.exportLogsForDevice(serialNumber)
    }

    /**
     * Retrieves the current temperature log as a list of [LoggedProbeDataPoint]s, organized by
     * session, for the specified serial number.
     *
     * @param serialNumber the serial number of the probe.
     * @return list of list of LoggedProbeDataPoints.
     *
     * @see LoggedProbeDataPoint
     */
    fun exportLogsForDeviceBySession(serialNumber: String): List<List<LoggedProbeDataPoint>>? {
        return LogManager.instance.exportLogsForDeviceBySession(serialNumber)
    }

    /**
     * Retrieves the current temperature log as a comma separate value string with header.
     *
     * @param serialNumber Serial number to export
     * @param appNameAndVersion Name and version to include in the CSV header
     * @return Pair: first is suggested name for the exported file, second is the CSV data.
     */
    fun exportLogsForDeviceAsCsv(
        serialNumber: String,
        appNameAndVersion: String
    ): Pair<String, String> {
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
    fun recordsDownloaded(serialNumber: String): Int {
        return LogManager.instance.recordsDownloaded(serialNumber)
    }

    /**
     * Retrieves the wall clock timestamp for the first record retrieved from the device
     * in the log buffer, or default Date() if no logs have been retrieved.
     *
     * @param serialNumber the serial number of the probe.
     * @return Timestamp or default Date()
     */
    fun logStartTimestampForDevice(serialNumber: String): Date {
        return LogManager.instance.logStartTimestampForDevice(serialNumber)
    }

    /**
     * Retrieves the current temperature log as a Kotlin flow of LoggedProbeDataPoint for
     * the specified serial number.  All logs previously transferred are produced to the flow
     * and new log records are produced to the flow as they are retrieved in real-time from
     * the probe.
     *
     * @param serialNumber the serial number of the probe.
     * @param includeHistory emits out the entire log history before starting updates in real-time.
     * @return Kotlin flow of LoggedProbeDataPoint for the probe.
     *
     * @see LoggedProbeDataPoint
     */
    fun createLogFlowForDevice(
        serialNumber: String,
        includeHistory: Boolean = true
    ): Flow<LoggedProbeDataPoint> {
        return LogManager.instance.createLogFlowForDevice(serialNumber, includeHistory)
    }

    /**
     * Clears all probes, logged data from the DeviceManager.  Closes all active connections
     * and disposes related resources.
     */
    fun clearDevices() {
        NetworkManager.instance.clearDevices()
        LogManager.instance.clear()
    }

    /**
     * Creates a simulated probe.  The simulated probe will generate events to the
     * discoveredDevicesFlow.  The simulated probe has a state flow that can be collected
     * use the probeFlow method.
     *
     * @see DeviceDiscoveryEvent
     * @see discoveredDevicesFlow
     * @see probeFlow
     */
    fun addSimulatedProbe() {
        NetworkManager.instance.addSimulatedProbe()
    }

    /**
     * Creates a simulated gauge. The simulated gauge will generate events to the
     * discoveredDevicesFlow.  The simulated gauge has a state flow that can be collected
     * use the gaugeFlow method.
     *
     * @see DeviceDiscoveryEvent
     * @see discoveredDevicesFlow
     * @see gaugeFlow
     */
    fun addSimulatedGauge() {
        NetworkManager.instance.addSimulatedGauge()
    }

    /**
     * Sends a request to the device to the set the probe color. The completion handler will
     * be called when a response is received or after timeout.
     *
     * @param serialNumber the serial number of the probe.
     * @param color the color to set the probe.
     * @param completionHandler completion handler to be called operation is complete
     *
     */
    fun setProbeColor(
        serialNumber: String,
        color: ProbeColor,
        completionHandler: (Boolean) -> Unit
    ) {
        NetworkManager.instance.setProbeColor(serialNumber, color, completionHandler)
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
        NetworkManager.instance.setProbeID(serialNumber, id, completionHandler)
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
    fun setRemovalPrediction(
        serialNumber: String,
        removalTemperatureC: Double,
        completionHandler: (Boolean) -> Unit
    ) {
        if (removalTemperatureC > MAXIMUM_PREDICTION_SETPOINT_CELSIUS || removalTemperatureC < MINIMUM_PREDICTION_SETPOINT_CELSIUS) {
            completionHandler(false)
            return
        }
        NetworkManager.instance.setRemovalPrediction(
            serialNumber,
            removalTemperatureC,
            completionHandler
        )
    }

    /**
     * Sends a request to the device to set the prediction mode to none, stopping any active prediction.
     *
     * @param serialNumber the serial number of the probe.
     * @param completionHandler completion handler to be called operation is complete
     */
    fun cancelPrediction(serialNumber: String, completionHandler: (Boolean) -> Unit) {
        NetworkManager.instance.cancelPrediction(serialNumber, completionHandler)
    }

    /**
     * Set the food safety configuration on the probe with the serial number [serialNumber] to
     * [foodSafeData], calling [completionHandler] with the success value on completion.
     */
    fun configureFoodSafe(
        serialNumber: String,
        foodSafeData: FoodSafeData,
        completionHandler: (Boolean) -> Unit
    ) {
        Log.i(LOG_TAG, "Setting food safe configuration: $foodSafeData")
        NetworkManager.instance.configureFoodSafe(serialNumber, foodSafeData, completionHandler)
    }

    /**
     * Reset the food safety configuration on the probe with the serial number [serialNumber],
     * calling [completionHandler] with the success value on completion.
     */
    fun resetFoodSafe(serialNumber: String, completionHandler: (Boolean) -> Unit) {
        Log.i(LOG_TAG, "Resetting food safe configuration")
        NetworkManager.instance.resetFoodSafe(serialNumber, completionHandler)
    }

    /**
     * Set powerMode on probe with the serial number [serialNumber] to
     * [powerMode], calling [completionHandler] with the success value on completion.
     */
    fun setPowerMode(
        serialNumber: String,
        powerMode: ProbePowerMode,
        completionHandler: (Boolean) -> Unit
    ) {
        Log.i(LOG_TAG, "Setting probe $serialNumber's powerMode to $powerMode")
        NetworkManager.instance.setPowerMode(serialNumber, powerMode, completionHandler)
    }

    /**
     * Reset probe with the serial number [serialNumber],
     * calling [completionHandler] with the success value on completion.
     */
    fun resetProbe(serialNumber: String, completionHandler: (Boolean) -> Unit) {
        Log.i(LOG_TAG, "Resetting probe $serialNumber")
        NetworkManager.instance.resetProbe(serialNumber, completionHandler)
    }

    /**
     * State flow containing the firmware details for all nodes on the network.
     */
    fun getNetworkFirmwareState(): StateFlow<FirmwareState> {
        return NetworkManager.firmwareUpdateStateFlow
    }

    /**
     * Transitions the framework into DFU mode.
     */
    fun startDfuMode() = service.startDfuMode()

    /**
     * Transitions the framework out of DFU mode and back into 'normal' mode.
     */
    fun stopDfuMode() = service.stopDfuMode()

    /**
     * Flow exposing the DFU system's overall state.
     */
    val dfuSystemStateFlow: SharedFlow<DfuSystemState>
        get() {
            return DfuManager.SYSTEM_STATE_FLOW
        }

    /**
     * A set of all devices that are currently available to perform a DFU operation on.
     */
    val dfuDevices: Set<DeviceID>
        get() {
            return service.dfuManager?.availableDevices?.toSet() ?: setOf()
        }

    /**
     * A flow exposing DFU status for a specific device.
     */
    fun dfuFlowForDevice(id: DeviceID) = service.dfuManager?.dfuFlowForDevice(id)

    /**
     * Starts a DFU operation using the update file [updateFile] on the device associated with [id],
     * returning the state flow to monitor progress.
     *
     * This function will return null if DFU mode is not enabled.
     *
     * This flow can also be obtained by using [dfuFlowForDevice].
     */
    fun performDfuForDevice(id: DeviceID, updateFile: Uri) =
        service.dfuManager?.performDfu(id, updateFile)

    fun sendNodeRequest(
        deviceId: String,
        request: GenericNodeRequest,
        completionHandler: (Boolean, GenericNodeResponse?) -> Unit
    ) {
        NetworkManager.instance.sendNodeRequest(deviceId, request, completionHandler)
    }

    private fun probeDataToCsv(
        probe: Probe?,
        probeData: List<LoggedProbeDataPoint>?,
        appNameAndVersion: String
    ): Pair<String, String> {
        val csvVersion = 4
        val sb = StringBuilder()
        val serialNumber = probe?.serialNumber ?: "UNKNOWN"
        val firmwareVersion = probe?.fwVersion ?: "UNKNOWN"
        val hardwareVersion = probe?.hwRevision ?: "UNKNOWN"
        val samplePeriod = probe?.sessionInfo?.samplePeriod ?: 0
        val frameworkVersion =
            "Android ${Combustion.FRAMEWORK_VERSION_NAME} ${Combustion.FRAMEWORK_BUILD_TYPE}"

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
        sb.appendLine("Timestamp,SessionID,SequenceNumber,T1,T2,T3,T4,T5,T6,T7,T8,VirtualCoreTemperature,VirtualSurfaceTemperature,VirtualAmbientTemperature,EstimatedCoreTemperature,PredictionSetPoint,VirtualCoreSensor,VirtualSurfaceSensor,VirtualAmbientSensor,PredictionState,PredictionMode,PredictionType,PredictionValueSeconds")

        // the data
        val startTime = probeData?.first()?.timestamp?.time ?: 0
        probeData?.forEach { dataPoint ->
            val timestamp = dataPoint.timestamp.time
            val elapsed = (timestamp - startTime) / 1000.0f
            sb.appendLine(
                String.format(
                    locale = Locale.US,
                    "%.3f,%s,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%s,%s,%s,%s,%s,%s,%d",
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
                    dataPoint.temperatures.values[7],
                    dataPoint.virtualCoreTemperature,
                    dataPoint.virtualSurfaceTemperature,
                    dataPoint.virtualAmbientTemperature,
                    dataPoint.estimatedCoreTemperature,
                    dataPoint.predictionSetPointTemperature,
                    dataPoint.virtualCoreSensor.toString(),
                    dataPoint.virtualSurfaceSensor.toString(),
                    dataPoint.virtualAmbientSensor.toString(),
                    dataPoint.predictionState.toString(),
                    dataPoint.predictionMode.toString(),
                    dataPoint.predictionType.toString(),
                    dataPoint.predictionValueSeconds.toInt()
                )
            )
        }

        // recommended file name
        val fileDateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        val recommendedFileName =
            "ProbeData_${serialNumber}_${now.format(fileDateTimeFormatter)}.csv"

        return recommendedFileName to sb.toString()
    }
}