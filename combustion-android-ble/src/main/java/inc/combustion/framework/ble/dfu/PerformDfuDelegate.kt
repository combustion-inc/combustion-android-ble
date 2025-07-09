/*
 * Project: Combustion Inc. Android Framework
 * File: PerformDfuDelegate.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2025. Combustion Inc.
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

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.scanning.AdvertisingData
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.dfu.DfuProgress
import inc.combustion.framework.service.dfu.DfuState
import inc.combustion.framework.service.dfu.DfuStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import no.nordicsemi.android.dfu.DfuBaseService
import no.nordicsemi.android.dfu.DfuProgressListener
import no.nordicsemi.android.dfu.DfuServiceController
import no.nordicsemi.android.dfu.DfuServiceInitiator
import no.nordicsemi.android.error.SecureDfuError

class PerformDfuDelegate(
    deviceStateFlow: MutableStateFlow<DfuState>,
    private val context: Context,
    advertisingData: AdvertisingData,
    private val dfuMac: String, // may be different from mac in DfuState, e.g. for bootloading device
) : DfuProgressListener {

    private val _state = deviceStateFlow
    val state: StateFlow<DfuState> = _state.asStateFlow()

    init {
        _state.update { dfuState ->
            dfuState.copy(
                device = dfuState.device.copy(
                    rssi = advertisingData.rssi,
                    productType = advertisingData.productType.takeIf {
                        (dfuState.device.productType == null) || (dfuState.device.productType == CombustionProductType.UNKNOWN)
                    } ?: dfuState.device.productType,
                ),
            )
        }
    }

    var internalDfuState = InternalDfuState()
        private set

    private var dfuServiceController: DfuServiceController? = null

    // callback used to signal that a DFU is complete (can be success, error) and other devices can be DFU'd.
    var dfuCompleteCallback: ((Boolean) -> Unit)? = null
        private set

    fun emitState(dfuState: DfuState) {
        _state.value = dfuState
    }

    fun updateStuckBootloader(isStuck: Boolean) {
        _state.update {
            it.copy(
                stuckBootloader = isStuck
            )
        }
    }

    fun resetInternalState() {
        internalDfuState = InternalDfuState()
    }

    fun abortDfu() {
        dfuServiceController?.abort() ?: Log.e(LOG_TAG, "Unable to abort DFU process.")

        // Call DFU completed callback
        dfuCompleteCallback?.invoke(false)
    }

    fun performDfu(file: Uri, completionHandler: (Boolean) -> Unit) {
        Log.i(LOG_TAG, "Performing DFU for device ${_state.value.device} with file $file")

        // Save off the completion handler so we can call it when the DFU process is complete.
        dfuCompleteCallback = completionHandler

        // Reset our internal state.
        internalDfuState = InternalDfuState(status = InternalDfuState.Status.IN_PROGRESS)

        _state.update {
            it.copy(
                status = DfuStatus.InProgress(
                    DfuProgress.Initializing(DfuProgress.Initializing.Status.INITIALIZING)
                )
            )
        }

        val starter = DfuServiceInitiator(dfuMac)
            //    .setDeviceName(genDeviceName()) // TODO : fix deviceName not sticking
            .setKeepBond(false)
            .setForceDfu(false)
            .setForceScanningForNewAddressInLegacyDfu(false)
            .setPrepareDataObjectDelay(400)
            .setRebootTime(3000)
            .setScanTimeout(4000)
            .setUnsafeExperimentalButtonlessServiceInSecureDfuEnabled(true)
            .setPacketsReceiptNotificationsEnabled(true)
            .setPacketsReceiptNotificationsValue(12)
            .setZip(file, null)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            DfuServiceInitiator.createDfuNotificationChannel(context)
        }

        dfuServiceController = starter.start(context, DfuService::class.java)
    }

//    private fun genDeviceName(): String {
//        val unique5Digits = String.format("%05d", Random.nextInt(0, 100000))
//        val product = when (state.value.device.dfuProductType) {
//            DfuProductType.PROBE -> "Thermom"
//            DfuProductType.DISPLAY -> "Display"
//            DfuProductType.CHARGER -> "Charger"
//            DfuProductType.GAUGE -> "Gauge"
//            else -> "Thermom"
//        }
//        return "${product}_DFU_$unique5Digits"
//    }

    /**
     * Holds bookkeeping state internal to the device. [status] indicates where we are in the DFU
     * process. [disconnectingEventCount] keeps track of the number of times [onDeviceDisconnecting]
     * has been called. We keep track of this to differentiate between when we're disconnecting to
     * boot into the bootloader at the beginning of the process or when we're disconnecting out of
     * the bootloader and into the app at the end.
     */
    data class InternalDfuState(
        var status: Status = Status.IDLE,
        var disconnectingEventCount: Int = 0,
    ) {
        enum class Status {
            IDLE, // A DFU operation has not been initiated.
            IN_PROGRESS, // A DFU operation is in progress; the Nordic library owns the connection.
            COMPLETE, // The DFU operation completed and the framework should manage the connection.
        }
    }

    override fun onDeviceConnecting(deviceAddress: String) {
        _state.update {
            it.copy(
                status = DfuStatus.InProgress(
                    DfuProgress.Initializing(DfuProgress.Initializing.Status.CONNECTING_TO_DEVICE),
                )
            )
        }

        Log.i(LOG_TAG, "[onDeviceConnecting] $deviceAddress ${_state.value}")
    }

    override fun onDeviceConnected(deviceAddress: String) {
        _state.update {
            it.copy(
                status = DfuStatus.InProgress(
                    DfuProgress.Initializing(DfuProgress.Initializing.Status.CONNECTED_TO_DEVICE),
                )
            )
        }

        Log.i(LOG_TAG, "[onDeviceConnected] $deviceAddress ${_state.value}")
    }

    override fun onDfuProcessStarting(deviceAddress: String) {
        _state.update {
            it.copy(
                status = DfuStatus.InProgress(
                    DfuProgress.Initializing(DfuProgress.Initializing.Status.STARTING_DFU_PROCESS),
                )
            )
        }

        Log.i(LOG_TAG, "[onDfuProcessStarting] $deviceAddress ${_state.value}")
    }

    override fun onDfuProcessStarted(deviceAddress: String) {
        _state.update {
            it.copy(
                status = DfuStatus.InProgress(
                    DfuProgress.Initializing(DfuProgress.Initializing.Status.STARTED_DFU_PROCESS)
                )
            )
        }

        Log.i(LOG_TAG, "[onDfuProcessStarted] $deviceAddress ${_state.value}")
    }

    override fun onEnablingDfuMode(deviceAddress: String) {
        // The command to enter the bootloader is sent immediately after this; the next event
        // encountered is onDeviceDisconnecting.
        _state.update {
            it.copy(
                status = DfuStatus.InProgress(
                    DfuProgress.Initializing(DfuProgress.Initializing.Status.ENABLING_DFU_MODE),
                )
            )
        }

        Log.i(LOG_TAG, "[onEnablingDfuMode] $deviceAddress ${_state.value}")
    }

    override fun onProgressChanged(
        deviceAddress: String,
        percent: Int,
        speed: Float,
        avgSpeed: Float,
        currentPart: Int,
        partsTotal: Int,
    ) {
        _state.update {
            it.copy(
                status = DfuStatus.InProgress(
                    DfuProgress.Uploading(percent, avgSpeed, currentPart, partsTotal)
                )
            )
        }

        Log.i(LOG_TAG, "[onProgressChanged] $deviceAddress : ${_state.value}")
    }

    override fun onFirmwareValidating(deviceAddress: String) {
        _state.update {
            it.copy(
                status = DfuStatus.InProgress(
                    DfuProgress.Initializing(DfuProgress.Initializing.Status.VALIDATING_FIRMWARE),
                )
            )
        }

        Log.i(LOG_TAG, "[onFirmwareValidating] $deviceAddress ${_state.value}")
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
            _state.update {
                it.copy(
                    status = DfuStatus.InProgress(progress)
                )
            }
        }

        Log.i(LOG_TAG, "[onDeviceDisconnecting] $deviceAddress ${_state.value}")
    }

    override fun onDeviceDisconnected(deviceAddress: String) {
        // This isn't reached when rebooting into the bootloader
        _state.update {
            it.copy(
                status = DfuStatus.InProgress(
                    DfuProgress.Finishing(DfuProgress.Finishing.Status.DISCONNECTED_FROM_DEVICE),
                )
            )
        }

        Log.i(LOG_TAG, "[onDeviceDisconnected] $deviceAddress ${_state.value}")
    }

    override fun onDfuCompleted(deviceAddress: String) {
        _state.update {
            it.copy(
                status = DfuStatus.InProgress(
                    DfuProgress.Finishing(DfuProgress.Finishing.Status.COMPLETE_RESTARTING_DEVICE),
                )
            )
        }

        Log.i(LOG_TAG, "[onDfuCompleted] $deviceAddress ${_state.value}")

        internalDfuState.status = InternalDfuState.Status.COMPLETE

        // Call DFU completed callback
        dfuCompleteCallback?.invoke(true)
    }

    override fun onDfuAborted(deviceAddress: String) {
        _state.update {
            it.copy(
                status = DfuStatus.InProgress(DfuProgress.Aborted)
            )
        }

        Log.i(LOG_TAG, "[onDfuAborted] $deviceAddress ${_state.value}")

        // Call DFU completed callback
        dfuCompleteCallback?.invoke(false)
    }

    override fun onError(
        deviceAddress: String,
        error: Int,
        errorType: Int,
        message: String?,
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

        _state.update {
            it.copy(
                status = DfuStatus.InProgress(
                    DfuProgress.Error(type, betterMessage)
                )
            )
        }

        Log.e(LOG_TAG, "[onError] $error (type: $errorType, message: $message ${_state.value}")

        // Call DFU complete callback
        dfuCompleteCallback?.invoke(false)
    }

    fun onLogEvent(level: Int, message: String) {
        val s = "[onLogEvent] $message (${state.value}])"
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