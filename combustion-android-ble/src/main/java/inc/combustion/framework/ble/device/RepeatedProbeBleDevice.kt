/*
 * Project: Combustion Inc. Android Framework
 * File: RepeatedProbeBleDevice.kt
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

import android.util.Log
import androidx.lifecycle.lifecycleScope
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.scanning.BaseAdvertisingData
import inc.combustion.framework.ble.scanning.CombustionAdvertisingData
import inc.combustion.framework.ble.uart.Request
import inc.combustion.framework.ble.uart.meatnet.NodeResponse
import inc.combustion.framework.service.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class RepeatedProbeBleDevice (
    private val probeSerialNumber: String,
    private val uart: UartBleDevice,
    advertisement: CombustionAdvertisingData,
) : ProbeBleDeviceBase() {

    companion object {
        const val MESSAGE_RESPONSE_TIMEOUT_MS = 5000L
    }

    private val advertisementForProbe = hashMapOf<String, CombustionAdvertisingData>()

    override val advertisement: CombustionAdvertisingData?
        get() {
            return advertisementForProbe[probeSerialNumber]
        }

    override val linkId: LinkID
        get() {
            return makeLinkId(advertisement)
        }

    override val id: DeviceID
        get() {
            return uart.id
        }

    override val mac: String
        get() {
            return uart.mac
        }

    override val rssi = uart.rssi
    override val connectionState = uart.connectionState
    override val isConnected = uart.isConnected.get()

    override val hopCount: UInt
        get() {
            return advertisement?.hopCount ?: UInt.MAX_VALUE
        }

    val probeMac: String get() { TODO() }
    val probeRssi: Int get() { TODO() }
    val probeConnectionState: DeviceConnectionState get() { TODO() }
    val probeIsConnected: Boolean get() { TODO() }

    init {
        advertisementForProbe[advertisement.probeSerialNumber] = advertisement
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

            /*
             note this is going to be called back for all responses coming back from the UARt
             we need to filter in here for the ones destined to this end point / probe
             */
        }
    }

    override fun connect() = uart.connect()
    override fun disconnect() = uart.disconnect()

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

    override suspend fun readSerialNumber() {
        TODO()
    }

    override suspend fun readFirmwareVersion() {
        TODO()
    }

    override suspend fun readHardwareRevision() {
        TODO()
    }

    override fun observeAdvertisingPackets(serialNumberFilter: String, macFilter: String, callback: (suspend (advertisement: CombustionAdvertisingData) -> Unit)?) {
        uart.observeAdvertisingPackets(
            { advertisement ->  macFilter == advertisement.mac && advertisement.probeSerialNumber == serialNumberFilter }
        ) { advertisement ->
            callback?.let {
                advertisementForProbe[probeSerialNumber] = advertisement
                it(advertisement)
            }
        }
    }

    override fun observeRemoteRssi(callback: (suspend (rssi: Int) -> Unit)?) = uart.observeRemoteRssi(callback)
    override fun observeOutOfRange(timeout: Long, callback: (suspend () -> Unit)?) = uart.observeOutOfRange(timeout, callback)
    override fun observeConnectionState(callback: (suspend (newConnectionState: DeviceConnectionState) -> Unit)?) = uart.observeConnectionState(callback)

    private fun observeUartResponses(callback: (suspend (response: NodeResponse) -> Unit)? = null) {
        uart.jobManager.addJob(uart.owner.lifecycleScope.launch {
            uart.observeUartCharacteristic { data ->
                callback?.let {

                    NodeResponse.responseFromData(data.toUByteArray())
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
}
