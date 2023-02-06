/*
 * Project: Combustion Inc. Android Framework
 * File: BleDevice.kt
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
package inc.combustion.framework.ble.device

import android.bluetooth.BluetoothAdapter
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.juul.kable.*
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.*
import inc.combustion.framework.ble.scanning.*
import inc.combustion.framework.ble.scanning.CombustionAdvertisingData
import inc.combustion.framework.ble.scanning.DeviceScanner
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.DeviceConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Base class for Combustion Devices.
 *
 * @property mac Bluetooth MAC address of device.
 * @property owner Owner of the instance's coroutine scope.
 * @constructor Creates a new Device with the specified MAC.
 *
 * @param adapter Android BluetoothAdapter.
 */
internal open class BleDevice (
    val mac: String,
    advertisement: CombustionAdvertisingData,
    val owner: LifecycleOwner,
    adapter: BluetoothAdapter,
    val productType: CombustionProductType = advertisement.productType,
) {
    companion object {
        private const val REMOTE_RSSI_POLL_RATE_MS = 2000L
        private const val IDLE_TIMEOUT_POLL_RATE_MS = 1000L
        const val DISCONNECT_TIMEOUT_MS = 500L

        private const val DEV_INFO_SERVICE_UUID_STRING = "0000180A-0000-1000-8000-00805F9B34FB"
        private val SERIAL_NUMBER_CHARACTERISTIC = characteristicOf(
            service = DEV_INFO_SERVICE_UUID_STRING,
            characteristic = "00002A25-0000-1000-8000-00805F9B34FB"
        )
        private val FW_VERSION_CHARACTERISTIC = characteristicOf(
            service = DEV_INFO_SERVICE_UUID_STRING,
            characteristic = "00002A26-0000-1000-8000-00805F9B34FB"
        )
        private val HW_REVISION_CHARACTERISTIC = characteristicOf(
            service = DEV_INFO_SERVICE_UUID_STRING,
            characteristic = "00002A27-0000-1000-8000-00805F9B34FB"
        )
    }

    private val mutex = Mutex()

    /**
     * Abstraction of a unique identifier.
     */
    val id: DeviceID
        get() = mac

    val jobManager = JobManager()
    var remoteRssiJob: Job? = null
    val rssiCallbacks = mutableListOf<(suspend (rssi: Int) -> Unit)>()
    var peripheral: Peripheral =
        owner.lifecycleScope.peripheral(adapter.getRemoteDevice(mac)) {
            logging {
                /* The following enables logging in Kable
                // engine = SystemLogEngine
                // level = Logging.Level.Events
                // format = Logging.Format.Multiline
                // data = Hex
                 */
            }
        }

    private val remoteRssi = AtomicInteger(0)
    private val connectionMonitor = IdleMonitor()

    val rssi get() = remoteRssi.get()
    var connectionState = DeviceConnectionState.OUT_OF_RANGE

    val isConnected = AtomicBoolean(false)
    val isDisconnected = AtomicBoolean(true)
    val isInRange = AtomicBoolean(false)
    val isConnectable = AtomicBoolean(false)

    init {
        handleAdvertisement(advertisement)
    }

    open fun connect() {
        owner.lifecycleScope.launch {
            Log.d(LOG_TAG, "Connecting to $mac")
            try {
                peripheral.connect()
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Connect Error: ${e.localizedMessage}")
                Log.e(LOG_TAG, Log.getStackTraceString(e))
            }
        }
    }

    open fun disconnect() {
        owner.lifecycleScope.launch {
            withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
                peripheral.disconnect()
            }
        }
    }

    open fun finish() {
        disconnect()
        remoteRssiJob = null
        jobManager.cancelJobs()
        rssiCallbacks.clear()
    }

    fun dispatchOnDefault(callback: (suspend () -> Unit)) {
        // run the code on the default coroutine scope
        owner.lifecycleScope.launch {
            callback()
        }
    }

    fun observeConnectionState(callback: (suspend (newConnectionState: DeviceConnectionState) -> Unit)? = null) {
        jobManager.addJob(owner.lifecycleScope.launch {
            peripheral.state.onCompletion {
                Log.d(LOG_TAG, "Connection State Monitor Complete")
            }
            .catch {
                Log.i(LOG_TAG, "Connection State Monitor Catch: $it")
            }
            .collect { state ->
                connectionMonitor.activity()

                connectionState = when (state) {
                    is State.Connecting -> DeviceConnectionState.CONNECTING
                    State.Connected -> DeviceConnectionState.CONNECTED
                    State.Disconnecting -> DeviceConnectionState.DISCONNECTING
                    is State.Disconnected -> DeviceConnectionState.DISCONNECTED
                }

                mutex.withLock {
                    isConnected.set(connectionState == DeviceConnectionState.CONNECTED)
                    isDisconnected.set(state is State.Disconnected)
                }

                callback?.let {it
                    // already being dispatched on default
                    it(connectionState)
                }
            }
        })
    }

    fun observeOutOfRange(timeout: Long, callback: (suspend () -> Unit)? = null) {
        jobManager.addJob(owner.lifecycleScope.launch(Dispatchers.IO) {
            while(isActive) {
                delay(IDLE_TIMEOUT_POLL_RATE_MS)
                val isIdle = connectionMonitor.isIdle(timeout)

                if(isIdle) {
                    mutex.withLock {
                        connectionState = when(connectionState) {
                            DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE -> DeviceConnectionState.OUT_OF_RANGE
                            DeviceConnectionState.ADVERTISING_CONNECTABLE -> DeviceConnectionState.OUT_OF_RANGE
                            DeviceConnectionState.DISCONNECTED -> DeviceConnectionState.OUT_OF_RANGE
                            else -> connectionState
                        }
                        isInRange.set(false)
                        isConnectable.set(false)
                        isDisconnected.set(true)
                    }

                    callback?.let {
                        dispatchOnDefault {
                            it()
                        }
                    }
                }
            }
        })
    }

    fun observeRemoteRssi(callback: (suspend (rssi: Int) -> Unit)? = null) {
        /*
        // maintain a list of objects that are observing RSSI updates for this device.
        callback?.let {
            rssiCallbacks.add(it)
        }

        // but only need one long running thread for this device to read the RSSI while connected.
        if(remoteRssiJob == null) {
            val job = owner.lifecycleScope.launch(Dispatchers.IO) {
                var exceptionCount = 0;
                while(isActive) {
                    if(isConnected.get()) {
                        try {
                            // TODO: DROID-106
                            //
                            remoteRssi.set(peripheral.rssi())
                            exceptionCount = 0;
                        } catch (e: Exception) {
                            exceptionCount++
                            Log.w(LOG_TAG, "Exception while reading remote RSSI: $exceptionCount\n${e.stackTrace}")
                        }

                        when {
                            exceptionCount < 5 -> {
                                rssiCallbacks.forEach {
                                    dispatchOnDefault {
                                        it(remoteRssi.get())
                                    }
                                }
                            }
                            else -> {
                                peripheral.disconnect()
                            }
                        }
                    }
                    delay(REMOTE_RSSI_POLL_RATE_MS)
                }
            }
            remoteRssiJob = job
            jobManager.addJob(job)
        }
         */
    }

    fun observeAdvertisingPackets(filter: (advertisement: CombustionAdvertisingData) -> Boolean, callback: (suspend (advertisement: CombustionAdvertisingData) -> Unit)? = null) {
        jobManager.addJob(owner.lifecycleScope.launch(Dispatchers.IO) {
            DeviceScanner.advertisements.filter {

                // call the passed in filter condition to determine if the packet should be filtered or passed along.
                filter(it)

            }.collect {
                mutex.withLock {
                    handleAdvertisement(it)
                }

                connectionMonitor.activity()

                callback?.let { clientCallback ->
                    dispatchOnDefault {
                        clientCallback(it)
                    }
                }
            }
        })
    }

    protected suspend fun readSerialNumberCharacteristic(): String? =
        readCharacteristic(SERIAL_NUMBER_CHARACTERISTIC, "remote serial number")?.toString(Charsets.UTF_8)

    protected suspend fun readFirmwareVersionCharacteristic(): String? =
        readCharacteristic(FW_VERSION_CHARACTERISTIC, "remote firmware version")?.toString(Charsets.UTF_8)

    protected suspend fun readHardwareRevisionCharacteristic(): String? =
        readCharacteristic(HW_REVISION_CHARACTERISTIC, "remote hardware revision")?.toString(Charsets.UTF_8)

    private suspend fun readCharacteristic(
        characteristic: Characteristic,
        description: String? = null): ByteArray?
    {
        var bytes: ByteArray? = null
        withContext(Dispatchers.IO) {
            try {
                bytes = peripheral.read(characteristic)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Exception while reading ${description ?: characteristic}: \n${e.stackTrace}")
            }
        }
        return bytes
    }

    private fun handleAdvertisement(advertisement: CombustionAdvertisingData) {
        remoteRssi.set(advertisement.rssi)
        isInRange.set(true)
        isConnectable.set(advertisement.isConnectable)

        // the probe continues to advertise even while a BLE connection is
        // established.  determine if the device is currently advertising as
        // connectable or not.
        val advertisingState = when(advertisement.isConnectable) {
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
    }
}