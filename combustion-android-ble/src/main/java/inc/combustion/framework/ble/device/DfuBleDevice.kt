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
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.dfu.DfuService
import inc.combustion.framework.ble.scanning.CombustionAdvertisingData
import inc.combustion.framework.service.Device
import inc.combustion.framework.service.DeviceConnectionState
import inc.combustion.framework.service.dfu.DfuProgress
import inc.combustion.framework.service.dfu.DfuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.nordicsemi.android.dfu.*
import no.nordicsemi.android.error.SecureDfuError

internal class DfuBleDevice(
    private val context: Context,
    owner: LifecycleOwner,
    adapter: BluetoothAdapter,
    advertisingData: CombustionAdvertisingData,
) : DeviceInformationBleDevice(advertisingData.mac, advertisingData, owner, adapter),
    DfuProgressListener,
    DfuLogListener
{
    private val _state = MutableStateFlow<DfuState>(
        DfuState.NotReady(
            device = Device(
                mac = advertisingData.mac,
                rssi = advertisingData.rssi,
                productType = advertisingData.productType
            ),
            DfuState.NotReady.Status.DISCONNECTED
        )
    )
    val state = _state.asStateFlow()

    /**
     * Holds bookkeeping state internal to the device. [status] indicates where we are in the DFU
     * process. [disconnectingEventCount] keeps track of the number of times [onDeviceDisconnecting]
     * has been called. We keep track of this to differentiate between when we're disconnecting to
     * boot into the bootloader at the beginning of the process or when we're disconnecting out of
     * the bootloader and into the app at the end.
     */
    private data class InternalDfuState(
        var status: Status = Status.IDLE,
        var disconnectingEventCount: Int = 0
    ) {
        enum class Status {
            IDLE, // A DFU operation has not been initiated.
            IN_PROGRESS, // A DFU operation is in progress; the Nordic library owns the connection.
            COMPLETE, // The DFU operation completed and the framework should manage the connection.
        }
    }

    private var internalDfuState = InternalDfuState()

    private var dfuServiceController: DfuServiceController? = null

    private fun onConnectionStateChange(connectionState: DeviceConnectionState) {
        when (connectionState) {
            DeviceConnectionState.ADVERTISING_CONNECTABLE, DeviceConnectionState.CONNECTING -> {
                _state.value = DfuState.NotReady(
                    state.value.device.copy(connectionState = connectionState),
                    DfuState.NotReady.Status.CONNECTING
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
                    _state.value = DfuState.Idle(
                        state.value.device.copy(
                            serialNumber = serialNumber ?: "",
                            fwVersion = firmwareVersion,
                            hwRevision = hardwareRevision,
                            modelInformation = modelInformation,
                            connectionState = connectionState,
                        )
                    )

                    Log.i(LOG_TAG, "Connected DFU handler finished (state: ${_state.value})")
                }

                _state.value = DfuState.NotReady(
                    state.value.device.copy(connectionState = connectionState),
                    DfuState.NotReady.Status.READING_DEVICE_INFORMATION
                )
            }
            DeviceConnectionState.DISCONNECTING,
            DeviceConnectionState.DISCONNECTED -> {
                // If we get a spontaneous disconnect while not in DFU mode, invalidate ourselves.
                // Otherwise, if DFU is in progress, eventing up to clients is handled by the DFU
                // progress listener callbacks.
                // TODO: Better to not show this device at all on spontaneous disconnect?
                if (internalDfuState.status == InternalDfuState.Status.IDLE) {
                    serialNumber = null
                    firmwareVersion = null
                    hardwareRevision = null

                    _state.value = DfuState.NotReady(
                        state.value.device.copy(
                            serialNumber = "",
                            fwVersion = null,
                            hwRevision = null,
                            connectionState = connectionState,
                        ),
                        DfuState.NotReady.Status.DISCONNECTED
                    )
                }
            }
            DeviceConnectionState.NO_ROUTE,
            DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE,
            DeviceConnectionState.OUT_OF_RANGE -> {
                _state.value = DfuState.NotReady(
                    state.value.device.copy(
                        serialNumber = "",
                        fwVersion = null,
                        hwRevision = null,
                        connectionState = connectionState,
                    ),
                    DfuState.NotReady.Status.DISCONNECTED
                )
            }
        }

        Log.i(LOG_TAG, "Connection state transition: ${state.value}")
    }

    init {
        Log.e(LOG_TAG, "INITIALIZING DFU BLE DEVICE")
        observeConnectionState {
            // If we're in the middle of a DFU operation, let the Nordic library handle connection
            // state changes.
            Log.v(LOG_TAG, "Connection state observation: $it, status: ${internalDfuState.status}")
            onConnectionStateChange(it)
        }

        observeOutOfRange(5000L) {
            if (internalDfuState.status == InternalDfuState.Status.IDLE) {
                _state.value = DfuState.NotReady(
                    state.value.device.copy(), DfuState.NotReady.Status.OUT_OF_RANGE
                )
            }
        }

        observeRemoteRssi {
            _state.value = _state.value.copy(_state.value.device.copy(rssi = it))
        }

        // `true` doesn't filter any advertising packets.
        observeAdvertisingPackets({ true }) {
            if (internalDfuState.status != InternalDfuState.Status.IN_PROGRESS
                && !(connectionState == DeviceConnectionState.CONNECTING
                        || connectionState == DeviceConnectionState.CONNECTED)
            ) {
                Log.i(
                    LOG_TAG,
                    "Attempting to connect after DFU!!!! $internalDfuState ${isDisconnected.get()} $connectionState"
                )

                // Make sure we don't try to connect again
                internalDfuState = InternalDfuState()

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
            context, this, _state.value.device.mac
        )
        DfuServiceListenerHelper.registerLogListener(
            context, this, _state.value.device.mac
        )

        connect()
    }

    override fun finish(disconnect: Boolean) {
        DfuServiceListenerHelper.unregisterProgressListener(context, this)

        // Only disconnect if there's not a DFU operation in progress.
        super.finish(internalDfuState.status == InternalDfuState.Status.IDLE)
    }

    fun performDfu(file: Uri) {
        Log.i(LOG_TAG, "Performing DFU for device ${_state.value.device} with file $file")

        // Reset our internal state.
        internalDfuState = InternalDfuState(status = InternalDfuState.Status.IN_PROGRESS)

        _state.value = DfuState.InProgress(
            _state.value.device,
            DfuProgress.Initializing(DfuProgress.Initializing.Status.INITIALIZING)
        )

        val starter = DfuServiceInitiator(_state.value.device.mac).apply {
            setDeviceName(serialNumber)

            setKeepBond(false)
            setForceDfu(false)

            setForceScanningForNewAddressInLegacyDfu(false)
            setPrepareDataObjectDelay(400)
            setRebootTime(3000)
            setScanTimeout(4000)
            setUnsafeExperimentalButtonlessServiceInSecureDfuEnabled(true)

            setPacketsReceiptNotificationsEnabled(true)
            setPacketsReceiptNotificationsValue(12)
        }

        starter.setZip(file, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            DfuServiceInitiator.createDfuNotificationChannel(context)
        }

        dfuServiceController = starter.start(context, DfuService::class.java)
    }

    fun abortDfu() {
        dfuServiceController?.abort() ?: Log.e(LOG_TAG, "Unable to abort DFU process.")
    }

    override fun onDeviceConnecting(deviceAddress: String) {
        _state.value = DfuState.InProgress(
            _state.value.device,
            DfuProgress.Initializing(DfuProgress.Initializing.Status.CONNECTING_TO_DEVICE)
        )

        Log.i(LOG_TAG, "[onDeviceConnecting] $deviceAddress ${_state.value}")
    }

    override fun onDeviceConnected(deviceAddress: String) {
        _state.value = DfuState.InProgress(
            _state.value.device,
            DfuProgress.Initializing(DfuProgress.Initializing.Status.CONNECTED_TO_DEVICE)
        )

        Log.i(LOG_TAG, "[onDeviceConnected] $deviceAddress ${_state.value}")
    }

    override fun onDfuProcessStarting(deviceAddress: String) {
        _state.value = DfuState.InProgress(
            _state.value.device,
            DfuProgress.Initializing(DfuProgress.Initializing.Status.STARTING_DFU_PROCESS)
        )

        Log.i(LOG_TAG, "[onDfuProcessStarting] $deviceAddress ${_state.value}")
    }

    override fun onDfuProcessStarted(deviceAddress: String) {
        _state.value = DfuState.InProgress(
            _state.value.device,
            DfuProgress.Initializing(DfuProgress.Initializing.Status.STARTED_DFU_PROCESS)
        )

        Log.i(LOG_TAG, "[onDfuProcessStarted] $deviceAddress ${_state.value}")
    }

    override fun onEnablingDfuMode(deviceAddress: String) {
        // The command to enter the bootloader is sent immediately after this; the next event
        // encountered is onDeviceDisconnecting.
        _state.value = DfuState.InProgress(
            _state.value.device,
            DfuProgress.Initializing(DfuProgress.Initializing.Status.ENABLING_DFU_MODE)
        )

        Log.i(LOG_TAG, "[onEnablingDfuMode] $deviceAddress ${_state.value}")
    }

    override fun onFirmwareValidating(deviceAddress: String) {
        _state.value = DfuState.InProgress(
            _state.value.device,
            DfuProgress.Initializing(DfuProgress.Initializing.Status.VALIDATING_FIRMWARE)
        )

        Log.i(LOG_TAG, "[onFirmwareValidating] $deviceAddress ${_state.value}")
    }

    override fun onProgressChanged(
        deviceAddress: String,
        percent: Int,
        speed: Float,
        avgSpeed: Float,
        currentPart: Int,
        partsTotal: Int
    ) {
        _state.value = DfuState.InProgress(
            _state.value.device,
            DfuProgress.Uploading(percent, avgSpeed, currentPart, partsTotal)
        )

        Log.i(LOG_TAG, "[onProgressChanged] $deviceAddress : ${_state.value}")
    }

    override fun onDeviceDisconnecting(deviceAddress: String?) {
        // This is reached when rebooting into the bootloader--need to have it not be finishing until
        // we've started
        val progress =
            if (internalDfuState.disconnectingEventCount++ == 0)
                DfuProgress.Initializing(DfuProgress.Initializing.Status.ENTERING_BOOTLOADER)
            else
                DfuProgress.Finishing(DfuProgress.Finishing.Status.DISCONNECTING_FROM_DEVICE)

        deviceAddress?.let {
            _state.value = DfuState.InProgress(
                _state.value.device,
                progress
            )
        }

        Log.i(LOG_TAG, "[onDeviceDisconnecting] $deviceAddress ${_state.value}")
    }

    override fun onDeviceDisconnected(deviceAddress: String) {
        // This isn't reached when rebooting into the bootloader
        _state.value = DfuState.InProgress(
            _state.value.device,
            DfuProgress.Finishing(DfuProgress.Finishing.Status.DISCONNECTED_FROM_DEVICE)
        )

        Log.i(LOG_TAG, "[onDeviceDisconnected] $deviceAddress ${_state.value}")
    }

    override fun onDfuCompleted(deviceAddress: String) {
        _state.value = DfuState.InProgress(
            _state.value.device,
            DfuProgress.Finishing(DfuProgress.Finishing.Status.COMPLETE_RESTARTING_DEVICE)
        )

        Log.i(LOG_TAG, "[onDfuCompleted] $deviceAddress ${_state.value}")

        internalDfuState.status = InternalDfuState.Status.COMPLETE
    }

    override fun onDfuAborted(deviceAddress: String) {
        _state.value = DfuState.InProgress(_state.value.device, DfuProgress.Aborted)

        Log.i(LOG_TAG, "[onDfuAborted] $deviceAddress ${_state.value}")
    }

    override fun onError(
        deviceAddress: String,
        error: Int,
        errorType: Int,
        message: String?
    ) {
        val type = when (error) {
            DfuBaseService.ERROR_TYPE_COMMUNICATION_STATE -> DfuProgress.Error.ErrorType.COMMUNICATION_STATE
            DfuBaseService.ERROR_TYPE_COMMUNICATION -> DfuProgress.Error.ErrorType.COMMUNICATION
            DfuBaseService.ERROR_TYPE_DFU_REMOTE -> DfuProgress.Error.ErrorType.DFU_OPERATION
            else -> DfuProgress.Error.ErrorType.OTHER
        }

        // These (English) descriptions are taken from Nordic's DFU example. This implementation
        // will need to be addressed during i18n.
        val betterMessage = when (error) {
            DfuBaseService.ERROR_DEVICE_DISCONNECTED -> "Device has disconnected."
            DfuBaseService.ERROR_FILE_ERROR -> "Invalid or too large file."
            DfuBaseService.ERROR_FILE_INVALID -> "Unsupported file."
            DfuBaseService.ERROR_FILE_TYPE_UNSUPPORTED -> "The file type is not supported."
            DfuBaseService.ERROR_SERVICE_NOT_FOUND -> "The device does not support nRF5 DFU"
            DfuBaseService.ERROR_BLUETOOTH_DISABLED -> "Bluetooth is disabled."
            DfuBaseService.ERROR_DEVICE_NOT_BONDED -> "The device is not bonded."
            DfuBaseService.ERROR_INIT_PACKET_REQUIRED -> "The init packet is required."

            // Secure DFU errors
            DfuBaseService.ERROR_REMOTE_TYPE_SECURE or SecureDfuError.INVALID_OBJECT ->
                "Selected firmware is not compatible with the device."
            DfuBaseService.ERROR_REMOTE_TYPE_SECURE or SecureDfuError.INSUFFICIENT_RESOURCES ->
                "Insufficient resources."

            else -> message
        } ?: "Unknown error"

        _state.value = DfuState.InProgress(
            _state.value.device, DfuProgress.Error(type, betterMessage)
        )

        Log.e(LOG_TAG, "[onError] $error (type: $errorType, message: $message ${_state.value}")
    }

    override fun onLogEvent(deviceAddress: String?, level: Int, message: String?) {
        message?.let {
            val s = "[onLogEvent] $it (${_state.value}])"
            when (level) {
                DfuBaseService.LOG_LEVEL_VERBOSE -> {
                    Log.v(LOG_TAG, s)
                }
                DfuBaseService.LOG_LEVEL_DEBUG -> {
                    Log.d(LOG_TAG, s)
                }
                DfuBaseService.LOG_LEVEL_INFO, DfuBaseService.LOG_LEVEL_APPLICATION -> {
                    Log.i(LOG_TAG, s)
                }
                DfuBaseService.LOG_LEVEL_WARNING -> {
                    Log.w(LOG_TAG, s)
                }
                DfuBaseService.LOG_LEVEL_ERROR -> {
                    Log.e(LOG_TAG, s)
                }
                else -> {
                    Log.e(LOG_TAG, "Unknown log level [$level]: $s")
                }
            }
        }
    }
}