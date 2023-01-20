/*
 * Project: Combustion Inc. Android Framework
 * File: RepeaterUartBleDevice.kt
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
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.scanning.BaseAdvertisingData
import inc.combustion.framework.ble.uart.LogResponse
import inc.combustion.framework.ble.uart.Request
import inc.combustion.framework.ble.uart.meatnet.NodeResponse
import inc.combustion.framework.service.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

internal class RepeaterUartBleDevice (
    mac: String,
    owner: LifecycleOwner,
    advertisement: BaseAdvertisingData,
    adapter: BluetoothAdapter,
    private val probeBleDeviceBase: ProbeBleDeviceBase = ProbeBleDeviceBase(),
) : UartBleDevice(mac, owner, advertisement, adapter),
    IProbeBleDeviceBase by probeBleDeviceBase,
    IProbeLogResponseBleDevice,
    IProbeUartBleDevice {

    companion object {
        const val MESSAGE_RESPONSE_TIMEOUT_MS = 5000L
    }

    private val sessionInfoHandler = MessageCompletionHandler()
    private val setColorHandler = MessageCompletionHandler()
    private val setIdHandler = MessageCompletionHandler()
    private val setPredictionHandler = MessageCompletionHandler()

    private val _logResponseFlow = MutableSharedFlow<LogResponse>(
        replay = 0, extraBufferCapacity = 50, BufferOverflow.SUSPEND)
    override val logResponseFlow = _logResponseFlow.asSharedFlow()

    init {
        observeUartResponses { response ->
            TODO()
            /*
            when (response) {
                is LogResponse -> {
                    _logResponseFlow.emit(response)
                }
                is SessionInfoResponse -> sessionInfoHandler?.let { it(response) }
                is SetColorResponse -> setColorHandler.handled(response.success)
                is SetIDResponse -> setIdHandler.handled(response.success)
                is SetPredictionResponse -> setPredictionHandler.handled(response.success)
            }
             */

            /*
            Handling the probe status over the UART needs to do this ...
            probeBleDeviceBase.mutableProbeStatusFlow.emit(probeStatus)
             */
        }
    }

    override fun sendSessionInformationRequest(callback: ((Boolean, Any?) -> Unit)?)  {
        // see ProbeUartBleDevice
        TODO()
    }

    override fun sendSetProbeColor(color: ProbeColor, callback: ((Boolean, Any?) -> Unit)?) {
        // see ProbeUartBleDevice
        TODO()
    }

    override fun sendSetProbeID(id: ProbeID, callback: ((Boolean, Any?) -> Unit)?) {
        // see ProbeUartBleDevice
        TODO()
    }

    override fun sendSetPrediction(setPointTemperatureC: Double, mode: ProbePredictionMode, callback: ((Boolean, Any?) -> Unit)?) {
        // see ProbeUartBleDevice
        TODO()
    }

    override fun sendLogRequest(minSequence: UInt, maxSequence: UInt) {
        // see ProbeUartBleDevice
        TODO()
    }

    private fun observeUartResponses(callback: (suspend (response: NodeResponse) -> Unit)? = null) {
        jobManager.addJob(owner.lifecycleScope.launch {
            observeUartCharacteristic { data ->
                callback?.let {
                    NodeResponse.responseFromData(data.toUByteArray())
                }
            }
        })
    }

    private fun sendUartRequest(request: Request) {
        owner.lifecycleScope.launch(Dispatchers.IO) {
            if (DebugSettings.DEBUG_LOG_BLE_UART_IO) {
                val packet = request.data.joinToString("") {
                    it.toString(16).padStart(2, '0').uppercase()
                }
                Log.d(LOG_TAG, "UART-TX: $packet")
            }

            writeUartCharacteristic(request.sData)
        }
    }
}
