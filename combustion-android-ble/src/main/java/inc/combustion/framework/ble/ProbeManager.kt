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
import com.juul.kable.characteristicOf
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.uart.*
import inc.combustion.framework.service.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.*
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
    private var advertisingData: LegacyProbeAdvertisingData,
    adapter: BluetoothAdapter
): BleDevice(mac, owner, adapter) {

    companion object {
        private const val PROBE_IDLE_TIMEOUT_MS = 15000L
        private const val PROBE_REMOTE_RSSI_POLL_RATE_MS = 1000L
        private const val MESSAGE_HANDLER_POLL_RATE_MS = 1000L
        //private const val DEV_INFO_SERVICE_UUID_STRING = "0000180A-0000-1000-8000-00805F9B34FB"
        private const val NEEDLE_SERVICE_UUID_STRING = "00000100-CAAB-3792-3D44-97AE51C1407A"
        private const val UART_SERVICE_UUID_STRING   = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
        private const val PROBE_INSTANT_READ_IDLE_TIMEOUT_MS = 5000L
        private const val PROBE_PREDICTION_IDLE_TIMEOUT_MS = 15000L
        private val MAX_PREDICTION_SECONDS = 60u * 60u * 4u
        private val LOW_RESOLUTION_CUTOFF_SECONDS = 60u * 5u
        private val LOW_RESOLUTION_PRECISION_SECONDS = 15u
        private val PREDICTION_TIME_UPDATE_COUNT = 3u

        /*
        val FW_VERSION_CHARACTERISTIC = characteristicOf(
            service = DEV_INFO_SERVICE_UUID_STRING,
            characteristic = "00002A26-0000-1000-8000-00805F9B34FB"
        )
        val HW_REVISION_CHARACTERISTIC = characteristicOf(
            service = DEV_INFO_SERVICE_UUID_STRING,
            characteristic = "00002A27-0000-1000-8000-00805F9B34FB"
        )
         */

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
    private var predictionStatus: PredictionStatus? = null
    private var predictionCountdownSeconds: UInt? = null
    // private var connectionState = DeviceConnectionState.OUT_OF_RANGE
    private var uploadState: ProbeUploadState = ProbeUploadState.Unavailable

    // internal val isConnected = AtomicBoolean(false)
    internal val remoteRssi = AtomicInteger(0)

    // internal var fwVersion: String? = null
    // internal var hwRevision: String? = null
    private var sessionInfo: SessionInformation? = null

    // Class to store when BLE message was sent and the completion handler for message
    private data class MessageHandler (
        val timeSentMillis: Long,
        val completionHandler : (Boolean) -> Unit
    )

    private var setProbeColorMessageHandler: MessageHandler? = null
    private var setProbeIDMessageHandler: MessageHandler? = null
    private var setPredictionMessageHandler: MessageHandler ?= null

    private val _probeStateFlow =
        MutableSharedFlow<Probe>(0, 10, BufferOverflow.DROP_OLDEST)
    val probeStateFlow = _probeStateFlow.asSharedFlow()

    internal val _probeStatusFlow =
        MutableSharedFlow<ProbeStatus>(0, 10, BufferOverflow.DROP_OLDEST)
    val deviceStatusFlow = _probeStatusFlow.asSharedFlow()

    private val _logResponseFlow =
        MutableSharedFlow<LogResponse>(0, 50, BufferOverflow.SUSPEND)
    val logResponseFlow = _logResponseFlow.asSharedFlow()

    private var _probe: Probe = Probe(baseDevice = Device(mac = mac))
    val probe: Probe get() = update()

    init {
        // connection state flow monitor
        /*
        addJob(owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                connectionStateMonitor()
            }
        })
        */
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
        // RSSI polling job
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
                    setPredictionMessageHandler?.let { messageHandler ->
                        if((System.currentTimeMillis() - messageHandler.timeSentMillis) > 5000) {
                            messageHandler.completionHandler(false)
                            setPredictionMessageHandler = null
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
            // Respond with failure because a set color is already in progress
            completionHandler(false)
        }
    }

    open fun sendSetProbeID(owner: LifecycleOwner, id: ProbeID, completionHandler: (Boolean) -> Unit) {
        if(setProbeIDMessageHandler == null) {
            setProbeIDMessageHandler = MessageHandler(System.currentTimeMillis(), completionHandler)
            sendUartRequest(owner, SetIDRequest(id))
        }
        else {
            // Respond with failure because a set ID is already in progress
            completionHandler(false)
        }
    }

    open fun sendSetPrediction(owner: LifecycleOwner, setPointTemperatureC: Double, mode: ProbePredictionMode, completionHandler: (Boolean) -> Unit) {
        if(setPredictionMessageHandler == null)  {
            setPredictionMessageHandler = MessageHandler(System.currentTimeMillis(), completionHandler)
            sendUartRequest(owner, SetPredictionRequest(setPointTemperatureC, mode))
        }
        else {
            // Response with failure because a set prediction is already in progress.
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

    suspend fun onNewAdvertisement(newAdvertisingData: LegacyProbeAdvertisingData) {
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

    /*
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
     */

    override suspend fun onConnectionStateChanged(newConnectionState: DeviceConnectionState) {
        if(isConnected.get()) {
            getProbeConfiguration()
        }
        else {
            probeStatus = null
            sessionInfo = null
            predictionStatus = null
        }

        if(DebugSettings.DEBUG_LOG_CONNECTION_STATE) {
            Log.d(LOG_TAG, "${probe.serialNumber} is ${probe.connectionState}")
        }
        _probeStateFlow.emit(probe)
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
        }
    }

    /*
    private suspend fun readFirmwareVersion() {
        withContext(Dispatchers.IO) {
            try {
                val fwVersionBytes = peripheral.read(FW_VERSION_CHARACTERISTIC)
                fwVersion = fwVersionBytes.toString(Charsets.UTF_8)

                _probe = _probe.copy(fwVersion = fwVersion)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Exception while reading remote FW version: \n${e.stackTrace}")
            }
        }
    }

    private suspend fun readHardwareRevision() {
        withContext(Dispatchers.IO) {
            try {
                val hwRevisionBytes = peripheral.read(HW_REVISION_CHARACTERISTIC)
                val rev = hwRevisionBytes.toString(Charsets.UTF_8)

                _probe = _probe.copy(hwRevision = rev)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Exception while reading remote HW revision: \n${e.stackTrace}")
            }
        }
    }
    */

    private fun requestSessionInformation() {
        sendUartRequest(owner, SessionInfoRequest())
    }

    private val instantReadMonitor = IdleMonitor()
    private val predictionStatusMonitor = IdleMonitor()

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
                            setPredictionMessageHandler?.let {
                                it.completionHandler(response.success)
                                setPredictionMessageHandler = null
                            }
                        }
                    }
                }
            }
    }

    private fun update(): Probe {
        _probe = _probe.copy(
                baseDevice = Device(
                    serialNumber = advertisingData.serialNumber,
                    mac = advertisingData.mac,
                    rssi = if(isConnected.get()) remoteRssi.get() else advertisingData.rssi,
                    connectionState = connectionState,
                ),
                sessionInfo = sessionInfo,
                minSequenceNumber = probeStatus?.minSequenceNumber ?: _probe.minSequenceNumber,
                maxSequenceNumber = probeStatus?.maxSequenceNumber ?: _probe.maxSequenceNumber,
                id = probeStatus?.id ?: advertisingData.id,
                color = probeStatus?.color ?: advertisingData.color,
                batteryStatus = probeStatus?.batteryStatus ?: advertisingData.batteryStatus,
                uploadState = uploadState
        )

        val temperatures = probeStatus?.temperatures ?: advertisingData.probeTemperatures
        val mode = probeStatus?.mode ?: advertisingData.mode

        if(mode == ProbeMode.INSTANT_READ) {
            instantReadMonitor.activity()

            _probe = _probe.copy(
                instantReadCelsius = temperatures.values[0]
            )
        }
        else if(mode == ProbeMode.NORMAL) {
            val virtualSensors = probeStatus?.virtualSensors ?: advertisingData.virtualSensors
            val instantReadCelsius = if(!instantReadMonitor.isIdle(PROBE_INSTANT_READ_IDLE_TIMEOUT_MS)) _probe.instantReadCelsius else null

            predictionStatusMonitor.activity()

            predictionStatus = probeStatus?.predictionStatus

            // handle large predictions and prediction resolution
            predictionStatus?.let {
                val rawPrediction = it.predictionValueSeconds

                predictionCountdownSeconds = if(rawPrediction > MAX_PREDICTION_SECONDS) {
                    null
                }
                else if(rawPrediction < LOW_RESOLUTION_CUTOFF_SECONDS) {
                    rawPrediction
                }
                else if(predictionCountdownSeconds == null || (_probe.maxSequenceNumber % PREDICTION_TIME_UPDATE_COUNT) == 0u) {
                    val remainder = rawPrediction % LOW_RESOLUTION_PRECISION_SECONDS
                    if(remainder > (LOW_RESOLUTION_PRECISION_SECONDS / 2u)) {
                        // round up
                        rawPrediction + (LOW_RESOLUTION_PRECISION_SECONDS - remainder)
                    }
                    else {
                        // round down
                        rawPrediction - remainder
                    }
                }
                else {
                    predictionCountdownSeconds
                }
            }

            _probe = _probe.copy(
                instantReadCelsius = instantReadCelsius,
                temperaturesCelsius = temperatures,
                virtualSensors = virtualSensors,
                coreTemperatureCelsius = when(virtualSensors.virtualCoreSensor) {
                    ProbeVirtualSensors.VirtualCoreSensor.T1 -> temperatures.values[0]
                    ProbeVirtualSensors.VirtualCoreSensor.T2 -> temperatures.values[1]
                    ProbeVirtualSensors.VirtualCoreSensor.T3 -> temperatures.values[2]
                    ProbeVirtualSensors.VirtualCoreSensor.T4 -> temperatures.values[3]
                    ProbeVirtualSensors.VirtualCoreSensor.T5 -> temperatures.values[4]
                    ProbeVirtualSensors.VirtualCoreSensor.T6 -> temperatures.values[5]
                },
                surfaceTemperatureCelsius = when(virtualSensors.virtualSurfaceSensor) {
                    ProbeVirtualSensors.VirtualSurfaceSensor.T4 -> temperatures.values[3]
                    ProbeVirtualSensors.VirtualSurfaceSensor.T5 -> temperatures.values[4]
                    ProbeVirtualSensors.VirtualSurfaceSensor.T6 -> temperatures.values[5]
                    ProbeVirtualSensors.VirtualSurfaceSensor.T7 -> temperatures.values[6]
                },
                ambientTemperatureCelsius = when(virtualSensors.virtualAmbientSensor) {
                    ProbeVirtualSensors.VirtualAmbientSensor.T5 -> temperatures.values[4]
                    ProbeVirtualSensors.VirtualAmbientSensor.T6 -> temperatures.values[5]
                    ProbeVirtualSensors.VirtualAmbientSensor.T7 -> temperatures.values[6]
                    ProbeVirtualSensors.VirtualAmbientSensor.T8 -> temperatures.values[7]
                }
            )
        }

        if(predictionStatusMonitor.isIdle(PROBE_PREDICTION_IDLE_TIMEOUT_MS)) {
            predictionStatus = null
            predictionCountdownSeconds = null
        }

        _probe = _probe.copy(
            predictionState = predictionStatus?.predictionState,
            predictionMode = predictionStatus?.predictionMode,
            predictionType = predictionStatus?.predictionType,
            setPointTemperatureCelsius = predictionStatus?.setPointTemperature,
            heatStartTemperatureCelsius = predictionStatus?.heatStartTemperature,
            rawPredictionSeconds = predictionStatus?.predictionValueSeconds,
            predictionSeconds = predictionCountdownSeconds,
            estimatedCoreCelsius = predictionStatus?.estimatedCoreTemperature
        )

        return _probe
    }

    /*
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

        if(mode == ProbeMode.INSTANT_READ) {
            instantReadMonitor.activity()
            instantRead = temps.values[0]
        } else {
            //temperatures = temps
            predictionStatus = probeStatus?.predictionStatus

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

            ambientTemperature = when(virtualSensors.virtualAmbientSensor) {
                ProbeVirtualSensors.VirtualAmbientSensor.T5 -> temps.values[4]
                ProbeVirtualSensors.VirtualAmbientSensor.T6 -> temps.values[5]
                ProbeVirtualSensors.VirtualAmbientSensor.T7 -> temps.values[6]
                ProbeVirtualSensors.VirtualAmbientSensor.T8 -> temps.values[7]
            }

            if(instantReadMonitor.isIdle(PROBE_INSTANT_READ_IDLE_TIMEOUT_MS)) {
                instantRead = null
            }
        }

        val predictionState = predictionStatus?.predictionState
        val predictionMode = predictionStatus?.predictionMode
        val predictionType = predictionStatus?.predictionType
        val setPointTemperatureC = predictionStatus?.setPointTemperature
        val heatStartTemperatureC = predictionStatus?.heatStartTemperature
        val predictionS = predictionStatus?.predictionValueSeconds
        val estimatedCoreC = predictionStatus?.estimatedCoreTemperature

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
            batteryStatus,
            virtualSensors,
            predictionState,
            predictionMode,
            predictionType,
            setPointTemperatureC,
            heatStartTemperatureC,
            predictionS,
            predictionS,
            estimatedCoreC
        )
    }
     */
}