/*
 * Project: Combustion Inc. Android Framework
 * File: DfuBleDevice.kt
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

package inc.combustion.framework.ble.device

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.dfu.PerformDfuDelegate
import inc.combustion.framework.ble.scanning.DeviceAdvertisingData
import inc.combustion.framework.service.DeviceConnectionState
import inc.combustion.framework.service.dfu.DfuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import no.nordicsemi.android.dfu.DfuLogListener
import no.nordicsemi.android.dfu.DfuProgressListener
import no.nordicsemi.android.dfu.DfuServiceListenerHelper

internal class DfuBleDevice(
    val performDfuDelegate: PerformDfuDelegate,
    private val context: Context,
    owner: LifecycleOwner,
    adapter: BluetoothAdapter,
    advertisingData: DeviceAdvertisingData,
) : DeviceInformationBleDevice(advertisingData.mac, advertisingData, owner, adapter),
    DfuProgressListener,
    DfuLogListener {

    constructor(
        context: Context,
        owner: LifecycleOwner,
        adapter: BluetoothAdapter,
        advertisingData: DeviceAdvertisingData,
    ) : this(
        performDfuDelegate = PerformDfuDelegate(context, advertisingData, advertisingData.mac),
        context = context,
        owner = owner, adapter = adapter, advertisingData = advertisingData,
    ) {
        // CombustionAdvertisingData's productType might be more reliable than previous value
        performDfuDelegate.emitState(
            state.value.copy(
                device = state.value.device.copy(
                    productType = advertisingData.productType,
                ),
            )
        )
    }

    val state = performDfuDelegate.state

    // Flag to indicate whether we should transition to a blocked state after connecting
    // Needed to handle new connections after a successful DFU and the device is rebooted
    private var blockAfterConnect: Boolean = false

    private fun onConnectionStateChange(connectionState: DeviceConnectionState) {
        when (connectionState) {
            DeviceConnectionState.ADVERTISING_CONNECTABLE, DeviceConnectionState.CONNECTING -> {
                performDfuDelegate.emitState(
                    DfuState.NotReady(
                        state.value.device.copy(connectionState = connectionState),
                        DfuState.NotReady.Status.CONNECTING
                    )
                )
            }

            DeviceConnectionState.CONNECTED -> {
                owner.lifecycleScope.launch(Dispatchers.IO) {
                    readSerialNumber()
                    readFirmwareVersion()
                    readHardwareRevision()
                    readModelInformation()

                    Log.i(LOG_TAG, "DFU read device service: ${state.value}")
                }.invokeOnCompletion {

                    // If we're blocked, we don't want to transition to idle.

                    if (blockAfterConnect) {
                        performDfuDelegate.emitState(
                            DfuState.NotReady(
                                state.value.device.copy(
                                    serialNumber = serialNumber ?: "",
                                    fwVersion = firmwareVersion,
                                    hwRevision = hardwareRevision,
                                    modelInformation = modelInformation,
                                    connectionState = connectionState,
                                ),
                                DfuState.NotReady.Status.BLOCKED
                            )
                        )
                    } else {
                        performDfuDelegate.emitState(
                            DfuState.Idle(
                                state.value.device.copy(
                                    serialNumber = serialNumber ?: "",
                                    fwVersion = firmwareVersion,
                                    hwRevision = hardwareRevision,
                                    modelInformation = modelInformation,
                                    connectionState = connectionState,
                                )
                            )
                        )
                    }

                    Log.i(LOG_TAG, "Connected DFU handler finished (state: ${state.value})")
                }

                performDfuDelegate.emitState(
                    DfuState.NotReady(
                        state.value.device.copy(connectionState = connectionState),
                        DfuState.NotReady.Status.READING_DEVICE_INFORMATION
                    )
                )
            }

            DeviceConnectionState.DISCONNECTING,
            DeviceConnectionState.DISCONNECTED -> {
                // If we get a spontaneous disconnect while not in DFU mode, invalidate ourselves.
                // Otherwise, if DFU is in progress, eventing up to clients is handled by the DFU
                // progress listener callbacks.
                // TODO: Better to not show this device at all on spontaneous disconnect?
                if (performDfuDelegate.internalDfuState.status == PerformDfuDelegate.InternalDfuState.Status.IDLE) {
                    serialNumber = null
                    firmwareVersion = null
                    hardwareRevision = null

                    performDfuDelegate.emitState(
                        DfuState.NotReady(
                            state.value.device.copy(
                                serialNumber = "",
                                fwVersion = null,
                                hwRevision = null,
                                connectionState = connectionState,
                            ),
                            DfuState.NotReady.Status.DISCONNECTED
                        )
                    )
                }
            }

            DeviceConnectionState.NO_ROUTE,
            DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE,
            DeviceConnectionState.OUT_OF_RANGE -> {
                performDfuDelegate.emitState(
                    DfuState.NotReady(
                        state.value.device.copy(
                            serialNumber = "",
                            fwVersion = null,
                            hwRevision = null,
                            connectionState = connectionState,
                        ),
                        DfuState.NotReady.Status.DISCONNECTED,
                    )
                )
            }
        }

        Log.i(LOG_TAG, "Connection state transition: ${state.value}")
    }

    init {
        Log.i(LOG_TAG, "INITIALIZING DFU BLE DEVICE")

        observeConnectionState {
            // If we're in the middle of a DFU operation, let the Nordic library handle connection
            // state changes.
            Log.v(
                LOG_TAG,
                "Connection state observation: $it, status: ${performDfuDelegate.internalDfuState.status}"
            )
            onConnectionStateChange(it)
        }

        observeOutOfRange(5000L) {
            if (performDfuDelegate.internalDfuState.status == PerformDfuDelegate.InternalDfuState.Status.IDLE) {
                performDfuDelegate.emitState(
                    DfuState.NotReady(
                        state.value.device.copy(), DfuState.NotReady.Status.OUT_OF_RANGE
                    )
                )
            }
        }

        observeRemoteRssi {
            performDfuDelegate.emitState(state.value.copy(state.value.device.copy(rssi = it)))
        }

        // `true` doesn't filter any advertising packets.
        observeAdvertisingPackets(serialNumber, { true }) { _ ->
            if (performDfuDelegate.internalDfuState.status != PerformDfuDelegate.InternalDfuState.Status.IN_PROGRESS
                && !(connectionState == DeviceConnectionState.CONNECTING
                        || connectionState == DeviceConnectionState.CONNECTED)
            ) {
                Log.i(
                    LOG_TAG,
                    "Attempting to connect after DFU!!!! ${performDfuDelegate.internalDfuState} ${isDisconnected.get()} $connectionState"
                )

                // Make sure we don't try to connect again
                performDfuDelegate.resetInternalState()

                // We very regularly get a ConnectionLostException from the Kable peripheral when we
                // try to connect after the DFU process is complete (generally during service
                // discovery). I suspect this is because the device is advertising before it's ready
                // for a connection, but waiting seems a little hacky.
                //
                // We occasionally see spurious exceptions of this type during normal operation as
                // well, so it could be chance. In any case, keep trying to connect if the first try
                // fails. I've never seen the second attempt fail.
                connect()
            }
        }

        DfuServiceListenerHelper.registerProgressListener(
            context, this, state.value.device.mac
        )
        DfuServiceListenerHelper.registerLogListener(
            context, this, state.value.device.mac
        )

        connect()
    }

    override fun finish(jobKey: String?, disconnect: Boolean) {
        DfuServiceListenerHelper.unregisterProgressListener(context, this)

        // Only disconnect if there's not a DFU operation in progress.
        super.finish(
            jobKey,
            performDfuDelegate.internalDfuState.status == PerformDfuDelegate.InternalDfuState.Status.IDLE
        )
    }

    fun performDfu(file: Uri, completionHandler: (Boolean) -> Unit) {
        performDfuDelegate.performDfu(file, completionHandler)
    }

    fun abortDfu() {
        performDfuDelegate.abortDfu()
    }

    override fun onDeviceConnecting(deviceAddress: String) {
        performDfuDelegate.onDeviceDisconnecting(deviceAddress)
    }

    override fun onDeviceConnected(deviceAddress: String) {
        performDfuDelegate.onDeviceConnected(deviceAddress)
    }

    override fun onDfuProcessStarting(deviceAddress: String) {
        performDfuDelegate.onDfuProcessStarting(deviceAddress)
    }

    override fun onDfuProcessStarted(deviceAddress: String) {
        performDfuDelegate.onDfuProcessStarted(deviceAddress)
    }

    override fun onEnablingDfuMode(deviceAddress: String) {
        performDfuDelegate.onEnablingDfuMode(deviceAddress)
    }

    override fun onFirmwareValidating(deviceAddress: String) {
        performDfuDelegate.onFirmwareValidating(deviceAddress)
    }

    override fun onProgressChanged(
        deviceAddress: String,
        percent: Int,
        speed: Float,
        avgSpeed: Float,
        currentPart: Int,
        partsTotal: Int,
    ) {
        performDfuDelegate.onProgressChanged(
            deviceAddress = deviceAddress,
            percent = percent,
            speed = speed,
            avgSpeed = avgSpeed,
            currentPart = currentPart,
            partsTotal = partsTotal,
        )
    }

    override fun onDeviceDisconnecting(deviceAddress: String?) {
        performDfuDelegate.onDeviceDisconnecting(deviceAddress)
    }

    override fun onDeviceDisconnected(deviceAddress: String) {
        performDfuDelegate.onDeviceDisconnected(deviceAddress)
    }

    override fun onDfuCompleted(deviceAddress: String) {
        performDfuDelegate.onDfuCompleted(deviceAddress)
    }

    override fun onDfuAborted(deviceAddress: String) {
        performDfuDelegate.onDfuAborted(deviceAddress)
    }

    override fun onError(
        deviceAddress: String,
        error: Int,
        errorType: Int,
        message: String?
    ) {
        performDfuDelegate.onError(
            deviceAddress = deviceAddress,
            error = error,
            errorType = errorType,
            message = message,
        )
    }

    override fun onLogEvent(deviceAddress: String?, level: Int, message: String?) {
        message?.let {
            performDfuDelegate.onLogEvent(
                level = level,
                message = message,
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            performDfuDelegate.emitState(
                DfuState.Idle(
                    state.value.device.copy(
                        serialNumber = serialNumber ?: "",
                        fwVersion = firmwareVersion,
                        hwRevision = hardwareRevision,
                        modelInformation = modelInformation,
                        connectionState = connectionState,
                    )
                )
            )
        } else {
            performDfuDelegate.emitState(
                DfuState.NotReady(
                    state.value.device,
                    DfuState.NotReady.Status.BLOCKED,
                )
            )
        }

        // Blocking after connect is the inverse of the enabled state
        // (i.e. if we're enabled, we don't want to block after connect)
        blockAfterConnect = !enabled
    }
}