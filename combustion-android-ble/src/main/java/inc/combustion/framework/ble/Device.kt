/*
 * Project: Combustion Inc. Android Framework
 * File: Device.kt
 * Author: https://github.com/miwright2
 *
 * MIT License
 *
 * Copyright (c) 2022. Combustion Inc.
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

import android.bluetooth.BluetoothAdapter
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.juul.kable.*
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.service.DeviceConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Base class for Combustion Devices.
 *
 * @property mac Bluetooth MAC address of device.
 * @property owner Owner of the instance's coroutine scope.
 * @constructor Creates a new Device with the specified MAC.
 *
 * @param adapter Android BluetoothAdapter.
 */
internal abstract class Device (
    private val mac: String,
    private val owner: LifecycleOwner,
    adapter: BluetoothAdapter
) {
    /**
     * Called whenever the peripheral's state flow has changed
     * (see https://github.com/JuulLabs/kable#state).
     *
     * @c connectionState and @p isConnected are guaranteed to be valid when this is called, and
     * @c connectionState will have the same value as @p newConnectionState.
     *
     * @param newConnectionState The connection state just entered by the peripheral.
     */
    protected abstract suspend fun onConnectionStateChanged(newConnectionState: DeviceConnectionState)

    companion object {
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

    init {
        // connection state flow monitor
        addJob(owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                connectionStateMonitor()
            }
        })
    }

    internal var serialNumber: String? = null
    internal var fwVersion: String? = null
    internal var hwRevision: String? = null

    protected val isConnected = AtomicBoolean(false)
    protected var connectionState = DeviceConnectionState.OUT_OF_RANGE

    class IdleMonitor() {
        var lastUpdateTime: Long = 0

        fun activity() {
            lastUpdateTime = SystemClock.elapsedRealtime()
        }

        fun isIdle(timeout: Long): Boolean {
            return (SystemClock.elapsedRealtime() - lastUpdateTime) >= timeout
        }
    }

    private suspend fun connectionStateMonitor() {
        peripheral.state.onCompletion {
            Log.d(LOG_TAG, "Connection State Monitor Complete")
        }
        .catch {
            Log.i(LOG_TAG, "Connection State Monitor Catch: $it")
        }
        .collect { state ->
            monitor.activity()

            connectionState = when (state) {
                is State.Connecting -> DeviceConnectionState.CONNECTING
                State.Connected -> DeviceConnectionState.CONNECTED
                State.Disconnecting -> DeviceConnectionState.DISCONNECTING
                is State.Disconnected -> DeviceConnectionState.DISCONNECTED
            }

            isConnected.set(connectionState == DeviceConnectionState.CONNECTED)

            // Tell derived devices that the connection's been updated.
            onConnectionStateChanged(connectionState)
        }
    }

    private val jobList = mutableListOf<Job>()

    protected val monitor = IdleMonitor()

    protected var peripheral: Peripheral =
        owner.lifecycleScope.peripheral(adapter.getRemoteDevice(mac)) {
            logging {
                // The following enables logging in Kable

                // engine = SystemLogEngine
                // level = Logging.Level.Events
                // format = Logging.Format.Multiline
                // data = Hex

            }
        }

    open suspend fun checkIdle() {}

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

    fun addJob(job: Job) {
        jobList.add(job)
    }

    fun finish() {
        disconnect()
        cancelJobs()
    }

    private fun cancelJobs() {
        jobList.forEach { it.cancel() }
    }

    private suspend fun readCharacteristic(
        characteristic: Characteristic,
        description: String? = null): ByteArray?
    {
        var bytes: ByteArray? = null

        withContext(Dispatchers.IO) {
            try {
                bytes = peripheral.read(SERIAL_NUMBER_CHARACTERISTIC)
            } catch (e: Exception) {
                Log.w(
                    LOG_TAG,
                    "Exception while reading ${description ?: characteristic}: \n${e.stackTrace}"
                )
            }
        }
        return bytes
    }

    protected suspend fun readSerialNumber() {
        val bytes = readCharacteristic(SERIAL_NUMBER_CHARACTERISTIC, "remote serial number")
        serialNumber = bytes?.toString(Charsets.UTF_8)
    }

    protected suspend fun readFirmwareVersion() {
        val bytes = readCharacteristic(FW_VERSION_CHARACTERISTIC, "remote FW version")
        fwVersion = bytes?.toString(Charsets.UTF_8)
    }

    protected suspend fun readHardwareRevision() {
        val bytes = readCharacteristic(HW_REVISION_CHARACTERISTIC, "remote HW version")
        hwRevision = bytes?.toString(Charsets.UTF_8)
    }
}
