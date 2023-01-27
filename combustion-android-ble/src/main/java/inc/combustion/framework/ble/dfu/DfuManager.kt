/*
 * Project: Combustion Inc. Android Framework
 * File: DfuManager.kt
 * Author:
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

package inc.combustion.framework.ble.dfu

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.device.DeviceID
import inc.combustion.framework.ble.device.DfuBleDevice
import inc.combustion.framework.ble.scanning.DeviceScanner
import inc.combustion.framework.service.dfu.DfuState
import inc.combustion.framework.service.dfu.DfuSystemState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

internal class DfuManager(
    private var owner: LifecycleOwner,
    private var context: Context,
    private var adapter: BluetoothAdapter, // TODO: Should this be nullable? Better to get from context?
    notificationTarget: Class<out Activity?>
) {

    private val devices = hashMapOf<DeviceID, DfuBleDevice>()
    private var scanningJob: Job? = null
    private val _initialized = AtomicBoolean(false)
    private val _enabled = AtomicBoolean(false)

    val enabled: Boolean
        get() = _enabled.get()

    val availableDevices = devices.keys

    fun dfuFlowForDevice(id: DeviceID) = devices[id]?.state

    private fun clearDevices() {
        // Release listener resources for each device
        devices.values.forEach {
            it.finish()
        }

        devices.clear()
        _systemState.tryEmit(DfuSystemState.DevicesCleared)
    }

    init {
        Log.i(LOG_TAG, "Initializing DFU manager")

        DfuService.notifyActivity = notificationTarget

        _initialized.set(true)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(): Boolean {
        Log.i(LOG_TAG, "Starting DFU manager")

        if (!_initialized.get()) {
            return false
        }

        _systemState.resetReplayCache()

        clearDevices()

        // Ensure that we're enabled so that discovery events are produced
        _enabled.set(true)

        collectAdvertisingData()

        return true
    }

    fun stop(): Boolean {
        Log.i(LOG_TAG, "Stopping DFU manager")
        if (!_initialized.get()) {
            return false
        }

        // Ensure that we're not enabled so that discovery events are no longer produced
        _enabled.set(false)

        // Stop scanning
        scanningJob?.cancel()

        clearDevices()

        return true
    }

    /**
     * Starts a DFU operation on the device associated with [id], returning the state flow to
     * monitor progress.
     *
     * This function will return null if DFU mode is not enabled.
     *
     * This flow can also be obtained by using [dfuFlowForDevice].
     */
    fun performDfu(id: DeviceID, file: Uri): StateFlow<DfuState>? {
        if (!enabled) {
            return null
        }

        devices[id]?.let {
            it.performDfu(file)
            return it.state
        }

        return null
    }

    private fun collectAdvertisingData() {
        scanningJob = owner.lifecycleScope.let {
            DeviceScanner.advertisements
                .onEach { advertisingData ->
                    if (enabled) {
                        val id = advertisingData.id

                        // Add the device to our list of devices if it doesn't exist yet.
                        if (!devices.containsKey(id)) {
                            Log.i(LOG_TAG, "DFU manager encountered new device $id")

                            // This needs to happen before the DeviceDiscovered event is emitted.
                            devices[id] = DfuBleDevice(context, owner, adapter, advertisingData)

                            // Tell clients that the device was discovered.
                            _systemState.tryEmit(DfuSystemState.DeviceDiscovered(id))
                        }
                    }
                }
                .launchIn(it)
        }
    }

    companion object {
        private const val FLOW_CONFIG_REPLAY = 5
        private const val FLOW_CONFIG_BUFFER = FLOW_CONFIG_REPLAY * 2

        private val _systemState = MutableSharedFlow<DfuSystemState>(
            replay = FLOW_CONFIG_REPLAY,
            extraBufferCapacity = FLOW_CONFIG_BUFFER
        )
        val SYSTEM_STATE_FLOW = _systemState.asSharedFlow()
    }
}