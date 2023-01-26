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
import inc.combustion.framework.service.DeviceConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onCompletion
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
    val owner: LifecycleOwner,
    advertisement: CombustionAdvertisingData,
    adapter: BluetoothAdapter
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

    /**
     * Abstraction of a unique identifier.
     */
    val id: DeviceID
        get() = mac

    val jobManager = JobManager()
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

    private val advertisementForProbe = hashMapOf<String, CombustionAdvertisingData>()
    private val remoteRssi = AtomicInteger(0)
    private val connectionMonitor = IdleMonitor()

    val rssi get() = remoteRssi.get()
    var connectionState = DeviceConnectionState.OUT_OF_RANGE
    val isConnected = AtomicBoolean(false)

    init {
        advertisementForProbe[advertisement.probeSerialNumber] = advertisement
    }

    open fun addRepeatedAdvertisement(serialNumber: String, advertisement: CombustionAdvertisingData) {
        advertisementForProbe[serialNumber] = advertisement
    }

    open fun advertisementForProbe(serialNumber: String): CombustionAdvertisingData? {
        if(advertisementForProbe.containsKey(serialNumber)) {
            return advertisementForProbe[serialNumber]
        }

        return null
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

    fun finish() {
        disconnect()
        jobManager.cancelJobs()
    }

    fun observeConnectionState(callback: (suspend (newConnectionState: DeviceConnectionState) -> Unit)? = null) {
        jobManager.addJob(owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.CREATED) {
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

                    isConnected.set(connectionState == DeviceConnectionState.CONNECTED)

                    callback?.let {
                        it(connectionState)
                    }
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
                    connectionState = when(connectionState) {
                        DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE -> DeviceConnectionState.OUT_OF_RANGE
                        DeviceConnectionState.ADVERTISING_CONNECTABLE -> DeviceConnectionState.OUT_OF_RANGE
                        DeviceConnectionState.DISCONNECTED -> DeviceConnectionState.OUT_OF_RANGE
                        else -> connectionState
                    }
                    callback?.let {
                        it()
                    }
                }
            }
        })
    }

    fun observeRemoteRssi(callback: (suspend (rssi: Int) -> Unit)? = null) {
        jobManager.addJob(owner.lifecycleScope.launch(Dispatchers.IO) {
            var exceptionCount = 0;
            while(isActive) {
                if(isConnected.get() && mac != SimulatedProbeBleDevice.SIMULATED_MAC) {
                    try {
                        remoteRssi.set(peripheral.rssi())
                        exceptionCount = 0;
                    } catch (e: Exception) {
                        exceptionCount++
                        Log.w(LOG_TAG, "Exception while reading remote RSSI: $exceptionCount\n${e.stackTrace}")
                    }

                    when {
                        exceptionCount < 5 -> {
                            callback?.let {
                                it(remoteRssi.get())
                            }
                        }
                        else -> {
                            peripheral.disconnect()
                        }
                    }
                }
                delay(REMOTE_RSSI_POLL_RATE_MS)
            }
        })
    }

    fun observeAdvertisingPackets(serialNumber: String, callback: (suspend (advertisement: CombustionAdvertisingData) -> Unit)? = null) {
        jobManager.addJob(owner.lifecycleScope.launch(Dispatchers.IO) {
            DeviceScanner.advertisements.filter {

                // if advertising packet has same mac address and same probe serial number
                mac == it.mac && it.probeSerialNumber == serialNumber

            }.collect {
                remoteRssi.set(it.rssi)
                connectionMonitor.activity()

                // the probe continues to advertise even while a BLE connection is
                // established.  determine if the device is currently advertising as
                // connectable or not.
                val advertisingState = when(it.isConnectable) {
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

                advertisementForProbe[serialNumber] = it

                callback?.let { clientCallback ->
                    clientCallback(it)
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
}