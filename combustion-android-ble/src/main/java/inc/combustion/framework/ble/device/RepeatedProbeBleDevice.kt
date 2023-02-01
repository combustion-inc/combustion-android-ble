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
import inc.combustion.framework.ble.NOT_IMPLEMENTED
import inc.combustion.framework.ble.ProbeStatus
import inc.combustion.framework.ble.scanning.CombustionAdvertisingData
import inc.combustion.framework.ble.uart.Request
import inc.combustion.framework.ble.uart.SetPredictionRequest
import inc.combustion.framework.ble.uart.meatnet.*
import inc.combustion.framework.ble.uart.meatnet.NodeProbeStatusRequest
import inc.combustion.framework.ble.uart.meatnet.NodeRequest
import inc.combustion.framework.ble.uart.meatnet.NodeSetPredictionResponse
import inc.combustion.framework.ble.uart.meatnet.NodeUARTMessage
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

    private var probeStatusCallback: (suspend (status: ProbeStatus) -> Unit)? = null

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

    // ble properties
    override val rssi: Int get() { return uart.rssi }
    override val connectionState: DeviceConnectionState get() { return uart.connectionState }
    override val isConnected: Boolean get() { return uart.isConnected.get() }
    override val isDisconnected: Boolean get() { return uart.isDisconnected.get() }
    override val isInRange: Boolean get() { return uart.isInRange.get() }
    override val isConnectable: Boolean get() { return uart.isConnectable.get() }

    // device information service values from the repeated probe's node.
    override val deviceInfoSerialNumber: String? get() { return uart.serialNumber }
    override val deviceInfoFirmwareVersion: String? get() { return uart.firmwareVersion }
    override val deviceInfoHardwareRevision: String? get() { return uart.hardwareRevision }

    override val productType: CombustionProductType get() { return uart.productType}

    private var _hopCount: UInt
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
        _hopCount = advertisement.hopCount

        processUartMessages()
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
        val serialNumber = probeSerialNumber.toLong(radix = 16).toUInt()
        setPredictionHandler.wait(uart.owner, ProbeBleDevice.MESSAGE_RESPONSE_TIMEOUT_MS, callback)
        sendUartRequest(NodeSetPredictionRequest(serialNumber, setPointTemperatureC, mode))
    }

    override fun sendLogRequest(minSequence: UInt, maxSequence: UInt) {
        // see ProbeUartBleDevice
        TODO()
    }

    override suspend fun readSerialNumber() = uart.readSerialNumber()
    override suspend fun readFirmwareVersion() = uart.readFirmwareVersion()
    override suspend fun readHardwareRevision() = uart.readHardwareRevision()

    suspend fun readProbeSerialNumber() {
        NOT_IMPLEMENTED("Not able to read probe firmware serial number over meatnet")
    }

    suspend fun readProbeFirmwareVersion() {
        NOT_IMPLEMENTED("Not able to read probe firmware version over meatnet")
    }

    suspend fun readProbeHardwareRevision() {
        NOT_IMPLEMENTED("Not able to read probe hardware rev over meatnet")
    }

    override fun observeAdvertisingPackets(serialNumberFilter: String, macFilter: String, callback: (suspend (advertisement: CombustionAdvertisingData) -> Unit)?) {
        uart.observeAdvertisingPackets(
            { advertisement ->  macFilter == advertisement.mac && advertisement.probeSerialNumber == serialNumberFilter }
        ) { advertisement ->
            callback?.let {
                advertisementForProbe[probeSerialNumber] = advertisement
                _hopCount = advertisement.hopCount
                it(advertisement)
            }
        }
    }

    override fun observeRemoteRssi(callback: (suspend (rssi: Int) -> Unit)?) = uart.observeRemoteRssi(callback)
    override fun observeOutOfRange(timeout: Long, callback: (suspend () -> Unit)?) = uart.observeOutOfRange(timeout, callback)
    override fun observeConnectionState(callback: (suspend (newConnectionState: DeviceConnectionState) -> Unit)?) = uart.observeConnectionState(callback)

    override fun observeProbeStatusUpdates(callback: (suspend (status: ProbeStatus) -> Unit)?) {
        probeStatusCallback = callback
    }

    private fun observeUartMessages(callback: (suspend (responses: List<NodeUARTMessage>) -> Unit)? = null) {
        uart.jobManager.addJob(uart.owner.lifecycleScope.launch {
            uart.observeUartCharacteristic { data ->
                callback?.let {
                    it(NodeUARTMessage.fromData(data.toUByteArray()))
                }
            }
        })
    }

    private fun sendUartRequest(request: NodeRequest) {
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

    private suspend fun handleProbeStatusRequest(message: NodeProbeStatusRequest) {
        // Check that this probe status matches this probe serial number
        if(message.serialNumberString == probeSerialNumber) {

            // Save hop count from probeStatus
            _hopCount = message.hopCount.hopCount

            probeStatusCallback?.let {
                it(message.probeStatus)
            }
        }
    }

    private fun processUartMessages() {
        observeUartMessages { messages ->
            for (message in messages) {
                when (message) {
//                    is LogResponse -> {
//                        mutableLogResponseFlow.emit(response)
//                    }
//                    is SessionInfoResponse -> sessionInfoHandler.handled(response.success, response.sessionInformation)
//                    is SetColorResponse -> setColorHandler.handled(response.success, null)
//                    is SetIDResponse -> setIdHandler.handled(response.success, null)
                    is NodeSetPredictionResponse -> setPredictionHandler.handled(message.success, null)
                    is NodeProbeStatusRequest -> handleProbeStatusRequest(message)
                }
            }
        }
    }
}
