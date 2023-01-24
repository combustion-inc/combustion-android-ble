/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeBleDevice.kt
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
import com.juul.kable.characteristicOf
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.ProbeStatus
import inc.combustion.framework.ble.scanning.CombustionAdvertisingData
import inc.combustion.framework.ble.uart.*
import inc.combustion.framework.ble.uart.LogRequest
import inc.combustion.framework.ble.uart.SessionInfoRequest
import inc.combustion.framework.ble.uart.SetColorRequest
import inc.combustion.framework.ble.uart.SetIDRequest
import inc.combustion.framework.ble.uart.SetPredictionRequest
import inc.combustion.framework.service.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

internal class ProbeBleDevice (
    mac: String,
    owner: LifecycleOwner,
    probeAdvertisingData: CombustionAdvertisingData,
    adapter: BluetoothAdapter,
    private val probeSerialNumber: String = probeAdvertisingData.probeSerialNumber,
    private val uart: UartBleDevice = UartBleDevice(mac, owner, probeAdvertisingData, adapter),
) : ProbeBleDeviceBase() {

    companion object {
        const val MESSAGE_RESPONSE_TIMEOUT_MS = 5000L
        val DEVICE_STATUS_CHARACTERISTIC = characteristicOf(
            service = "00000100-CAAB-3792-3D44-97AE51C1407A",
            characteristic = "00000101-CAAB-3792-3D44-97AE51C1407A"
        )
    }

    override val advertisement: CombustionAdvertisingData?
        get() {
            return uart.advertisementForProbe(probeSerialNumber)
        }

    // identifiers
    override val linkId: LinkID
        get() {
            return makeLinkId(advertisement)
        }
    override val id = uart.id

    // ble properties
    override val rssi = uart.rssi
    override val connectionState = uart.connectionState
    override val isConnected = uart.isConnected.get()

    override val hopCount: UInt
        get() {
            return advertisement?.hopCount ?: UInt.MAX_VALUE
        }

    init {
        processProbeStatusCharacteristic()
        processUartResponses()
    }

    override fun connect() = uart.connect()
    override fun disconnect() = uart.disconnect()

    override fun sendSessionInformationRequest(callback: ((Boolean, Any?) -> Unit)?)  {
        sessionInfoHandler.wait(uart.owner, MESSAGE_RESPONSE_TIMEOUT_MS, callback)
        sendUartRequest(SessionInfoRequest())
    }

    override fun sendSetProbeColor(color: ProbeColor, callback: ((Boolean, Any?) -> Unit)?) {
        setColorHandler.wait(uart.owner, MESSAGE_RESPONSE_TIMEOUT_MS, callback)
        sendUartRequest(SetColorRequest(color))
    }

    override fun sendSetProbeID(id: ProbeID, callback: ((Boolean, Any?) -> Unit)?) {
        setIdHandler.wait(uart.owner, MESSAGE_RESPONSE_TIMEOUT_MS, callback)
        sendUartRequest(SetIDRequest(id))
    }

    override fun sendSetPrediction(setPointTemperatureC: Double, mode: ProbePredictionMode, callback: ((Boolean, Any?) -> Unit)?) {
        setPredictionHandler.wait(uart.owner, MESSAGE_RESPONSE_TIMEOUT_MS, callback)
        sendUartRequest(SetPredictionRequest(setPointTemperatureC, mode))
    }

    override fun sendLogRequest(minSequence: UInt, maxSequence: UInt) {
        sendUartRequest(LogRequest(minSequence, maxSequence))
    }

    override suspend fun readSerialNumber() = uart.readSerialNumber()
    override suspend fun readFirmwareVersion() = uart.readFirmwareVersion()
    override suspend fun readHardwareRevision() = uart.readHardwareRevision()

    override fun observeAdvertisingPackets(serialNumber: String, callback: (suspend (advertisement: CombustionAdvertisingData) -> Unit)?) = uart.observeAdvertisingPackets(serialNumber, callback)
    override fun observeRemoteRssi(callback: (suspend (rssi: Int) -> Unit)?) = uart.observeRemoteRssi(callback)
    override fun observeOutOfRange(timeout: Long, callback: (suspend () -> Unit)?) = uart.observeOutOfRange(timeout, callback)
    override fun observeConnectionState(callback: (suspend (newConnectionState: DeviceConnectionState) -> Unit)?) = uart.observeConnectionState(callback)

    private fun observeUartResponses(callback: (suspend (responses: List<Response>) -> Unit)? = null) {
        uart.jobManager.addJob(uart.owner.lifecycleScope.launch {
            uart.observeUartCharacteristic { data ->
                callback?.let {
                    it(Response.fromData(data.toUByteArray()))
                }
            }
        })
    }

    private fun sendUartRequest(request: Request) {
        uart.owner.lifecycleScope.launch(Dispatchers.IO) {
            if (DebugSettings.DEBUG_LOG_BLE_UART_IO) {
                val packet = request.data.joinToString("") {
                    it.toString(16).padStart(2, '0').uppercase()
                }
                Log.d(LOG_TAG, "UART-TX: $packet")
            }
            uart.writeUartCharacteristic(request.sData)
        }
    }

    private fun processProbeStatusCharacteristic() {
        uart.jobManager.addJob(uart.owner.lifecycleScope.launch {
            uart.peripheral.observe(DEVICE_STATUS_CHARACTERISTIC)
                .onCompletion {
                    Log.d(LOG_TAG, "Device Status Characteristic Monitor Complete")
                }
                .catch {
                    Log.i(LOG_TAG, "Device Status Characteristic Monitor Catch: $it")
                }
                .collect { data ->
                    ProbeStatus.fromRawData(data.toUByteArray())?.let { status ->
                        mutableProbeStatusFlow.emit(status)
                    }
                }
        })
    }

    private fun processUartResponses() {
        observeUartResponses { responses ->
            for (response in responses) {
                when (response) {
                    is LogResponse -> {
                        mutableLogResponseFlow.emit(response)
                    }
                    is SessionInfoResponse -> sessionInfoHandler.handled(response.success, response.sessionInformation)
                    is SetColorResponse -> setColorHandler.handled(response.success, null)
                    is SetIDResponse -> setIdHandler.handled(response.success, null)
                    is SetPredictionResponse -> setPredictionHandler.handled(response.success, null)
                }
            }
        }
    }
}
