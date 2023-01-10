/*
 * Project: Combustion Inc. Android Framework
 * File: LegacyProbeBleDevice.kt
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
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.juul.kable.characteristicOf
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.uart.*
import inc.combustion.framework.ble.uart.LogResponse
import inc.combustion.framework.ble.uart.Response
import inc.combustion.framework.ble.uart.SessionInfoResponse
import inc.combustion.framework.ble.uart.SetColorResponse
import inc.combustion.framework.ble.uart.SetIDResponse
import inc.combustion.framework.ble.uart.SetPredictionResponse
import inc.combustion.framework.service.DebugSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal open class UartBleDevice(
    mac: String,
    owner: LifecycleOwner,
    advertisement: LegacyProbeAdvertisingData,
    adapter: BluetoothAdapter
) : DeviceInformationBleDevice(mac, owner, advertisement, adapter) {

    class MessageCompletionHandler {
        private val waiting = AtomicBoolean(false)
        private var completionCallback: ((Boolean) -> Unit)? = null

        fun handled(result: Boolean) {
            waiting.set(false)
            completionCallback?.let {
                it(result)
            }
            completionCallback = null
        }

        fun wait(owner: LifecycleOwner, duration: Long, callback: ((Boolean) -> Unit)?) {
            if(waiting.get()) {
                completionCallback?.let {
                    it(false)
                }
                return
            }

            waiting.set(true)
            completionCallback = callback
            owner.lifecycleScope.launch {
                delay(duration)
                if(waiting.get()) {
                    completionCallback?.let {
                       it(false)
                    }
                }
            }
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

    protected suspend fun writeUartCharacteristic(data: ByteArray) {
        if(isConnected.get()) {
            owner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    peripheral.write(UART_RX_CHARACTERISTIC, data)
                } catch(e: Exception)  {
                    Log.w(LOG_TAG, "UART-TX: Unable to write to RX characteristic.")
                }
            }
        }
    }

    protected suspend fun observeUartCharacteristic(callback: (suspend (data: UByteArray) -> Unit)? = null) {
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