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
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.juul.kable.Peripheral
import com.juul.kable.characteristicOf
import com.juul.kable.peripheral
import inc.combustion.framework.LOG_TAG
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Base class for Combustion Devices.
 *
 * @property mac Bluetooth MAC address of device.
 * @property owner Owner of the instance's coroutine scope.
 * @constructor Creates a new Device with the specified MAC.
 *
 * @param adapter Android BluetoothAdapter.
 */
internal open class Device (
    private val mac: String,
    private val owner: LifecycleOwner,
    adapter: BluetoothAdapter
){
    companion object {
        private const val DEVICE_INFO_SERVICE_UUID = "180a"
        private const val UART_SERVICE_UUID        = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"

        const val DISCONNECT_TIMEOUT_MS = 500L

        val UART_RX_CHARACTERISTIC = characteristicOf(
            service = UART_SERVICE_UUID,
            characteristic = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
        )

        val UART_TX_CHARACTERISTIC = characteristicOf(
            service = UART_SERVICE_UUID,
            characteristic = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
        )
    }

    class IdleMonitor() {
        var lastUpdateTime : Long = 0

        fun activity() {
            lastUpdateTime = SystemClock.elapsedRealtime()
        }

        fun isIdle(timeout: Long): Boolean {
            return (SystemClock.elapsedRealtime() - lastUpdateTime) >= timeout
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

    open suspend fun checkIdle() { }

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
}