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
package inc.combustion.framework.ble.deleteme

import android.bluetooth.BluetoothAdapter
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.juul.kable.characteristicOf
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.device.UartBleDevice
import inc.combustion.framework.ble.uart.*
import inc.combustion.framework.ble.uart.Request
import inc.combustion.framework.ble.uart.Response
import inc.combustion.framework.ble.uart.SessionInfoResponse
import inc.combustion.framework.ble.uart.SetIDResponse
import inc.combustion.framework.ble.uart.SetPredictionResponse
import inc.combustion.framework.service.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

/*
internal class LegacyProbeBleDevice(
    mac: String,
    owner: LifecycleOwner,
    advertisement: LegacyProbeAdvertisingData,
    adapter: BluetoothAdapter
) : UartBleDevice(mac, owner, advertisement, adapter) {

    companion object {
        const val MESSAGE_RESPONSE_TIMEOUT_MS = 5000L

        val DEVICE_STATUS_CHARACTERISTIC = characteristicOf(
            service = "00000100-CAAB-3792-3D44-97AE51C1407A",
            characteristic = "00000101-CAAB-3792-3D44-97AE51C1407A"
        )
    }

    private val setColorHandler = MessageCompletionHandler()
    private val setIdHandler = MessageCompletionHandler()
    private val setPredictionHandler = MessageCompletionHandler()

    private var sessionInfoHandler: ((SessionInfoResponse) -> Unit)? = null
    private var logResponseHandler: ((LogResponse) -> Unit)? = null
    private var probeStatusHandler: (suspend (ProbeStatus) -> Unit)? = null

    var sessionInfo: SessionInformation? = null
    var probeStatus: ProbeStatus? = null
    var predictionStatus: PredictionStatus? = null

    init {
        observeProbeStatusCharacteristic()
        observeUartResponses { responses ->
            for (response in responses) {
                when (response) {
                    is LogResponse -> logResponseHandler?.let { it(response) }
                    is SessionInfoResponse -> sessionInfoHandler?.let { it(response) }
                    is SetColorResponse -> setColorHandler.handled(response.success)
                    is SetIDResponse -> setIdHandler.handled(response.success)
                    is SetPredictionResponse -> setPredictionHandler.handled(response.success)
                }
            }
        }
    }

    fun sendSessionInformationRequest()  {
        sendUartRequest(SessionInfoRequest())
        sessionInfoHandler = { it ->
            if(it.success) {
                sessionInfo = it.sessionInformation
            }
        }
    }

    fun sendSetProbeColor(color: ProbeColor, callback: ((Boolean) -> Unit)? = null ) {
        setColorHandler.wait(owner, MESSAGE_RESPONSE_TIMEOUT_MS, callback)
        sendUartRequest(SetColorRequest(color))
    }

    fun sendSetProbeID(id: ProbeID, callback: ((Boolean) -> Unit)? = null ) {
        setIdHandler.wait(owner, MESSAGE_RESPONSE_TIMEOUT_MS, callback)
        sendUartRequest(SetIDRequest(id))
    }

    fun sendSetPrediction(setPointTemperatureC: Double, mode: ProbePredictionMode, callback: ((Boolean) -> Unit)? = null) {
        setPredictionHandler.wait(owner, MESSAGE_RESPONSE_TIMEOUT_MS, callback)
        sendUartRequest(SetPredictionRequest(setPointTemperatureC, mode))
    }

    fun sendLogRequest(minSequence: UInt, maxSequence: UInt, callback: ((LogResponse) -> Unit)? = null) {
        logResponseHandler = callback
        sendUartRequest(LogRequest(minSequence, maxSequence))
    }

    fun observeProbeStatusNotifications(callback: (suspend (ProbeStatus) -> Unit)? = null) {
        probeStatusHandler = callback
    }

    fun disconnected() {
        sessionInfo = null
        probeStatus = null
        predictionStatus = null
    }

    fun simulateProbeStatus(status: ProbeStatus) {
        handleProbeStatus(status)
    }

    private fun handleProbeStatus(status: ProbeStatus) {
        probeStatus = status
        if(status.mode == ProbeMode.NORMAL) {
            predictionStatus = status.predictionStatus
        }
    }

    private fun observeProbeStatusCharacteristic() {
        jobManager.addJob(owner.lifecycleScope.launch {
            peripheral.observe(DEVICE_STATUS_CHARACTERISTIC)
                .onCompletion {
                    Log.d(LOG_TAG, "Device Status Characteristic Monitor Complete")
                }
                .catch {
                    Log.i(LOG_TAG, "Device Status Characteristic Monitor Catch: $it")
                }
                .collect { data ->
                    ProbeStatus.fromRawData(data.toUByteArray())?.let { status ->
                        handleProbeStatus(status)
                        probeStatusHandler?.let {
                            it(status)
                        }
                    }
                }
        })
    }

    private fun observeUartResponses(callback: (suspend (responses: List<Response>) -> Unit)? = null) {
        jobManager.addJob(owner.lifecycleScope.launch {
            observeUartCharacteristic { data ->
                callback?.let {
                    it(Response.fromData(data.toUByteArray()))
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
 */