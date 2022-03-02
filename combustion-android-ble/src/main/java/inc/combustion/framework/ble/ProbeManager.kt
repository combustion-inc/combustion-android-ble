package inc.combustion.framework.ble

import android.bluetooth.BluetoothAdapter
import android.os.ParcelUuid
import android.util.Log
import androidx.lifecycle.*
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

open class ProbeManager (
    mac: String,
    owner: LifecycleOwner,
    private var _advertisingData: ProbeAdvertisingData,
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

    private val _probeStateFlow =
        MutableSharedFlow<Probe>(FLOW_CONFIG_REPLAY, FLOW_CONFIG_BUFFER, BufferOverflow.DROP_OLDEST)
    private val _deviceStatusFlow =
        MutableSharedFlow<DeviceStatus>(FLOW_CONFIG_REPLAY, FLOW_CONFIG_BUFFER, BufferOverflow.DROP_OLDEST)
    private val _logResponseFlow =
        MutableSharedFlow<LogResponse>(FLOW_CONFIG_REPLAY*5, FLOW_CONFIG_BUFFER*5, BufferOverflow.SUSPEND)
    private val _isConnected = AtomicBoolean(false)
    private val _remoteRssi = AtomicInteger(0)
    private var _hasDeviceStatus = false
    private var _deviceStatus = DeviceStatus()
    private var _connectionState = DeviceConnectionState.OUT_OF_RANGE
    private var _uploadState: ProbeUploadState = ProbeUploadState.Unavailable
    private var _fwVersion: String? = null

    val probeStateFlow = _probeStateFlow.asSharedFlow()
    val deviceStatusFlow = _deviceStatusFlow.asSharedFlow()
    val logResponseFlow = _logResponseFlow.asSharedFlow()
    val probe: Probe get() = toProbe()

    init {
        // create long running coroutines for consuming off of flows.  add
        // jobs to Job list so that they can be cleaned up as needed.

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
                if(_isConnected.get()) {
                    _remoteRssi.set(peripheral.rssi())
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
            _connectionState = when(_connectionState) {
                DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE -> DeviceConnectionState.OUT_OF_RANGE
                DeviceConnectionState.ADVERTISING_CONNECTABLE -> DeviceConnectionState.OUT_OF_RANGE
                DeviceConnectionState.DISCONNECTED -> DeviceConnectionState.OUT_OF_RANGE
                else -> _connectionState
            }
        }
        if(idle) {
            _probeStateFlow.emit(probe)
        }
    }

    fun sendLogRequest(owner: LifecycleOwner, minSequence: UInt, maxSequence: UInt) {
        sendUartRequest(owner, LogRequest(minSequence, maxSequence))
    }

    suspend fun onNewUploadState(uploadState: ProbeUploadState) {
        // only update and emit on state change
        if (_uploadState != uploadState ) {
            _uploadState = uploadState
            _probeStateFlow.emit(probe)
        }
    }

    suspend fun onNewAdvertisement(advertisingData: ProbeAdvertisingData) {
        // the probe continues to advertise even while a BLE connection is
        // established.  determine if the device is currently advertising as
        // connectable or not.
        val advertisingState = when(advertisingData.isConnectable) {
            true -> DeviceConnectionState.ADVERTISING_CONNECTABLE
            else -> DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE
        }
        // if the device is advertising as connectable, advertising as non-connectable,
        // currently disconnected, or currently out of range then it's new state is the
        // advertising state determined above. otherwise, (connected, connected or
        // disconnecting) the state is unchanged by the advertising packet.
        _connectionState = when(_connectionState) {
            DeviceConnectionState.ADVERTISING_CONNECTABLE -> advertisingState
            DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE -> advertisingState
            DeviceConnectionState.OUT_OF_RANGE -> advertisingState
            DeviceConnectionState.DISCONNECTED -> advertisingState
            else -> _connectionState
        }
        // if our new state is advertising, then emit the data.  and kick the monitor
        // to indicate activity.
        if(_connectionState == DeviceConnectionState.ADVERTISING_CONNECTABLE ||
                _connectionState == DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE ) {
            monitor.activity()
            _advertisingData = advertisingData
            _hasDeviceStatus = false
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
            peripheral.write(UART_RX_CHARACTERISTIC, request.sData)
        }
    }

    private suspend fun connectionStateMonitor() {
        peripheral.state
            .collect { connectionState ->
                monitor.activity()

                _connectionState = when(connectionState) {
                    is State.Connecting -> DeviceConnectionState.CONNECTING
                    State.Connected -> DeviceConnectionState.CONNECTED
                    State.Disconnecting -> DeviceConnectionState.DISCONNECTING
                    is State.Disconnected -> DeviceConnectionState.DISCONNECTED
                }

                _isConnected.set(_connectionState == DeviceConnectionState.CONNECTED)

                if(_connectionState != DeviceConnectionState.CONNECTED)
                    _hasDeviceStatus = false
                else
                    readFirmwareVersion()

                Log.d(LOG_TAG, "CONN  : ${probe.serialNumber} is ${probe.connectionState}")
                _probeStateFlow.emit(probe)
            }
    }

    private suspend fun readFirmwareVersion() {
        withContext(Dispatchers.IO) {
            val fwVersionBytes = peripheral.read(FW_VERSION_CHARACTERISTIC)
            _fwVersion = fwVersionBytes.toString(Charsets.UTF_8)
        }
    }

    private suspend fun deviceStatusCharacteristicMonitor() {
        peripheral.observe(DEVICE_STATUS_CHARACTERISTIC)
            .collect { data ->
                 _deviceStatusFlow.emit(DeviceStatus(data.toUByteArray()))
            }
    }

    private suspend fun deviceStatusMonitor() {
        _deviceStatusFlow
            .collect { status ->
                _deviceStatus = status
                _hasDeviceStatus = true
                _probeStateFlow.emit(probe)
            }
    }

    private suspend fun uartTxMonitor() {
        peripheral.observe(UART_TX_CHARACTERISTIC)
            .onCompletion {
                Log.d(LOG_TAG, "UART-TX Monitor Done?")
            }
            .catch { exception ->
                Log.d(LOG_TAG, "UART-TX Monitor Catch: $exception")
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
        val isConnected = _isConnected.get()
        val hasDeviceStatus = _hasDeviceStatus

        val temps = if(isConnected && hasDeviceStatus)
            _deviceStatus.temperatures
        else
            _advertisingData.probeTemperatures

        val rssi = if(isConnected)
            _remoteRssi.get()
        else
            _advertisingData.rssi

        val minSeq = if(isConnected && hasDeviceStatus)
            _deviceStatus.minSequenceNumber else 0u
        val maxSeq = if(isConnected && hasDeviceStatus)
            _deviceStatus.maxSequenceNumber else 0u

        return Probe(
            _advertisingData.serialNumber,
            _advertisingData.mac,
            _fwVersion,
            temps,
            rssi,
            minSeq,
            maxSeq,
            _connectionState,
            _uploadState
        )
    }
}