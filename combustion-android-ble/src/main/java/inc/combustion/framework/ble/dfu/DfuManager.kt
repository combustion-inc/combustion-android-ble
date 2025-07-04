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
import inc.combustion.framework.analytics.AnalyticsTracker
import inc.combustion.framework.analytics.ExceptionEvent
import inc.combustion.framework.ble.device.BootLoaderDevice
import inc.combustion.framework.ble.device.DeviceID
import inc.combustion.framework.ble.device.DfuBleDevice
import inc.combustion.framework.ble.device.standardId
import inc.combustion.framework.ble.scanning.DeviceScanner
import inc.combustion.framework.service.dfu.DfuProductType
import inc.combustion.framework.service.dfu.DfuState
import inc.combustion.framework.service.dfu.DfuSystemState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private const val BOOTLOADER_STUCK_TIMEOUT_MILLIS = 10000L

internal class DfuManager(
    private var owner: LifecycleOwner,
    private var context: Context,
    private var adapter: BluetoothAdapter, // TODO: Should this be nullable? Better to get from context?
    private val notificationTarget: Class<out Activity?>?,
    private val latestFirmware: Map<DfuProductType, Uri>,
    private val analyticsTracker: AnalyticsTracker = AnalyticsTracker.instance,
) {
    private val devices = hashMapOf<DeviceID, DfuBleDevice>()
    private var scanningJob: Job? = null

    @Volatile
    private var activeRetryDfuContext: RetryDfuContext? = null
    private val bootLoaderDevices = mutableMapOf<DeviceID, BootLoaderDevice>()
    private val retryDfuHistory = mutableMapOf<DeviceID, RetryDfuContext>()
    private var checkStuckDfuJob: Job? = null

    private val _initialized = AtomicBoolean(false)
    private val _enabled = AtomicBoolean(false)

    // tracks when a dfu is in progress or not
    private val dfuInProgress = AtomicBoolean(false)

    val enabled: Boolean
        get() = _enabled.get()

    val availableDevices = devices.keys

    fun dfuFlowForDevice(id: DeviceID) = devices[id]?.state

    private fun clearDevices() {
        // Release listener resources for each device
        devices.values.forEach {
            it.finish()
        }

        // reset _dfuInProgress
        dfuInProgress.set(false)

        devices.clear()
        _systemState.tryEmit(DfuSystemState.DevicesCleared)
    }

    init {
        Log.i(LOG_TAG, "Initializing DFU manager")

        _initialized.set(true)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(): Boolean {
        Log.i(LOG_TAG, "Starting DFU manager")
        if (!_initialized.get()) {
            return false
        }

        if (notificationTarget != null) {
            DfuService.notifyActivity = notificationTarget
        } else {
            analyticsTracker.trackExceptionEvent(
                ExceptionEvent.NonFatalException(
                    IllegalStateException("DfuManager.notificationTarget should not be null")
                )
            )
        }

        _systemState.resetReplayCache()

        clearDevices()

        // Ensure that we're enabled so that discovery events are produced
        _enabled.set(true)

        collectAdvertisingData()
        collectBootloaderDevices()

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
        scanningJob = null
        checkStuckDfuJob?.cancel()
        checkStuckDfuJob = null

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
        if (!DfuService.isNotifyActivitySet()) {
            throw NotImplementedError("You must provide a Notification Target when initializing Combustion Service to use DFU")
        }

        if (!enabled) {
            return null
        }

        devices[id]?.let { currentDevice ->

            // set dfu in progress to true
            dfuInProgress.set(true)

            analyticsTracker.trackStartDfu(
                productType = currentDevice.state.value.device.dfuProductType,
                serialNumber = currentDevice.state.value.device.serialNumber,
            )

            currentDevice.performDfu(file) {
                // Re-enable all devices when DFU is complete
                devices.forEach { (_, device) ->
                    device.setEnabled(true)
                }

                // set dfu in progress to false
                dfuInProgress.set(false)

                if (activeRetryDfuContext?.standardId == id) {
                    activeRetryDfuContext = null
                }
            }

            // Disable all other devices while DFU is in progress
            devices.filter { it.key != id }.forEach { (_, device) ->
                device.setEnabled(false)
            }

            return currentDevice.state
        }

        return null
    }

    private fun collectAdvertisingData() {
        scanningJob = owner.lifecycleScope.let {
            DeviceScanner.advertisements
                .onEach { advertisingData ->
                    if (enabled) {
                        val id = advertisingData.id

                        // if device is visible here then it exited bootLoading and can be removed
                        val matchingBootloaderDevice = bootLoaderDevices.remove(id)?.also {
                            it.finish()
                        }

                        // Add the device to our list of devices if it doesn't exist yet.
                        if (!devices.containsKey(id)) {
                            Log.i(LOG_TAG, "DFU manager encountered new device $id")

                            // This needs to happen before the DeviceDiscovered event is emitted.
                            devices[id] =
                                matchingBootloaderDevice?.performDfuDelegate?.let { performDfuDelegate ->
                                    // reuse performDfuDelegate since probably retried dfu in progress on previously stuck bootLoader device
                                    DfuBleDevice(
                                        performDfuDelegate,
                                        context,
                                        owner,
                                        adapter,
                                        advertisingData,
                                    )
                                } ?: DfuBleDevice(context, owner, adapter, advertisingData)

                            // If a DFU is in progress, disable the device.
                            if (dfuInProgress.get()) {
                                Log.i(LOG_TAG, "DFU in progress, disabling device $id")
                                devices[id]?.setEnabled(false)
                            }

                            // Tell clients that the device was discovered.
                            _systemState.tryEmit(DfuSystemState.DeviceDiscovered(id))
                        }
                    }
                }
                .launchIn(it)
        }
    }

    private fun collectBootloaderDevices() {
        checkStuckDfuJob = owner.lifecycleScope.launch {
            DeviceScanner.bootloadingAdvertisements.collect { advertisingData ->
                val standardId = advertisingData.standardId()
                val existingBootloaderDevice = bootLoaderDevices[standardId]
                val matchingDevice = devices[standardId]
                val bootLoaderDevice: BootLoaderDevice = when {
                    existingBootloaderDevice == null -> {
                        (matchingDevice?.performDfuDelegate?.let { performDfuDelegate ->
                            BootLoaderDevice(
                                performDfuDelegate = performDfuDelegate,
                                advertisingData = advertisingData,
                                context = context,
                            )
                        } ?: BootLoaderDevice(
                            context = context,
                            advertisingData = advertisingData,
                        )).also {
                            Log.i(LOG_TAG, "DFU manager encountered new bootloader device $it")
                            bootLoaderDevices[standardId] = it
                        }
                    }

                    else -> existingBootloaderDevice
                }

                // check if bootloader is stuck
                if ((activeRetryDfuContext == null) && (bootLoaderDevice.millisSinceFirstSeen() >= BOOTLOADER_STUCK_TIMEOUT_MILLIS)) {
                    handleStuckBootloader(bootLoaderDevice, matchingDevice)
                }
            }
        }
    }

    private fun handleStuckBootloader(
        bootLoaderDevice: BootLoaderDevice,
        matchingDevice: DfuBleDevice?,
    ) {
        if (!DfuService.isNotifyActivitySet()) {
            throw NotImplementedError("You must provide a Notification Target when initializing Combustion Service to use DFU")
        }

        val standardId = bootLoaderDevice.standardId
        val retryDfuContext = retryDfuHistory[standardId]?.let {
            val count = it.count + 1
            it.copy(
                count = count,
                dfuProductType = bootLoaderDevice.retryProductType(count),
            )
        } ?: RetryDfuContext(
            standardId = bootLoaderDevice.standardId,
            dfuProductType = bootLoaderDevice.dfuProductType,
        )
        latestFirmware[retryDfuContext.dfuProductType]?.let { firmwareFile ->
            Log.i(
                LOG_TAG,
                "detected bootloader device $bootLoaderDevice is stuck in dfu - " +
                        "retry with context $retryDfuContext and firmware $firmwareFile",
            )
            activeRetryDfuContext = retryDfuContext
            retryDfuHistory[standardId] = retryDfuContext

            analyticsTracker.trackRetryDfu(
                productType = retryDfuContext.dfuProductType,
                serialNumber = bootLoaderDevice.performDfuDelegate.state.value.device.serialNumber,
            )

            val matchingDeviceCallback =
                matchingDevice?.performDfuDelegate?.dfuCompleteCallback
            bootLoaderDevice.performDfu(firmwareFile) { success ->
                activeRetryDfuContext = null
                if (success) {
                    bootLoaderDevices.remove(bootLoaderDevice.standardId)?.also {
                        it.finish()
                    }
                }
                matchingDeviceCallback?.invoke(success)
                Log.i(
                    LOG_TAG,
                    "dfu retry completed with success = $success for bootloader device $bootLoaderDevice with context $retryDfuContext",
                )
            }
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

    private data class RetryDfuContext(
        val standardId: String,
        val dfuProductType: DfuProductType,
        val count: Int = 1,
    )
}