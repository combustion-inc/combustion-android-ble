/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeManager.kt
 * Author: https://github.com/miwright2
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

import android.util.Log
import androidx.lifecycle.LifecycleOwner
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.device.ProbeBleDevice
import inc.combustion.framework.ble.device.ProbeBleDeviceBase
import inc.combustion.framework.ble.device.RepeatedProbeBleDevice
import inc.combustion.framework.ble.scanning.CombustionAdvertisingData
import inc.combustion.framework.ble.uart.LogResponse
import inc.combustion.framework.service.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*

/**
 * This class is responsible for managing and arbitrating the data links to a temperature
 * probe.  When MeatNet is enabled that includes data links through repeater devices over
 * MeatNet and direct links to temperature probes.  When MeatNet is disabled, this class
 * manages only direct links to temperature probes.  The class is responsible for presenting
 * a common interface over both scenarios.
 *
 * @property owner LifecycleOnwer for coroutine scope.
 * @property settings Service settings.
 * @constructor
 * Constructs a probe manager
 *
 * @param serialNumber The serial number of the probe being managed.
 */
internal class ProbeManager(
    serialNumber: String,
    private val owner: LifecycleOwner,
    private val settings: DeviceManager.Settings
) {
    companion object {
        const val OUT_OF_RANGE_TIMEOUT = 15000L
    }

    // direct ble link to probe
    private var probeBleDevice: ProbeBleDevice? = null
    private val repeatedProbeBleDevices = mutableListOf<RepeatedProbeBleDevice>()

    // manages long-running coroutine scopes for data handling
    private val jobManager = JobManager()

    private var probeStatusCollectJob: Job? = null

    // holds the current state and data for this probe
    private var _probe = MutableStateFlow(Probe.create(serialNumber = serialNumber))

    // the flow that is consumed to get state and date updates
    val probeFlow = _probe.asStateFlow()

    // the flow that produces ProbeStatus updates from MeatNet
    private val _probeStatusFlow = MutableSharedFlow<ProbeStatus>(
        replay = 0, extraBufferCapacity = 10, BufferOverflow.DROP_OLDEST)

    // the flow that is consumed to get ProbeStatus updates from MeatNet
    val probeStatusFlow = _probeStatusFlow.asSharedFlow()

    // the flow that produces LogResponses from MeatNet
    private val _logResponseFlow = MutableSharedFlow<LogResponse>(
        replay = 0, extraBufferCapacity = 50, BufferOverflow.SUSPEND)

    // the flow that is consumed to get LogResponses from MeatNet
    val logResponseFlow = _logResponseFlow.asSharedFlow()

    // serial number of the probe that is being managed by this manager
    val serialNumber: String
        get() {
            return _probe.value.serialNumber
        }

    // current upload state for this probe, determined by LogManager
    var uploadState: ProbeUploadState
        get() {
            return _probe.value.uploadState
        }
        set(value) {
            if(value != _probe.value.uploadState) {
                _probe.value = _probe.value.copy(uploadState = value)
            }
        }

    val probe: Probe
        get() {
            return _probe.value
        }

    // current session information for the probe
    var sessionInfo: SessionInformation? = null
        private set

    // current minimum sequence number for the probe
    val minSequenceNumber: UInt
        get() {
            return _probe.value.minSequenceNumber
        }

    // current maximum sequence number for the probe
    val maxSequenceNumber: UInt
        get() {
            return _probe.value.maxSequenceNumber
        }

    fun addJob(job: Job) = jobManager.addJob(job)

    fun addProbe(probe: ProbeBleDevice) {
        if(probeBleDevice == null) {
            probeBleDevice = probe
            manage(probe)
            Log.i(LOG_TAG, "PM($serialNumber) is managing link ${probe.linkId}")
        }
    }

    fun addRepeatedProbe(repeatedProbe: RepeatedProbeBleDevice) {
        repeatedProbeBleDevices.add(repeatedProbe)
        manage(repeatedProbe)
        Log.i(LOG_TAG, "PM($serialNumber) is managing link ${repeatedProbe.linkId}")
    }

    fun connect() {
        // TODO: Need to Read Session Information Upon Connection To Probe
        TODO()
    }

    fun disconnect() {
        TODO()
    }

    fun postDfuReconnect() {
        TODO()
    }

    fun setProbeColor(color: ProbeColor, completionHandler: (Boolean) -> Unit) {
        TODO()
    }

    fun setProbeID(id: ProbeID, completionHandler: (Boolean) -> Unit) {
        TODO()
    }

    fun setPrediction(removalTemperatureC: Double, mode: ProbePredictionMode, completionHandler: (Boolean) -> Unit) {
        TODO()
    }

    fun cancelPrediction(completionHandler: (Boolean) -> Unit) {
        TODO()
    }

    fun sendLogRequest(startSequenceNumber: UInt, endSequenceNumber: UInt) {
        // for now we should route to the direct connected probe, right?
        TODO()
    }

    fun finish() {
       TODO()
    }

    private fun manage(base: ProbeBleDeviceBase) {
        _probe.value = _probe.value.copy(baseDevice = _probe.value.baseDevice.copy(mac = base.mac))

        base.observeAdvertisingPackets(serialNumber, base.mac) { advertisement -> handleAdvertisingPackets(base, advertisement) }
        base.observeConnectionState { state -> handleConnectionState(base, state) }
        base.observeOutOfRange(OUT_OF_RANGE_TIMEOUT){ handleOutOfRange(base) }
        base.observeRemoteRssi { rssi ->  handleRemoteRssi(base, rssi) }
    }

    private fun debuggingWithStaticLink(device: ProbeBleDeviceBase): Boolean {
        return when(device) {
            is ProbeBleDevice -> true
            is RepeatedProbeBleDevice -> true
            else -> false
        }
    }

    private fun shouldUpdateFromAdvertisingPacket(device: ProbeBleDeviceBase, hopCount: UInt): Boolean {
        // TODO: Implement Multiplexing Business Logic
        return debuggingWithStaticLink(device)
    }

    private fun shouldUpdateOnConnectionState(device: ProbeBleDeviceBase, hopCount: UInt, state: DeviceConnectionState): Boolean {
        // TODO: Implement Multiplexing Business Logic
        return debuggingWithStaticLink(device)
    }

    private fun shouldUpdateOnRemoteRssi(device: ProbeBleDeviceBase, hopCount: UInt, rssi: Int): Boolean {
        // TODO: Implement Multiplexing Business Logic
        return debuggingWithStaticLink(device)
    }

    private suspend fun handleAdvertisingPackets(device: ProbeBleDeviceBase, advertisement: CombustionAdvertisingData) {
        when(device) {
            is ProbeBleDevice -> {
                if(shouldUpdateFromAdvertisingPacket(device, advertisement.hopCount)) {
                    updateStateFromAdvertisement(
                        rssi = advertisement.rssi,
                        mode = advertisement.mode,
                        temperatures = advertisement.probeTemperatures,
                        sensors = advertisement.virtualSensors,
                        hopCount = advertisement.hopCount,
                        connectable = advertisement.isConnectable,
                    )
                }
            }
            is RepeatedProbeBleDevice -> {
                if(shouldUpdateFromAdvertisingPacket(device, advertisement.hopCount)) {
                    updateStateFromAdvertisement(
                        rssi = advertisement.rssi,
                        mode = advertisement.mode,
                        temperatures = advertisement.probeTemperatures,
                        sensors = advertisement.virtualSensors,
                        hopCount = advertisement.hopCount,
                        connectable = advertisement.isConnectable
                    )
                }
            }
        }
    }

    private suspend fun handleConnectionState(device: ProbeBleDeviceBase, state: DeviceConnectionState) {
        when(device) {
            is ProbeBleDevice -> {
                if(shouldUpdateOnConnectionState(device, device.hopCount, state)) {
                    _probe.value = _probe.value.copy(baseDevice = _probe.value.baseDevice.copy(connectionState = state))
                }
            }
            is RepeatedProbeBleDevice -> {
                if(shouldUpdateOnConnectionState(device, device.hopCount, state)) {
                    _probe.value = _probe.value.copy(baseDevice = _probe.value.baseDevice.copy(connectionState = state))
                }
            }
        }
    }

    private suspend fun handleOutOfRange(device: ProbeBleDeviceBase) {
        when(device) {
            is ProbeBleDevice -> {
                // TODO
            }
            is RepeatedProbeBleDevice -> {
                // TODO
            }
        }
    }

    private suspend fun handleRemoteRssi(device: ProbeBleDeviceBase, rssi: Int) {
        when(device) {
            is ProbeBleDevice -> {
                if(shouldUpdateOnRemoteRssi(device, device.hopCount, rssi)) {
                    _probe.value = _probe.value.copy(baseDevice = _probe.value.baseDevice.copy(rssi = rssi))
                }
            }
            is RepeatedProbeBleDevice -> {
                if(shouldUpdateOnRemoteRssi(device, device.hopCount, rssi)) {
                    _probe.value = _probe.value.copy(baseDevice = _probe.value.baseDevice.copy(rssi = rssi))
                }
            }
        }
    }

    private fun updateStateFromAdvertisement(
        rssi: Int, mode: ProbeMode, temperatures: ProbeTemperatures, sensors: ProbeVirtualSensors,
        hopCount: UInt, connectable: Boolean
    ) {
        val advertisingState = if(connectable) DeviceConnectionState.ADVERTISING_CONNECTABLE else DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE

        _probe.value = _probe.value.copy(
            baseDevice = _probe.value.baseDevice.copy(rssi = rssi, connectionState = advertisingState),
            hopCount = hopCount,
        )

        if(mode == ProbeMode.INSTANT_READ) {
            // TODO: Keep Track of Instant Read Activity
            _probe.value = _probe.value.copy(
                instantReadCelsius = temperatures.values[0]
            )
        } else if(mode == ProbeMode.NORMAL) {
            // TODO: Keep Track of Instant Read Stale/Idle/Timeout
            _probe.value = _probe.value.copy(
                temperaturesCelsius = temperatures,
                virtualSensors = sensors,
                coreTemperatureCelsius = when(sensors.virtualCoreSensor) {
                    ProbeVirtualSensors.VirtualCoreSensor.T1 -> temperatures.values[0]
                    ProbeVirtualSensors.VirtualCoreSensor.T2 -> temperatures.values[1]
                    ProbeVirtualSensors.VirtualCoreSensor.T3 -> temperatures.values[2]
                    ProbeVirtualSensors.VirtualCoreSensor.T4 -> temperatures.values[3]
                    ProbeVirtualSensors.VirtualCoreSensor.T5 -> temperatures.values[4]
                    ProbeVirtualSensors.VirtualCoreSensor.T6 -> temperatures.values[5]
                },
                surfaceTemperatureCelsius = when(sensors.virtualSurfaceSensor) {
                    ProbeVirtualSensors.VirtualSurfaceSensor.T4 -> temperatures.values[3]
                    ProbeVirtualSensors.VirtualSurfaceSensor.T5 -> temperatures.values[4]
                    ProbeVirtualSensors.VirtualSurfaceSensor.T6 -> temperatures.values[5]
                    ProbeVirtualSensors.VirtualSurfaceSensor.T7 -> temperatures.values[6]
                },
                ambientTemperatureCelsius = when(sensors.virtualAmbientSensor) {
                    ProbeVirtualSensors.VirtualAmbientSensor.T5 -> temperatures.values[4]
                    ProbeVirtualSensors.VirtualAmbientSensor.T6 -> temperatures.values[5]
                    ProbeVirtualSensors.VirtualAmbientSensor.T7 -> temperatures.values[6]
                    ProbeVirtualSensors.VirtualAmbientSensor.T8 -> temperatures.values[7]
                }
            )
        }
    }
}