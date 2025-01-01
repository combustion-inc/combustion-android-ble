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
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.juul.kable.*
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.*
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
        private val MODEL_INFO_CHARACTERISTIC = characteristicOf(
            service = DEV_INFO_SERVICE_UUID_STRING,
            characteristic = "00002A24-0000-1000-8000-00805F9B34FB"
        )
    }

    private val mutex = Mutex()

    /**
     * Abstraction of a unique identifier.
     */
    val id: DeviceID
        get() = mac

    val jobManager = JobManager()
    private val rssiCallbacks = mutableListOf<(suspend (rssi: Int) -> Unit)>()
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

    /**
     * Attempts to connect to this peripheral. Occasionally we see exceptions during the Kable
     * connect operation. Set [numAttempts] to more than 1 to let this function retry connections
     * on a connect failure. [observeConnectionState] and [connectionState] can be used to monitor
     * connection status if you'd like to manage connection retries yourself.
     */
    open fun connect(numAttempts: Int = 1) {
        assert(numAttempts > 0)

        owner.lifecycleScope.launch {
            for (attempt in 1..numAttempts) {
                Log.d(LOG_TAG, "Connecting to $mac (attempt $attempt / $numAttempts)")
                try {
                    peripheral.connect()
                    Log.d(LOG_TAG, "Done connecting to $mac (attempt $attempt / $numAttempts)")

                    break
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Connect Error: ${e.localizedMessage}")
                    Log.e(LOG_TAG, Log.getStackTraceString(e))
                }
            }
        }
    }

    open fun disconnect() {
        Log.d(LOG_TAG, "BleDevice.disconnect($mac)")

        owner.lifecycleScope.launch {
            withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
                peripheral.disconnect()
            }
        }
    }

    /**
     * Cancels all jobs for [jobKey] and unregisters callbacks. Also optionally disconnects the
     * device if [disconnect] is true. If [jobKey] is null, all jobs are cancelled. This should be
     * the same job key that was used when calling [observeAdvertisingPackets].
     */
    open fun finish(jobKey: String? = null, disconnect: Boolean = true) {
        Log.d(LOG_TAG, "BleDevice.finish() for $mac; (jobKey=$jobKey, disconnect=$disconnect)")
        if (disconnect) {
            disconnect()
        }

        jobKey?.let {
            jobManager.cancelJobs(it)
        } ?: jobManager.cancelJobs()
        rssiCallbacks.clear()
    }

    private fun dispatchOnDefault(callback: (suspend () -> Unit)) {
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

                callback?.let {
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
        // Maintain a list of objects that are observing RSSI updates for this device. They'll be
        // called back when the RSSI is updated in advertising data.
        callback?.let {
            rssiCallbacks.add(it)
        }
    }

    fun observeAdvertisingPackets(jobKey: String?, filter: (advertisement: CombustionAdvertisingData) -> Boolean, callback: (suspend (advertisement: CombustionAdvertisingData) -> Unit)? = null) {
        jobManager.addJob(
            key = jobKey,
            job = owner.lifecycleScope.launch(
                CoroutineName("${jobKey}.observeAdvertisingPackets") + Dispatchers.IO
            ) {
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
            }.also { job ->
                job.invokeOnCompletion {
                    Log.d(LOG_TAG, "observeAdvertisingPackets() for $jobKey complete ($it)")
                }
            }
        )
    }

    protected suspend fun readSerialNumberCharacteristic(): String? =
        readCharacteristic(SERIAL_NUMBER_CHARACTERISTIC, "remote serial number")?.toString(Charsets.UTF_8)

    protected suspend fun readFirmwareVersionCharacteristic(): String? =
        readCharacteristic(FW_VERSION_CHARACTERISTIC, "remote firmware version")?.toString(Charsets.UTF_8)

    protected suspend fun readHardwareRevisionCharacteristic(): String? =
        readCharacteristic(HW_REVISION_CHARACTERISTIC, "remote hardware revision")?.toString(Charsets.UTF_8)

    protected suspend fun readModelNumberCharacteristic(): String? =
        readCharacteristic(MODEL_INFO_CHARACTERISTIC, "remote model info")?.toString(Charsets.UTF_8)

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

        rssiCallbacks.toList().forEach {
            dispatchOnDefault {
                it(remoteRssi.get())
            }
        }

        // if the device is advertising as connectable, advertising as non-connectable,
        // currently disconnected, or currently out of range then it's new state is the
        // advertising state determined above. otherwise, (connected, connecting or
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