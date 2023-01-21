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
import androidx.lifecycle.lifecycleScope
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.device.ProbeBleDevice
import inc.combustion.framework.ble.device.RepeatedProbeBleDevice
import inc.combustion.framework.ble.uart.LogResponse
import inc.combustion.framework.service.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

internal class ProbeManager(
    serialNumber: String,
    private val owner: LifecycleOwner,
    private val settings: DeviceManager.Settings
) {
    // direct ble link to probe
    private var probeBleDevice: ProbeBleDevice? = null
    private val repeatedProbeBleDevices = mutableListOf<RepeatedProbeBleDevice>()

    // manages long-running coroutine scopes for data handling
    private val jobManager = JobManager()

    private var probeStatusCollectJob: Job? = null

    // holds the current state and data for this probe
    private var _probe: Probe = Probe.create(serialNumber = serialNumber)

    // the flow that produce a state and data updates for this probe from meatnet
    private val _probeFlow = MutableSharedFlow<Probe>(
        replay = 0, extraBufferCapacity = 10, BufferOverflow.DROP_OLDEST)

    // the flow that is consumed to get state and date updates
    val probeFlow = _probeFlow.asSharedFlow()

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
            return _probe.serialNumber
        }

    // current upload state for this probe, determined by LogManager
    var uploadState: ProbeUploadState
        get() {
            return _probe.uploadState
        }
        set(value) {
            if(value != _probe.uploadState) {
                _probe = _probe.copy(uploadState = value)
               emit()
            }
        }

    val probe: Probe
        get() {
            return _probe
        }

    // current session information for the probe
    var sessionInfo: SessionInformation? = null
        private set

    // current minimum sequence number for the probe
    val minSequenceNumber: UInt
        get() {
            return _probe.minSequenceNumber
        }

    // current maximum sequence number for the probe
    val maxSequenceNumber: UInt
        get() {
            return _probe.maxSequenceNumber
        }

    fun addJob(job: Job) = jobManager.addJob(job)

    fun addProbe(probe: ProbeBleDevice) {
        if(probeBleDevice != null) {
            probeBleDevice = probe

            Log.i(LOG_TAG, "PM($serialNumber) is managing link ${probe.linkId}")
        }
    }

    fun addRepeatedProbe(repeatedProbe: RepeatedProbeBleDevice) {
        repeatedProbeBleDevices.add(repeatedProbe)
        Log.i(LOG_TAG, "PM($serialNumber) is managing link ${repeatedProbe.linkId}")
    }

    fun connect() {
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

    // TODO: Need to Read Session Information Upon Connection To Probe

    private fun collectProbeStatus() {
        /*
        probeStatusCollectJob?.cancel()
        probeStatusCollectJob = owner.lifecycleScope.launch {
            val flowList = mutableListOf<Flow<ProbeStatus>>()

            probeBleDevice?.let {
                flowList.add(it.probeStatusFlow)
            }

            repeatedProbeBleDevices.forEach {
                flowList.add(it.probeStatusFlow)
            }

            merge(*flowList.map{ it }.toTypedArray()).collect {
            }
            probeBleDevice?.probeStatusFlow?.collect {

            }
        }
         */
    }

    private fun emit() {
        _probeFlow.tryEmit(_probe)
    }
}