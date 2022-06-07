/*
 * Project: Combustion Inc. Android Framework
 * File: CombustionService.kt
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

import android.app.Notification
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.os.Binder
import android.os.CountDownTimer
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.*
import inc.combustion.framework.log.LogManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * Android Service for managing Combustion Inc. Predictive Thermometers
 */
class CombustionService : LifecycleService() {

    private var bluetoothIsOn = false
    private val binder = CombustionServiceBinder()
    private val _probes = hashMapOf<String, ProbeManager>()

    private val periodicTimer = object : CountDownTimer(Long.MAX_VALUE, 1000) {
        override fun onTick(millisUntilFinished: Long) {
            lifecycleScope.launch {
                _probes.forEach { (_, value) ->
                    value.checkIdle()
                }
            }
        }

        override fun onFinish() {/* do nothing */ }
    }

    private val _bluetoothReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            val action = intent.action
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR
                )
                when (state) {
                    BluetoothAdapter.STATE_OFF -> {
                        bluetoothIsOn = false
                        emitScanningOffEvent()
                        emitBluetoothOffEvent()
                    }
                    BluetoothAdapter.STATE_TURNING_OFF -> {
                        DeviceScanner.stopProbeScanning()
                    }
                    BluetoothAdapter.STATE_ON -> {
                        bluetoothIsOn = true
                        emitBluetoothOnEvent()
                    }
                    BluetoothAdapter.STATE_TURNING_ON -> { }
                }
            }
        }
    }

    internal companion object {
        private const val FLOW_CONFIG_REPLAY = 5
        private const val FLOW_CONFIG_BUFFER = FLOW_CONFIG_REPLAY * 2

        private val serviceIsStarted = AtomicBoolean(false)

        var serviceNotification : Notification? = null
        var notificationId = 0

        fun start(context: Context, notification: Notification?): Int {
            if(!serviceIsStarted.get()) {
                serviceNotification = notification
                notificationId = ThreadLocalRandom.current().asKotlinRandom().nextInt()
                Intent(context, CombustionService::class.java).also { intent ->
                    context.startService(intent)
                }
            }

            return notificationId
        }

        fun bind(context: Context, connection: ServiceConnection) {
            Intent(context, CombustionService::class.java).also { intent ->
                val flags = Context.BIND_AUTO_CREATE
                context.bindService(intent, connection, flags)
            }
        }

        fun stop(context: Context) {
            Intent(context, CombustionService::class.java).also { intent ->
                context.stopService(intent)
            }
        }
    }

    inner class CombustionServiceBinder : Binder() {
        fun getService(): CombustionService = this@CombustionService
    }

    init {
        lifecycleScope.launch {
            // launches the block in a new coroutine every time the service is
            // in the CREATED state or above, and cancels the block when the
            // service is destroyed
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                DeviceScanner.probeAdvertisements.collect {
                    if(it.type == ProbeAdvertisingData.CombustionProductType.PROBE) {
                        _probes.getOrPut(key = it.serialNumber) {
                            // create new probe instance
                            var newProbe =
                                ProbeManager(
                                    it.mac,
                                    this@CombustionService,
                                    it,
                                    (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter)

                            // add it to the LogManager
                            LogManager.instance.manage(this@CombustionService, newProbe)

                            // new probe discovered, so emit into the discovered probes flow
                            _discoveredProbesFlow.emit(
                                DeviceDiscoveredEvent.DeviceDiscovered(it.serialNumber)
                            )

                            newProbe
                        }.onNewAdvertisement(it)
                    }
                }
            }
        }
    }

    private fun startForeground() {
        serviceNotification?.let {
            startForeground(notificationId, serviceNotification)
        }
    }

    private fun stopForeground() {
        serviceNotification?.let {
            val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            service.cancel(notificationId)

            stopForeground(true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // start periodic timer for RSSI polling
        periodicTimer.start()

        // setup receiver to see when platform Bluetooth state changes
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(_bluetoothReceiver, filter)

        // determine what the current state of Bluetooth is
        val bluetooth = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothIsOn = if (bluetooth.adapter == null) {
            false
        } else {
            bluetooth.adapter.isEnabled
        }

        // notify consumers on flow what the current Bluetooth state is
        if(bluetoothIsOn) {
            emitBluetoothOnEvent()
        }
        else {
            emitBluetoothOffEvent()
        }

        startForeground()

        serviceIsStarted.set(true)

        Log.d(LOG_TAG, "Combustion Android Service Started...")

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopForeground()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        // stop the service notification
        stopForeground()
        serviceNotification = null

        // always try to unregister, even if the previous register didn't complete.
        try { unregisterReceiver(_bluetoothReceiver) } catch (e: Exception) { }

        periodicTimer.cancel()
        clearDevices()

        serviceIsStarted.set(false)

        Log.d(LOG_TAG, "Combustion Android Service Destroyed...")
        super.onDestroy()
    }

    private val _discoveredProbesFlow = MutableSharedFlow<DeviceDiscoveredEvent>(
        FLOW_CONFIG_REPLAY, FLOW_CONFIG_BUFFER, BufferOverflow.SUSPEND
    )
    internal val discoveredProbesFlow = _discoveredProbesFlow.asSharedFlow()

    internal val isScanningForProbes
        get() = DeviceScanner.isScanningForProbes

    internal val discoveredProbes: List<String>
        get() {
            return _probes.keys.toList()
        }

    internal fun startScanningForProbes(): Boolean {
        if(bluetoothIsOn) {
            DeviceScanner.startProbeScanning(this)
        }
        if(isScanningForProbes) {
            emitScanningOnEvent()
        }
        return isScanningForProbes
    }

    internal fun stopScanningForProbes(): Boolean {
        if(bluetoothIsOn) {
            DeviceScanner.stopProbeScanning()
            emitScanningOnEvent()
        }
        if(!isScanningForProbes) {
            emitScanningOffEvent()
        }

        return isScanningForProbes
    }

    internal fun probeFlow(serialNumber: String): SharedFlow<Probe>? =
        _probes[serialNumber]?.probeStateFlow

    internal fun probeState(serialNumber: String): Probe? = _probes[serialNumber]?.probe

    internal fun connect(serialNumber: String) = _probes[serialNumber]?.connect()

    internal fun disconnect(serialNumber: String) = _probes[serialNumber]?.disconnect()

    internal fun requestLogsFromDevice(serialNumber: String) =
        LogManager.instance.requestLogsFromDevice(this, serialNumber)

    internal fun exportLogsForDevice(serialNumber: String): List<LoggedProbeDataPoint>? =
        LogManager.instance.exportLogsForDevice(serialNumber)

    internal fun createLogFlowForDevice(serialNumber: String): Flow<LoggedProbeDataPoint> =
        LogManager.instance.createLogFlowForDevice(serialNumber)

    internal fun recordsDownloaded(serialNumber: String): Int =
        LogManager.instance.recordsDownloaded(serialNumber)

    internal fun clearDevices() {
        LogManager.instance.clear()
        _probes.forEach { (_, probe) -> probe.finish() }
        _probes.clear()
        emitDevicesClearedEvent()
    }

    internal fun addSimulatedProbe() {
        // Create SimulatedProbeManager
        val simulatedProbeManager = SimulatedProbeManager.create(this@CombustionService,
            (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter)

        // Add to probe list
        _probes[simulatedProbeManager.probe.serialNumber] = simulatedProbeManager

        // Add it to the LogManager
        LogManager.instance.manage(this@CombustionService, simulatedProbeManager)

        // emit into the discovered probes flow
        lifecycleScope.launch {
            _discoveredProbesFlow.emit(
                DeviceDiscoveredEvent.DeviceDiscovered(simulatedProbeManager.probe.serialNumber)
            )
        }
    }

    internal fun setProbeColor(serialNumber: String, color: ProbeColor, completionHandler: (Boolean) -> Unit) {
        _probes[serialNumber]?.sendSetProbeColor(this, color, completionHandler)
    }

    internal fun setProbeID(serialNumber: String, id: ProbeID, completionHandler: (Boolean) -> Unit) {
        _probes[serialNumber]?.sendSetProbeID(this, id, completionHandler)
    }

    private fun emitBluetoothOnEvent() = _discoveredProbesFlow.tryEmit(
        DeviceDiscoveredEvent.BluetoothOn
    )

    private fun emitBluetoothOffEvent() = _discoveredProbesFlow.tryEmit(
        DeviceDiscoveredEvent.BluetoothOff
    )

    private fun emitScanningOnEvent()  = _discoveredProbesFlow.tryEmit(
        DeviceDiscoveredEvent.ScanningOn
    )

    private fun emitScanningOffEvent() = _discoveredProbesFlow.tryEmit(
        DeviceDiscoveredEvent.ScanningOff
    )

    private fun emitDevicesClearedEvent() = _discoveredProbesFlow.tryEmit(
        DeviceDiscoveredEvent.DevicesCleared
    )
}