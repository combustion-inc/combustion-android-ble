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
import com.juul.kable.State
import com.juul.kable.characteristicOf
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.uart.*
import inc.combustion.framework.service.*
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
    private val owner: LifecycleOwner,
    private var advertisingData: ProbeAdvertisingData,
    adapter: BluetoothAdapter
): Device(mac, owner, adapter) {

    companion object {
        private const val PROBE_IDLE_TIMEOUT_MS = 15000L
        private const val PROBE_REMOTE_RSSI_POLL_RATE_MS = 1000L
        private const val MESSAGE_HANDLER_POLL_RATE_MS = 1000L
        private const val DEV_INFO_SERVICE_UUID_STRING = "0000180A-0000-1000-8000-00805F9B34FB"
        private const val NEEDLE_SERVICE_UUID_STRING = "00000100-CAAB-3792-3D44-97AE51C1407A"
        private const val UART_SERVICE_UUID_STRING   = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
        private const val PROBE_INSTANT_READ_IDLE_TIMEOUT_MS = 5000L

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

    private var probeStatus: ProbeStatus? = null
    private var connectionState = DeviceConnectionState.OUT_OF_RANGE
    private var uploadState: ProbeUploadState = ProbeUploadState.Unavailable

    private var temperatures: ProbeTemperatures? = null
    private var instantRead: Double? = null
    private var coreTemperature: Double? = null
    private var surfaceTemperature: Double? = null
    private var ambientTemperature: Double? = null

    internal val isConnected = AtomicBoolean(false)
    internal val remoteRssi = AtomicInteger(0)

    internal var fwVersion: String? = null
    internal var hwRevision: String? = null
    private var sessionInfo: SessionInformation? = null

    // Class to store when BLE message was sent and the completion handler for message
    private data class MessageHandler (
        val timeSentMillis: Long,
        val completionHandler : (Boolean) -> Unit
    )

    private var setProbeColorMessageHandler: MessageHandler? = null
    private var setProbeIDMessageHandler: MessageHandler? = null

    private val _probeStateFlow =
        MutableSharedFlow<Probe>(0, 10, BufferOverflow.DROP_OLDEST)
    val probeStateFlow = _probeStateFlow.asSharedFlow()

    internal val _probeStatusFlow =
        MutableSharedFlow<ProbeStatus>(0, 10, BufferOverflow.DROP_OLDEST)
    val deviceStatusFlow = _probeStatusFlow.asSharedFlow()

    private val _logResponseFlow =
        MutableSharedFlow<LogResponse>(0, 50, BufferOverflow.SUSPEND)
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
            var exceptionCount = 0;
            while(isActive) {
                if(isConnected.get() && mac != SimulatedProbeManager.SIMULATED_MAC) {
                    try {
                        remoteRssi.set(peripheral.rssi())
                        exceptionCount = 0;
                    } catch (e: Exception) {
                        exceptionCount++
                        Log.w(LOG_TAG, "Exception while reading remote RSSI: $exceptionCount\n${e.stackTrace}")
                    }

                    when {
                        exceptionCount < 5 -> _probeStateFlow.emit(probe)
                        else -> peripheral.disconnect()
                    }
                }
                delay(PROBE_REMOTE_RSSI_POLL_RATE_MS)
            }
        })
        // Message handler polling job
        addJob(owner.lifecycleScope.launch(Dispatchers.IO) {
            while(isActive) {
                if(isConnected.get() && mac != SimulatedProbeManager.SIMULATED_MAC) {
                    setProbeColorMessageHandler?.let { messageHandler ->
                        if((System.currentTimeMillis() - messageHandler.timeSentMillis) > 5000) {
                            messageHandler.completionHandler(false)
                            setProbeColorMessageHandler = null
                        }
                    }
                    setProbeIDMessageHandler?.let { messageHandler ->
                        if((System.currentTimeMillis() - messageHandler.timeSentMillis) > 5000) {
                            messageHandler.completionHandler(false)
                            setProbeIDMessageHandler = null
                        }
                    }
                }
                delay(MESSAGE_HANDLER_POLL_RATE_MS)
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

    open fun sendSetProbeColor(owner: LifecycleOwner, color: ProbeColor, completionHandler: (Boolean) -> Unit ) {
        if(setProbeColorMessageHandler == null) {
            setProbeColorMessageHandler = MessageHandler(System.currentTimeMillis(), completionHandler)
            sendUartRequest(owner, SetColorRequest(color))
        }
        else {
            // Respond with failure because a set Color is already in progress
            completionHandler(false)
        }
    }

    open fun sendSetProbeID(owner: LifecycleOwner, id: ProbeID, completionHandler: (Boolean) -> Unit) {
        if(setProbeIDMessageHandler == null) {
            setProbeIDMessageHandler = MessageHandler(System.currentTimeMillis(), completionHandler)
            sendUartRequest(owner, SetIDRequest(id))
        }
        else {
            // Respond with failure because a set Color is already in progress
            completionHandler(false)
        }
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
            probeStatus = null
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
            } catch(e: Exception)  {
                Log.w(LOG_TAG, "UART-TX: Unable to write to TX characteristic.")
            }
        }
    }

    private suspend fun connectionStateMonitor() {
        peripheral.state.onCompletion {
            Log.d(LOG_TAG, "Connection Stater Monitor Complete")
        }
        .catch {
            Log.i(LOG_TAG, "Connection State Monitor Catch: $it")
        }
        .collect { state ->
            monitor.activity()

            connectionState = when(state) {
                is State.Connecting -> DeviceConnectionState.CONNECTING
                State.Connected -> DeviceConnectionState.CONNECTED
                State.Disconnecting -> DeviceConnectionState.DISCONNECTING
                is State.Disconnected -> DeviceConnectionState.DISCONNECTED
            }

            isConnected.set(connectionState == DeviceConnectionState.CONNECTED)

            if(isConnected.get()) {
                getProbeConfiguration()
            }
            else {
                probeStatus = null
                sessionInfo = null
            }

            if(DebugSettings.DEBUG_LOG_CONNECTION_STATE) {
                Log.d(LOG_TAG, "${probe.serialNumber} is ${probe.connectionState}")
            }
            _probeStateFlow.emit(probe)
        }
    }

    private fun getProbeConfiguration() {
        owner.lifecycleScope.launch(Dispatchers.IO) {

            // the following operations can take some time to complete, so
            // we run them in a separate coroutine, so that we can receive
            // the state updates in connectionStatusMonitor above.  And if
            // we are disconnected, then stop this sequence of trying to
            // read configuration from the probe.

            if(isConnected.get())
                readFirmwareVersion()

            if(isConnected.get())
                readHardwareRevision()

            if(isConnected.get())
                requestSessionInformation()

            /* TODO -- Remove This Integration Code
            if(isConnected.get()) {
                Log.e(LOG_TAG, "Sending SetPredictionRequest")
                sendUartRequest(owner, SetPredictionRequest(102.5, ProbePredictionMode.TIME_TO_REMOVAL))
            }
             */
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

    private fun requestSessionInformation() {
        sendUartRequest(owner, SessionInfoRequest())
    }

    private suspend fun deviceStatusCharacteristicMonitor() {
        peripheral.observe(DEVICE_STATUS_CHARACTERISTIC)
            .onCompletion {
                Log.d(LOG_TAG, "Device Status Characteristic Monitor Complete")
            }
            .catch {
                Log.i(LOG_TAG, "Device Status Characteristic Monitor Catch: $it")
            }
            .collect { data ->
                ProbeStatus.fromRawData(data.toUByteArray())?.let {
                    _probeStatusFlow.emit(it)
                }
        }
    }

    private suspend fun deviceStatusMonitor() {
        _probeStatusFlow
            .onCompletion {
                Log.d(LOG_TAG, "Device Status Monitor Complete")
            }
            .catch {
                Log.i(LOG_TAG, "Device Status Monitor Catch: $it")
            }
            .collect { status ->
                probeStatus = status
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
                val responses = Response.fromData(data.toUByteArray())

                for (response in responses) {
                    when (response) {
                        is LogResponse -> {
                            _logResponseFlow.emit(response)
                        }
                        is SetColorResponse -> {
                            setProbeColorMessageHandler?.let {
                                it.completionHandler(response.success)
                                setProbeColorMessageHandler = null
                            }
                        }
                        is SetIDResponse -> {
                            setProbeIDMessageHandler?.let {
                                it.completionHandler(response.success)
                                setProbeIDMessageHandler = null
                            }
                        }
                        is SessionInfoResponse -> {
                            sessionInfo = response.sessionInformation
                        }
                        is SetPredictionResponse -> {
                            // TODO -- Plumb Through
                            Log.e(LOG_TAG, "Set Prediction Response: ${response.success}")
                        }
                    }
                }
            }
    }

    private fun toProbe(): Probe {
        val temps = probeStatus?.temperatures ?: advertisingData.probeTemperatures
        val minSeq = probeStatus?.minSequenceNumber ?: 0u
        val maxSeq = probeStatus?.maxSequenceNumber ?: 0u
        val rssi  = if(isConnected.get()) remoteRssi.get() else advertisingData.rssi
        val id = probeStatus?.id ?: advertisingData.id
        val color = probeStatus?.color ?: advertisingData.color
        val mode = probeStatus?.mode ?: advertisingData.mode
        val batteryStatus = probeStatus?.batteryStatus ?: advertisingData.batteryStatus
        val virtualSensors = probeStatus?.virtualSensors ?: advertisingData.virtualSensors
        val hopCount = probeStatus?.hopCount ?: advertisingData.hopCount
        val predictionState = probeStatus?.predictionStatus?.predictionState ?: null
        val predictionMode = probeStatus?.predictionStatus?.predictionMode ?: null
        val predictionType = probeStatus?.predictionStatus?.predictionType ?: null
        val setPointTemperatureC = probeStatus?.predictionStatus?.setPointTemperature ?: null
        val heatStartTemperatureC = probeStatus?.predictionStatus?.heatStartTemperature ?: null
        val predictionS = probeStatus?.predictionStatus?.predictionValueSeconds ?: null
        val estimatedCoreC = probeStatus?.predictionStatus?.estimatedCoreTemperature ?: null

        if(mode == ProbeMode.INSTANT_READ) {
            instantReadMonitor.activity()
            instantRead = temps.values[0]
        } else {
            temperatures = temps
            ambientTemperature = temps.values[7]

            coreTemperature = when(virtualSensors.virtualCoreSensor) {
                ProbeVirtualSensors.VirtualCoreSensor.T1 -> temps.values[0]
                ProbeVirtualSensors.VirtualCoreSensor.T2 -> temps.values[1]
                ProbeVirtualSensors.VirtualCoreSensor.T3 -> temps.values[2]
                ProbeVirtualSensors.VirtualCoreSensor.T4 -> temps.values[3]
                ProbeVirtualSensors.VirtualCoreSensor.T5 -> temps.values[4]
                ProbeVirtualSensors.VirtualCoreSensor.T6 -> temps.values[5]
            }

            surfaceTemperature = when(virtualSensors.virtualSurfaceSensor) {
                ProbeVirtualSensors.VirtualSurfaceSensor.T4 -> temps.values[3]
                ProbeVirtualSensors.VirtualSurfaceSensor.T5 -> temps.values[4]
                ProbeVirtualSensors.VirtualSurfaceSensor.T6 -> temps.values[5]
                ProbeVirtualSensors.VirtualSurfaceSensor.T7 -> temps.values[6]
            }

            if(instantReadMonitor.isIdle(PROBE_INSTANT_READ_IDLE_TIMEOUT_MS)) {
                instantRead = null
            }
        }

        return Probe(
            advertisingData.serialNumber,
            advertisingData.mac,
            fwVersion,
            hwRevision,
            sessionInfo,
            temperatures,
            instantRead,
            coreTemperature,
            surfaceTemperature,
            ambientTemperature,
            rssi,
            minSeq,
            maxSeq,
            connectionState,
            uploadState,
            id,
            color,
            mode,
            batteryStatus,
            virtualSensors,
            hopCount,
            predictionState,
            predictionMode,
            predictionType,
            setPointTemperatureC,
            heatStartTemperatureC,
            predictionS,
            estimatedCoreC
        )
    }
}