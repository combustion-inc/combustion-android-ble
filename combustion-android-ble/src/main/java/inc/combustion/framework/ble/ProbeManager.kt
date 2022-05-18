/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeManager.kt
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
package inc.combustion.framework.ble

import android.bluetooth.BluetoothAdapter
import android.os.ParcelUuid
import android.util.Log
import androidx.lifecycle.*
import com.juul.kable.NotReadyException
import com.juul.kable.State
import com.juul.kable.characteristicOf
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.uart.*
import inc.combustion.framework.service.ProbeUploadState
import inc.combustion.framework.service.DebugSettings
import inc.combustion.framework.service.DeviceConnectionState
import inc.combustion.framework.service.Probe
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages probe BLE communications and state monitoring.
 *
 * @property mac Bluetooth MAC address
 * @property advertisingData Advertising packet for probe.
 * @constructor Constructs a new instance and starts probe management
 *
 * @param owner Lifecycle owner for managing coroutine scope.
 * @param adapter Bluetooth adapter.
 */
internal open class ProbeManager (
    private val mac: String,
    owner: LifecycleOwner,
    private var advertisingData: ProbeAdvertisingData,
    adapter: BluetoothAdapter
): Device(mac, owner, adapter) {

    companion object {
        private const val PROBE_IDLE_TIMEOUT_MS = 15000L
        private const val PROBE_REMOTE_RSSI_POLL_RATE_MS = 1000L
        private const val DEV_INFO_SERVICE_UUID_STRING = "0000180A-0000-1000-8000-00805F9B34FB"
        private const val NEEDLE_SERVICE_UUID_STRING = "00000100-CAAB-3792-3D44-97AE51C1407A"
        private const val UART_SERVICE_UUID_STRING   = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
        private const val FLOW_CONFIG_REPLAY = 5
        private const val FLOW_CONFIG_BUFFER = FLOW_CONFIG_REPLAY * 2

        val FW_VERSION_CHARACTERISTIC = characteristicOf(
            service = DEV_INFO_SERVICE_UUID_STRING,
            characteristic = "00002A26-0000-1000-8000-00805F9B34FB"
        )
        val HW_REVISION_CHARACTERISTIC = characteristicOf(
            service = DEV_INFO_SERVICE_UUID_STRING,
            characteristic = "00002A27-0000-1000-8000-00805F9B34FB"
        )

        val NEEDLE_SERVICE_UUID: ParcelUuid = ParcelUuid.fromString(
            NEEDLE_SERVICE_UUID_STRING
        )
        val DEVICE_STATUS_CHARACTERISTIC = characteristicOf(
            service = NEEDLE_SERVICE_UUID_STRING,
            characteristic = "00000101-CAAB-3792-3D44-97AE51C1407A"
        )
        val UART_RX_CHARACTERISTIC = characteristicOf(
            service = UART_SERVICE_UUID_STRING,
            characteristic = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
        )
        val UART_TX_CHARACTERISTIC = characteristicOf(
            service = UART_SERVICE_UUID_STRING,
            characteristic = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
        )
    }

    private var deviceStatus: DeviceStatus? = null
    private var connectionState = DeviceConnectionState.OUT_OF_RANGE
    private var uploadState: ProbeUploadState = ProbeUploadState.Unavailable

    internal val isConnected = AtomicBoolean(false)
    internal val remoteRssi = AtomicInteger(0)

    internal var fwVersion: String? = null
    internal var hwRevision: String? = null

    private val _probeStateFlow =
        MutableSharedFlow<Probe>(
            FLOW_CONFIG_REPLAY, FLOW_CONFIG_BUFFER, BufferOverflow.DROP_OLDEST)
    val probeStateFlow = _probeStateFlow.asSharedFlow()

    internal val _deviceStatusFlow =
        MutableSharedFlow<DeviceStatus>(
            FLOW_CONFIG_REPLAY, FLOW_CONFIG_BUFFER, BufferOverflow.DROP_OLDEST)
    val deviceStatusFlow = _deviceStatusFlow.asSharedFlow()

    private val _logResponseFlow =
        MutableSharedFlow<LogResponse>(
            FLOW_CONFIG_REPLAY*5, FLOW_CONFIG_BUFFER*5, BufferOverflow.SUSPEND)
    val logResponseFlow = _logResponseFlow.asSharedFlow()

    val probe: Probe get() = toProbe()

    init {
        // connection state flow monitor
        addJob(owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                connectionStateMonitor()
            }
        })
        // device status characteristic notification flow monitor
        addJob(owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                deviceStatusCharacteristicMonitor()
            }
        })
        // device status deserialized object flow monitor
        addJob(owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                deviceStatusMonitor()
            }
        })
        // UART TX characteristic notification flow monitor
        addJob(owner.lifecycleScope.launch(Dispatchers.IO) {
            owner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                uartTxMonitor()
            }
        })
        // RSII polling job
        addJob(owner.lifecycleScope.launch(Dispatchers.IO) {
            while(isActive) {
                if(isConnected.get() && mac != SimulatedProbeManager.SIMULATED_MAC) {
                    try {
                        remoteRssi.set(peripheral.rssi())
                    } catch (e: Exception) {
                        Log.w(LOG_TAG,
                            "Exception while reading remote RSSI: \n${e.stackTrace}")
                    }
                    _probeStateFlow.emit(probe)
                }
                delay(PROBE_REMOTE_RSSI_POLL_RATE_MS)
            }
        })
    }

    override suspend fun checkIdle() {
        super.checkIdle()

        val idle = monitor.isIdle(PROBE_IDLE_TIMEOUT_MS)

        if(idle) {
            connectionState = when(connectionState) {
                DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE -> DeviceConnectionState.OUT_OF_RANGE
                DeviceConnectionState.ADVERTISING_CONNECTABLE -> DeviceConnectionState.OUT_OF_RANGE
                DeviceConnectionState.DISCONNECTED -> DeviceConnectionState.OUT_OF_RANGE
                else -> connectionState
            }
        }

        if(idle) {
            _probeStateFlow.emit(probe)
        }
    }

    open fun sendLogRequest(owner: LifecycleOwner, minSequence: UInt, maxSequence: UInt) {
        sendUartRequest(owner, LogRequest(minSequence, maxSequence))
    }

    suspend fun onNewUploadState(newUploadState: ProbeUploadState) {
        // only update and emit on state change
        if (uploadState != newUploadState ) {
            uploadState = newUploadState
            _probeStateFlow.emit(probe)
        }
    }

    suspend fun onNewAdvertisement(newAdvertisingData: ProbeAdvertisingData) {
        // the probe continues to advertise even while a BLE connection is
        // established.  determine if the device is currently advertising as
        // connectable or not.
        val advertisingState = when(newAdvertisingData.isConnectable) {
            true -> DeviceConnectionState.ADVERTISING_CONNECTABLE
            else -> DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE
        }

        // if the device is advertising as connectable, advertising as non-connectable,
        // currently disconnected, or currently out of range then it's new state is the
        // advertising state determined above. otherwise, (connected, connected or
        // disconnecting) the state is unchanged by the advertising packet.
        connectionState = when(connectionState) {
            DeviceConnectionState.ADVERTISING_CONNECTABLE -> advertisingState
            DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE -> advertisingState
            DeviceConnectionState.OUT_OF_RANGE -> advertisingState
            DeviceConnectionState.DISCONNECTED -> advertisingState
            else -> connectionState
        }

        // if our new state is advertising, then emit the data.  and kick the monitor
        // to indicate activity.
        if(connectionState == DeviceConnectionState.ADVERTISING_CONNECTABLE ||
            connectionState == DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE ) {
            monitor.activity()
            advertisingData = newAdvertisingData
            deviceStatus = null
            _probeStateFlow.emit(probe)
        }
    }

    private fun sendUartRequest(owner: LifecycleOwner, request: Request) {
        owner.lifecycleScope.launch(Dispatchers.IO) {
            if(DebugSettings.DEBUG_LOG_BLE_UART_IO) {
                val packet = request.data.joinToString(""){
                    it.toString(16).padStart(2, '0').uppercase()
                }
                Log.d(LOG_TAG, "UART-TX: $packet")
            }

            try {
                peripheral.write(UART_RX_CHARACTERISTIC, request.sData)
            } catch(e: NotReadyException)  {
                Log.w(LOG_TAG, "UART-TX: Attempt to write when connection is not ready")
            }
        }
    }

    private suspend fun connectionStateMonitor() {
        peripheral.state.collect { state ->
            monitor.activity()

            connectionState = when(state) {
                is State.Connecting -> DeviceConnectionState.CONNECTING
                State.Connected -> DeviceConnectionState.CONNECTED
                State.Disconnecting -> DeviceConnectionState.DISCONNECTING
                is State.Disconnected -> DeviceConnectionState.DISCONNECTED
            }

            isConnected.set(connectionState == DeviceConnectionState.CONNECTED)

            if(connectionState != DeviceConnectionState.CONNECTED)
                deviceStatus = null
            else {
                readFirmwareVersion()
                readHardwareRevision()
            }

            if(DebugSettings.DEBUG_LOG_CONNECTION_STATE) {
                Log.d(LOG_TAG, "${probe.serialNumber} is ${probe.connectionState}")
            }
            _probeStateFlow.emit(probe)
        }
    }

    private suspend fun readFirmwareVersion() {
        withContext(Dispatchers.IO) {
            try {
                val fwVersionBytes = peripheral.read(FW_VERSION_CHARACTERISTIC)
                fwVersion = fwVersionBytes.toString(Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(LOG_TAG,
                    "Exception while reading remote FW version: \n${e.stackTrace}")
            }
        }
    }

    private suspend fun readHardwareRevision() {
        withContext(Dispatchers.IO) {
            try {
                val hwRevisionBytes = peripheral.read(HW_REVISION_CHARACTERISTIC)
                hwRevision = hwRevisionBytes.toString(Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(LOG_TAG,
                    "Exception while reading remote HW revision: \n${e.stackTrace}")
            }
        }
    }

    private suspend fun deviceStatusCharacteristicMonitor() {
        peripheral.observe(DEVICE_STATUS_CHARACTERISTIC).collect { data ->
            DeviceStatus.fromRawData(data.toUByteArray())?.let {
                _deviceStatusFlow.emit(it)
            }
        }
    }

    private suspend fun deviceStatusMonitor() {
        _deviceStatusFlow.collect { status ->
            deviceStatus = status
            _probeStateFlow.emit(probe)
        }
    }

    private suspend fun uartTxMonitor() {
        peripheral.observe(UART_TX_CHARACTERISTIC)
            .onCompletion {
                if(DebugSettings.DEBUG_LOG_BLE_UART_IO) {
                    Log.d(LOG_TAG, "UART-TX Monitor Done")
                }
            }
            .catch { exception ->
                Log.i(LOG_TAG, "UART-TX Monitor Catch: $exception")
            }
            .collect { data ->
                if(DebugSettings.DEBUG_LOG_BLE_UART_IO) {
                    val packet = data.toUByteArray().joinToString(""){ ubyte ->
                        ubyte.toString(16).padStart(2, '0').uppercase()
                    }
                    Log.d(LOG_TAG, "UART-RX: $packet")
                }
                when(val response = Response.fromData(data.toUByteArray())) {
                    is LogResponse -> {
                        _logResponseFlow.emit(response)
                    }
            }
        }
    }

    private fun toProbe(): Probe {
        val temps = deviceStatus?.temperatures ?: advertisingData.probeTemperatures
        val minSeq = deviceStatus?.minSequenceNumber ?: 0u
        val maxSeq = deviceStatus?.maxSequenceNumber ?: 0u
        val rssi  = if(isConnected.get()) remoteRssi.get() else advertisingData.rssi

        return Probe(
            advertisingData.serialNumber,
            advertisingData.mac,
            fwVersion,
            hwRevision,
            temps,
            rssi,
            minSeq,
            maxSeq,
            connectionState,
            uploadState
        )
    }
}