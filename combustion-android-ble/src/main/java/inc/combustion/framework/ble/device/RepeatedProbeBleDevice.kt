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
import inc.combustion.framework.ble.IdleMonitor
import inc.combustion.framework.ble.NOT_IMPLEMENTED
import inc.combustion.framework.ble.ProbeStatus
import inc.combustion.framework.ble.scanning.CombustionAdvertisingData
import inc.combustion.framework.ble.uart.LogResponse
import inc.combustion.framework.ble.uart.meatnet.*
import inc.combustion.framework.ble.uart.meatnet.NodeProbeStatusRequest
import inc.combustion.framework.ble.uart.meatnet.NodeRequest
import inc.combustion.framework.ble.uart.meatnet.NodeSetPredictionResponse
import inc.combustion.framework.ble.uart.meatnet.NodeUARTMessage
import inc.combustion.framework.ble.uart.meatnet.NodeReadSessionInfoRequest
import inc.combustion.framework.service.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class RepeatedProbeBleDevice (
    override val probeSerialNumber: String,
    private val uart: UartBleDevice,
    advertisement: CombustionAdvertisingData,
) : ProbeBleDeviceBase() {

    private val advertisementForProbe = hashMapOf<String, CombustionAdvertisingData>()

    private var probeStatusCallback: (suspend (status: ProbeStatus) -> Unit)? = null
    private var logResponseCallback: (suspend (LogResponse) -> Unit)? = null

    private var _deviceInfoSerialNumber: String? = null
    private var _deviceInfoFirmwareVersion: FirmwareVersion? = null
    private var _deviceInfoHardwareRevision: String? = null
    private var _deviceInfoModelInformation: ModelInformation? = null

    // activate this monitor whenever the link to the probe is used, except for session info
    private val routeMonitor = IdleMonitor()

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
    override val isConnected: Boolean get() { return uart.isConnected.get() }
    override val isDisconnected: Boolean get() { return uart.isDisconnected.get() }
    override val isInRange: Boolean get() { return uart.isInRange.get() }
    override val isConnectable: Boolean get() { return uart.isConnectable.get() }

    // repeater connection state
    private var routeIsAvailable = true
    private var _connectionState: DeviceConnectionState = uart.connectionState
    override val connectionState: DeviceConnectionState
        get() {
            return when(_connectionState) {
                DeviceConnectionState.CONNECTED -> if(routeIsAvailable) _connectionState else DeviceConnectionState.NO_ROUTE
                else -> _connectionState
            }
        }

    // device information service values from the repeated probe's node.
    override val deviceInfoSerialNumber: String? get() { return _deviceInfoSerialNumber }
    override val deviceInfoFirmwareVersion: FirmwareVersion? get() { return _deviceInfoFirmwareVersion }
    override val deviceInfoHardwareRevision: String? get() { return _deviceInfoHardwareRevision }
    override val deviceInfoModelInformation: ModelInformation? get() { return _deviceInfoModelInformation }

    override val productType: CombustionProductType get() { return uart.productType}

    override var isInDfuMode: Boolean
        get() = uart.isInDfuMode
        set(value) { uart.isInDfuMode = value }

    private var _hopCount: UInt = advertisement.hopCount
    override val hopCount: UInt
        get() {
            return _hopCount
        }

    val probeMac: String get() { TODO() }
    val probeRssi: Int get() { TODO() }
    val probeConnectionState: DeviceConnectionState get() { TODO() }
    val probeIsConnected: Boolean get() { TODO() }

    // message completion handlers
    private val probeSerialNumberHandler = UartBleDevice.MessageCompletionHandler()
    private val probeFirmwareRevisionHandler = UartBleDevice.MessageCompletionHandler()
    private val probeHardwareRevisionHandler = UartBleDevice.MessageCompletionHandler()
    private val probeModelInfoHandler = UartBleDevice.MessageCompletionHandler()

    // base connection state callback
    private var connectionStateCallback: (suspend (newConnectionState: DeviceConnectionState) -> Unit)? = null

    init {
        advertisementForProbe[advertisement.probeSerialNumber] = advertisement

        processUartMessages()
        monitorMeatNetRoute()
    }

    override fun connect() = uart.connect()
    override fun disconnect() = uart.disconnect()

    override fun sendSessionInformationRequest(callback: ((Boolean, Any?) -> Unit)?)  {
        sessionInfoHandler.wait(uart.owner, MESSAGE_RESPONSE_TIMEOUT_MS, callback)
        sendUartRequest(NodeReadSessionInfoRequest(probeSerialNumber))
    }

    override fun sendSetProbeColor(color: ProbeColor, callback: ((Boolean, Any?) -> Unit)?) {
        routeMonitor.activity()
        NOT_IMPLEMENTED("Not able to set probe color over MeatNet")
    }

    override fun sendSetProbeID(id: ProbeID, callback: ((Boolean, Any?) -> Unit)?) {
        routeMonitor.activity()
        NOT_IMPLEMENTED("Not able to set probe ID over MeatNet")
    }

    override fun sendSetPrediction(setPointTemperatureC: Double, mode: ProbePredictionMode, callback: ((Boolean, Any?) -> Unit)?) {
        routeMonitor.activity()
        setPredictionHandler.wait(uart.owner, MESSAGE_RESPONSE_TIMEOUT_MS, callback)
        sendUartRequest(NodeSetPredictionRequest(probeSerialNumber, setPointTemperatureC, mode))
    }

    override fun sendLogRequest(minSequence: UInt, maxSequence: UInt, callback: (suspend (LogResponse) -> Unit)?) {
        routeMonitor.activity()
        logResponseCallback = callback
        sendUartRequest(NodeReadLogsRequest(probeSerialNumber, minSequence, maxSequence))
    }

    override suspend fun readSerialNumber() = uart.readSerialNumber()
    override suspend fun readFirmwareVersion() = uart.readFirmwareVersion()
    override suspend fun readHardwareRevision() = uart.readHardwareRevision()
    override suspend fun readModelInformation() = uart.readModelInformation()

    suspend fun readProbeFirmwareVersion() {
        val channel = Channel<Unit>(0)
        routeMonitor.activity()
        probeFirmwareRevisionHandler.wait(uart.owner, MESSAGE_RESPONSE_TIMEOUT_MS) { success, response ->
            if (success) {
                val resp = response as NodeReadFirmwareRevisionResponse
                _deviceInfoFirmwareVersion = FirmwareVersion.fromString(resp.firmwareRevision)
                Log.d(LOG_TAG, "MeatNet: readProbeFirmwareVersion: $_deviceInfoFirmwareVersion")
            }
            uart.owner.lifecycleScope.launch {
                channel.send(Unit)
            }
        }
        sendUartRequest(NodeReadFirmwareRevisionRequest(probeSerialNumber))
        channel.receive()
    }

    suspend fun readProbeHardwareRevision() {
        val channel = Channel<Unit>(0)
        routeMonitor.activity()
        probeHardwareRevisionHandler.wait(uart.owner, MESSAGE_RESPONSE_TIMEOUT_MS) { success, response ->
            if (success) {
                val resp = response as NodeReadHardwareRevisionResponse
                _deviceInfoHardwareRevision = resp.hardwareRevision
                Log.d(LOG_TAG, "MeatNet: readProbeHardwareRevision: $_deviceInfoHardwareRevision")
            }
            uart.owner.lifecycleScope.launch {
                channel.send(Unit)
            }
        }
        sendUartRequest(NodeReadHardwareRevisionRequest(probeSerialNumber))
        channel.receive()
    }

    suspend fun readProbeModelInformation() {
        val channel = Channel<Unit>(0)
        routeMonitor.activity()
        probeModelInfoHandler.wait(uart.owner, MESSAGE_RESPONSE_TIMEOUT_MS) { success, response ->
            if (success) {
                val resp = response as NodeReadModelInfoResponse
                _deviceInfoModelInformation = resp.modelInfo
                Log.d(LOG_TAG, "MeatNet: readProbeModelInformation: ${resp.modelInfo.sku} ${resp.modelInfo.manufacturingLot}")
            }
            uart.owner.lifecycleScope.launch {
                channel.send(Unit)
            }
        }
        sendUartRequest(NodeReadModelInfoRequest(probeSerialNumber))
        channel.receive()
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

    override fun observeConnectionState(callback: (suspend (newConnectionState: DeviceConnectionState) -> Unit)?) {
        connectionStateCallback = callback
        uart.observeConnectionState(this::baseConnectionStateHandler)
    }

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
        if (DebugSettings.DEBUG_LOG_BLE_UART_IO) {
            val packet = request.data.joinToString("") {
                it.toString(16).padStart(2, '0').uppercase()
            }
            Log.d(LOG_TAG, "UART-TX: $packet")
        }

        uart.writeUartCharacteristic(request.sData)
    }

    private suspend fun handleProbeStatusRequest(message: NodeProbeStatusRequest) {
        // Save hop count from probeStatus
        _hopCount = message.hopCount.hopCount

        probeStatusCallback?.let {
            it(message.probeStatus)
        }
    }

    private suspend fun handleLogResponse(message: NodeReadLogsResponse) {
        // Send log response to callback
        logResponseCallback?.let {
            it( LogResponse(
                message.sequenceNumber,
                message.temperatures,
                message.predictionLog,
                message.success,
                message.payloadLength.toUInt())
            )
        }
    }

    private fun processUartMessages() {
        observeUartMessages { messages ->
            for (message in messages) {
                when (message) {
                    // Unsupported Messages
                    // is NodeSetColorResponse -> setColorHandler.handled(response.success, null)
                    // is NodeSetIDResponse -> setIdHandler.handled(response.success, null)

                    // Repeated Responses
                    is NodeReadLogsResponse -> {
                        if(message.serialNumber == probeSerialNumber) {
                            routeMonitor.activity()
                            handleLogResponse(message)
                        }
                    }

                    // Synchronous Requests that are responded to with a single message
                    is NodeSetPredictionResponse -> {
                        if(message.serialNumber == probeSerialNumber) {
                            routeMonitor.activity()
                            setPredictionHandler.handled(message.success, null)
                        }
                    }
                    is NodeReadFirmwareRevisionResponse -> {
                        if(message.serialNumber == probeSerialNumber) {
                            routeMonitor.activity()
                            probeFirmwareRevisionHandler.handled(message.success, message)
                        }
                    }
                    is NodeReadHardwareRevisionResponse -> {
                        if(message.serialNumber == probeSerialNumber) {
                            routeMonitor.activity()
                            probeHardwareRevisionHandler.handled(message.success, message)
                        }
                    }
                    is NodeReadModelInfoResponse -> {
                        if(message.serialNumber == probeSerialNumber) {
                            routeMonitor.activity()
                            probeModelInfoHandler.handled(message.success, message)
                        }
                    }

                    /// Async Requests that are Broadcast on certain events from a Node
                    is NodeProbeStatusRequest -> {
                        if(message.serialNumber == probeSerialNumber) {
                            handleProbeStatusRequest(message)
                        }
                    }
                    is NodeReadSessionInfoResponse -> {
                        if(message.serialNumber == probeSerialNumber) {
                            sessionInfoHandler.handled(message.success, message.sessionInformation)
                        }
                    }
                    is NodeHeartbeatRequest -> {
                        // Heartbeat message not processed
                    }
                }
            }
        }
    }

    private fun monitorMeatNetRoute() {
        uart.jobManager.addJob(uart.owner.lifecycleScope.launch {
            var pingTimeoutCount = 0
            val channel = Channel<Unit>(0)

            // settling time until we start pinging the route
            delay(PING_SETTLING_MS)
            routeMonitor.activity()

            // until this coroutine is cancelled
            while(isActive) {
                // delay to poll again on next period
                delay(PING_RATE_MS)

                // if we are connected to the repeater, and the link has been idle for sufficient time.
                if(uart.connectionState == DeviceConnectionState.CONNECTED && routeMonitor.isIdle(IDLE_LINK_TIMEOUT)) {
                    val state = connectionState

                    // send session information request to ping the endpoint and determine if there is a route
                    sendSessionInformationRequest { status, _ ->
                        // count consecutive ping timeouts
                        pingTimeoutCount = if(status) 0 else pingTimeoutCount + 1

                        // keep track of the status of the ping
                        routeIsAvailable = pingTimeoutCount <= PING_TIMEOUT_COUNT

                        // use channel to signal that this response handler block is done
                        uart.owner.lifecycleScope.launch { channel.send(Unit) }
                    }

                    // block this polling coroutine until the response handler is done.
                    channel.receive()

                    // if the link is still idle
                    if(routeMonitor.isIdle(IDLE_LINK_TIMEOUT)) {

                        // if we aren't able to ping, then event up that there is no route to the probe.
                        if((state == DeviceConnectionState.NO_ROUTE || connectionState == DeviceConnectionState.NO_ROUTE) && state != connectionState) {

                            connectionStateCallback?.let {
                                Log.w(LOG_TAG, "Ping[$probeSerialNumber]: Connection Is $connectionState (Repeater is $_connectionState)")
                                uart.owner.lifecycleScope.launch {
                                    it(connectionState)
                                }
                            }
                        }
                    }
                }
            }
        })
    }

    private suspend fun baseConnectionStateHandler(newConnectionState: DeviceConnectionState) {
        _connectionState = newConnectionState
        connectionStateCallback?.let { it(connectionState) }
    }

    companion object {
        const val PING_RATE_MS = 1000L
        const val PING_SETTLING_MS = 10000L
        const val IDLE_LINK_TIMEOUT = MESSAGE_RESPONSE_TIMEOUT_MS + 3000L
        const val PING_TIMEOUT_COUNT = 3
    }
}
