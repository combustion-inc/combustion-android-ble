/*
 * Project: Combustion Inc. Android Framework
 * File: UartBleDevice.kt
 * Author: http://github.com/miwright2
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
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.scanning.CombustionAdvertisingData
import inc.combustion.framework.service.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal open class UartBleDevice(
    mac: String,
    advertisement: CombustionAdvertisingData,
    owner: LifecycleOwner,
    adapter: BluetoothAdapter
) : DeviceInformationBleDevice(mac, advertisement, owner, adapter) {

    class MessageCompletionHandler {
        private val waiting = AtomicBoolean(false)
        private var completionCallback: ((Boolean, Any?) -> Unit)? = null
        private var job: Job? = null

        var requestId: UInt? = null
            private set

        val isWaiting: Boolean
            get() {
                return waiting.get()
            }

        // Returns true if the message reqId matched or the callback was called
        fun handled(result: Boolean, data: Any?, reqId: UInt? = null): Boolean {
            var matched = false
            // if we are waiting for a specific request id
            requestId?.let {
                // and we weren't called with the request id we are looking for
                if(it == reqId) {
                    matched = true
                } else {
                    return matched
                }
            }

            job?.cancel()
            waiting.set(false)
            // Make a local copy of the completion callback and then cleanup the state
            // This allows for sending additional messages from within the callback
            val localCallback = completionCallback
            cleanup()

            localCallback?.let {
                it(result, data)
                matched = true
            }

            return matched
        }

        fun wait(owner: LifecycleOwner, duration: Long, reqId: UInt? = null, callback: ((Boolean, Any?) -> Unit)?) {
            requestId?.let {
                // if we are waiting for a specific request id and asked to wait again, then
                // we callback to the caller with an error (the current wait has not completed).
                if(it == reqId) {
                    callback?.let { it(false, null) }
                    return
                }

                // otherwise, cancel the current wait and start a new wait with the new request.
                cancel()
            }

            // if we are already waiting for a completion callback, then we callback to the
            // caller with an error (the current wait has not completed).
            completionCallback?.let {
                callback?.let { it(false, null) }
                return
            }

            if(waiting.get()) {
                callback?.let { it(false, null) }
                return
            }

            waiting.set(true)
            completionCallback = callback
            requestId = reqId

            job = owner.lifecycleScope.launch {
                delay(duration)
                if(waiting.getAndSet(false)) {
                    callback?.let { it(false, null) }
                    cleanup()
                }
            }
        }

        fun cancel() {
            job?.cancel()
            waiting.set(false)
            cleanup()
        }

        private fun cleanup() {
            completionCallback = null
            requestId = null
        }
    }

    companion object {
        val UART_TX_CHARACTERISTIC = characteristicOf(
            service = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
            characteristic = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
        )
        val UART_RX_CHARACTERISTIC = characteristicOf(
            service = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
            characteristic = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
        )
    }

    var isInDfuMode: Boolean = false

    fun writeUartCharacteristic(data: ByteArray) {
        if(isConnected.get()) {
            owner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    peripheral.write(UART_RX_CHARACTERISTIC, data, WriteType.WithoutResponse)
                } catch(e: Exception)  {
                    Log.w(LOG_TAG, "UART-TX: Unable to write to RX characteristic.")
                }
            }
        }
    }

    suspend fun observeUartCharacteristic(callback: (suspend (data: UByteArray) -> Unit)?) {
        peripheral.observe(UART_TX_CHARACTERISTIC)
            .onCompletion {
                if (DebugSettings.DEBUG_LOG_BLE_UART_IO) {
                    Log.d(LOG_TAG, "UART-TX Monitor Done")
                }
            }
            .catch { exception ->
                Log.i(LOG_TAG, "UART-TX Monitor Catch: $exception")
            }
            .collect { data ->
                if (DebugSettings.DEBUG_LOG_BLE_UART_IO) {
                    val packet = data.toUByteArray().joinToString("") { ubyte ->
                        ubyte.toString(16).padStart(2, '0').uppercase()
                    }
                    Log.d(LOG_TAG, "UART-RX: $packet")
                }

                callback?.let {
                    it(data.toUByteArray())
                }
            }
    }
}