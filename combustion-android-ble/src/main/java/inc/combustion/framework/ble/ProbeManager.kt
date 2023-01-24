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
    advertisement: LegacyProbeAdvertisingData,
    adapter: BluetoothAdapter
) {
    companion object {
        private const val PROBE_IDLE_TIMEOUT_MS = 15000L
        private const val PROBE_INSTANT_READ_IDLE_TIMEOUT_MS = 5000L
        private const val PROBE_PREDICTION_IDLE_TIMEOUT_MS = 15000L
        private val MAX_PREDICTION_SECONDS = 60u * 60u * 4u
        private val LOW_RESOLUTION_CUTOFF_SECONDS = 60u * 5u
        private val LOW_RESOLUTION_PRECISION_SECONDS = 15u
        private val PREDICTION_TIME_UPDATE_COUNT = 3u
    }

    private val _probeStateFlow = MutableSharedFlow<Probe>(
        replay = 0, extraBufferCapacity = 10, BufferOverflow.DROP_OLDEST)

    internal val _deviceStatusFlow = MutableSharedFlow<ProbeStatus>(
        replay = 0, extraBufferCapacity = 10, BufferOverflow.DROP_OLDEST)

    private val _logResponseFlow = MutableSharedFlow<LogResponse>(
        replay = 0, extraBufferCapacity = 50, BufferOverflow.SUSPEND)

    private var _probe: Probe = Probe.create(mac = mac)

    private val instantReadMonitor = IdleMonitor()
    private val predictionStatusMonitor = IdleMonitor()

    private var predictionCountdownSeconds: UInt? = null
    private var uploadState: ProbeUploadState = ProbeUploadState.Unavailable

    protected val probeBleDevice = ProbeBleDevice(mac, owner, advertisement, adapter)

    val probeStateFlow = _probeStateFlow.asSharedFlow()
    val deviceStatusFlow = _deviceStatusFlow.asSharedFlow()
    val logResponseFlow = _logResponseFlow.asSharedFlow()
    val probe: Probe get() = update()

    init {
        probeBleDevice.observeRemoteRssi {
            _probeStateFlow.emit(probe)
        }

        probeBleDevice.observeConnectionState {
            connectionStateChangeHandler(it)
        }

        probeBleDevice.observeProbeStatusNotifications {
            _deviceStatusFlow.emit(it)
            _probeStateFlow.emit(probe)
        }

        probeBleDevice.observeOutOfRange(PROBE_IDLE_TIMEOUT_MS) {
            _probeStateFlow.emit(probe)
        }
    }

    open fun connect() = probeBleDevice.connect()
    open fun disconnect() = probeBleDevice.disconnect()

    open fun sendLogRequest(minSequence: UInt, maxSequence: UInt) {
        probeBleDevice.sendLogRequest(minSequence, maxSequence) {
            owner.lifecycleScope.launch {
                _logResponseFlow.emit(it)
            }
        }
    }

    open fun sendSetProbeColor(color: ProbeColor, completionHandler: (Boolean) -> Unit ) {
        probeBleDevice.sendSetProbeColor(color, completionHandler)
    }

    open fun sendSetProbeID(id: ProbeID, completionHandler: (Boolean) -> Unit) {
        probeBleDevice.sendSetProbeID(id, completionHandler)
    }

    open fun sendSetPrediction(setPointTemperatureC: Double, mode: ProbePredictionMode, completionHandler: (Boolean) -> Unit) {
        probeBleDevice.sendSetPrediction(setPointTemperatureC, mode, completionHandler)
    }

    fun finish() {
        probeBleDevice.finish()
    }

    protected suspend fun simulatedProbeStatus(status: ProbeStatus) {
        probeBleDevice.simulateProbeStatus(status)
        _probeStateFlow.emit(probe)
    }

    private suspend fun connectionStateChangeHandler(state: DeviceConnectionState)  {
        if(probeBleDevice.isConnected.get()) {
            owner.lifecycleScope.launch(Dispatchers.IO) {
                probeBleDevice.readFirmwareVersion()
                probeBleDevice.readHardwareRevision()
                probeBleDevice.sendSessionInformationRequest()
            }
        }
        else {
            // Not completely excited about having to make this call to clear out variables.
            probeBleDevice.disconnected()
        }

        if(DebugSettings.DEBUG_LOG_CONNECTION_STATE) {
            Log.d(LOG_TAG, "${probe.serialNumber} is ${probe.connectionState}")
        }

        _probeStateFlow.emit(probe)
    }

    // TODO: Not excited about needing to have the LogManager add jobs this way to make sure that
    // coroutines are cleaned up on exit.  With where things are going, I wonder if LogManager needs
    // to be a member of this class.
    fun addJob(job: Job) {
        probeBleDevice.jobManager.addJob(job)
    }

    // TODO: Not particular exited about how this data is making it into the base BleDevice.  The
    // CombustionService currently observes the scanner, which is why it pushes this data down.  Might
    // consider a way to do something like ...
    //
    //      probeBleDevice.observeAdvertisements()
    //
    suspend fun onNewAdvertisement(advertisement: LegacyProbeAdvertisingData) {
        probeBleDevice.handleAdvertisement(advertisement)

        val advConnectable = probeBleDevice.connectionState == DeviceConnectionState.ADVERTISING_CONNECTABLE
        val advNotConnecteable = probeBleDevice.connectionState == DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE
        val isAdvertising = advConnectable || advNotConnecteable

        if(isAdvertising) {
            probeBleDevice.disconnected()
            _probeStateFlow.emit(probe)
        }
    }

    // TODO: Not particular exited about how this data is making it into the base BleDevice.  Need to
    //  consider the best way to connect LogManager upload state handling.
    suspend fun onNewUploadState(newUploadState: ProbeUploadState) {
        // only update and emit on state change
        if (uploadState != newUploadState ) {
            uploadState = newUploadState
            _probeStateFlow.emit(probe)
        }
    }

    // TODO: We need to figure out the best way to tease apart this monstrosity.  This will be needed
    // when we are managing data coming in from multiple sources (e.g. probe direct connection and
    // meatnet repeater connection).
    private fun update(): Probe {
        _probe = _probe.copy(
                baseDevice = Device(
                    serialNumber = probeBleDevice.advertisingData.serialNumber,
                    mac = probeBleDevice.advertisingData.mac,
                    rssi = if(probeBleDevice.isConnected.get()) probeBleDevice.rssi else probeBleDevice.advertisingData.rssi,
                    connectionState = probeBleDevice.connectionState,
                    fwVersion = probeBleDevice.firmwareVersion,
                    hwRevision = probeBleDevice.hardwareRevision
                ),
                sessionInfo = probeBleDevice.sessionInfo,
                minSequenceNumber = probeBleDevice.probeStatus?.minSequenceNumber ?: _probe.minSequenceNumber,
                maxSequenceNumber = probeBleDevice.probeStatus?.maxSequenceNumber ?: _probe.maxSequenceNumber,
                id = probeBleDevice.probeStatus?.id ?: probeBleDevice.advertisingData.id,
                color = probeBleDevice.probeStatus?.color ?: probeBleDevice.advertisingData.color,
                batteryStatus = probeBleDevice.probeStatus?.batteryStatus ?: probeBleDevice.advertisingData.batteryStatus,
                uploadState = uploadState
        )

        val temperatures = probeBleDevice.probeStatus?.temperatures ?: probeBleDevice.advertisingData.probeTemperatures
        val mode = probeBleDevice.probeStatus?.mode ?: probeBleDevice.advertisingData.mode

        if(mode == ProbeMode.INSTANT_READ) {
            instantReadMonitor.activity()

            _probe = _probe.copy(
                instantReadCelsius = temperatures.values[0]
            )
        }
        else if(mode == ProbeMode.NORMAL) {
            val virtualSensors = probeBleDevice.probeStatus?.virtualSensors ?: probeBleDevice.advertisingData.virtualSensors
            val instantReadCelsius = if(!instantReadMonitor.isIdle(PROBE_INSTANT_READ_IDLE_TIMEOUT_MS)) _probe.instantReadCelsius else null

            predictionStatusMonitor.activity()

            // handle large predictions and prediction resolution
            probeBleDevice.predictionStatus?.let {
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

        // Calculate if prediction has gone stale
        val predictionStale = predictionStatusMonitor.isIdle(PROBE_PREDICTION_IDLE_TIMEOUT_MS)

        _probe = _probe.copy(
            predictionState = probeBleDevice.predictionStatus?.predictionState,
            predictionMode = probeBleDevice.predictionStatus?.predictionMode,
            predictionType = probeBleDevice.predictionStatus?.predictionType,
            setPointTemperatureCelsius = probeBleDevice.predictionStatus?.setPointTemperature,
            heatStartTemperatureCelsius = probeBleDevice.predictionStatus?.heatStartTemperature,
            rawPredictionSeconds = probeBleDevice.predictionStatus?.predictionValueSeconds,
            predictionSeconds = predictionCountdownSeconds,
            estimatedCoreCelsius = probeBleDevice.predictionStatus?.estimatedCoreTemperature,
            predictionStale = predictionStale
        )

        return _probe
    }
}