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
import inc.combustion.framework.ble.device.UartCapableProbe.Companion.MEATNET_MESSAGE_RESPONSE_TIMEOUT_MS
import inc.combustion.framework.ble.device.UartCapableProbe.Companion.makeLinkId
import inc.combustion.framework.ble.scanning.DeviceAdvertisingData
import inc.combustion.framework.ble.scanning.ProbeAdvertisingData
import inc.combustion.framework.ble.uart.ProbeLogResponse
import inc.combustion.framework.ble.uart.meatnet.NodeConfigureFoodSafeRequest
import inc.combustion.framework.ble.uart.meatnet.NodeConfigureFoodSafeResponse
import inc.combustion.framework.ble.uart.meatnet.NodeGaugeStatusRequest
import inc.combustion.framework.ble.uart.meatnet.NodeHeartbeatRequest
import inc.combustion.framework.ble.uart.meatnet.NodeMessageType
import inc.combustion.framework.ble.uart.meatnet.NodeProbeStatusRequest
import inc.combustion.framework.ble.uart.meatnet.NodeReadFirmwareRevisionRequest
import inc.combustion.framework.ble.uart.meatnet.NodeReadFirmwareRevisionResponse
import inc.combustion.framework.ble.uart.meatnet.NodeReadHardwareRevisionRequest
import inc.combustion.framework.ble.uart.meatnet.NodeReadHardwareRevisionResponse
import inc.combustion.framework.ble.uart.meatnet.NodeReadProbeLogsRequest
import inc.combustion.framework.ble.uart.meatnet.NodeReadProbeLogsResponse
import inc.combustion.framework.ble.uart.meatnet.NodeReadModelInfoRequest
import inc.combustion.framework.ble.uart.meatnet.NodeReadModelInfoResponse
import inc.combustion.framework.ble.uart.meatnet.NodeReadSessionInfoRequest
import inc.combustion.framework.ble.uart.meatnet.NodeReadSessionInfoResponse
import inc.combustion.framework.ble.uart.meatnet.NodeRequest
import inc.combustion.framework.ble.uart.meatnet.NodeResetFoodSafeRequest
import inc.combustion.framework.ble.uart.meatnet.NodeResetFoodSafeResponse
import inc.combustion.framework.ble.uart.meatnet.NodeResetProbeRequest
import inc.combustion.framework.ble.uart.meatnet.NodeResetProbeResponse
import inc.combustion.framework.ble.uart.meatnet.NodeResponse
import inc.combustion.framework.ble.uart.meatnet.NodeSetPowerModeRequest
import inc.combustion.framework.ble.uart.meatnet.NodeSetPowerModeResponse
import inc.combustion.framework.ble.uart.meatnet.NodeSetPredictionRequest
import inc.combustion.framework.ble.uart.meatnet.NodeSetPredictionResponse
import inc.combustion.framework.ble.uart.meatnet.NodeUARTMessage
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.DeviceConnectionState
import inc.combustion.framework.service.FirmwareVersion
import inc.combustion.framework.service.FoodSafeData
import inc.combustion.framework.service.ModelInformation
import inc.combustion.framework.service.ProbeColor
import inc.combustion.framework.service.ProbeID
import inc.combustion.framework.service.ProbePowerMode
import inc.combustion.framework.service.ProbePredictionMode
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Class representing a probe connected through one or more MeatNet nodes.
 *
 * [serialNumber] indicates the serial number of the probe. [advertisement] is the initial
 * advertisement coming from a node that contains the probe's information. [uart] is the interface
 * for sending and receiving UART messages to and from the probe.
 */
internal class RepeatedProbeBleDevice(
    override val serialNumber: String,
    private val nodeDevice: NodeBleDevice,
    advertisement: ProbeAdvertisingData,
) : ProbeBleDeviceBase() {

    private val uart: UartBleDevice = nodeDevice.getDevice()
    private val hybridDeviceChild: NodeHybridDevice?
        get() = nodeDevice.hybridDeviceChild

    val isHybridDevice: Boolean
        get() = hybridDeviceChild != null

    // TODO: Why is this a map? I only see probeSerialNumber being set here.
    private val advertisementForProbe = hashMapOf<String, ProbeAdvertisingData>()

    private var probeStatusCallback: (suspend (status: ProbeStatus, hopCount: UInt?) -> Unit)? =
        null
    private var logResponseCallback: (suspend (ProbeLogResponse) -> Unit)? = null

    private var _deviceInfoSerialNumber: String? = null
    private var _deviceInfoFirmwareVersion: FirmwareVersion? = null
    private var _deviceInfoHardwareRevision: String? = null
    private var _deviceInfoModelInformation: ModelInformation? = null

    override val advertisement: ProbeAdvertisingData?
        get() {
            return advertisementForProbe[serialNumber]
        }

    override val linkId: LinkID
        get() {
            return makeLinkId(advertisement)
        }

    override val id: DeviceID
        get() {
            return uart.id
        }
    override val isSimulated: Boolean = false

    override val mac: String
        get() {
            return uart.mac
        }

    // ble properties
    override val rssi: Int
        get() {
            return uart.rssi
        }
    override val isConnected: Boolean
        get() {
            return uart.isConnected.get()
        }
    override val isDisconnected: Boolean
        get() {
            return uart.isDisconnected.get()
        }
    override val isInRange: Boolean
        get() {
            return uart.isInRange.get()
        }
    override val isConnectable: Boolean
        get() {
            return uart.isConnectable.get()
        }

    // repeater connection state
    private var routeIsAvailable = true
    private var _connectionState: DeviceConnectionState = uart.connectionState
    override val connectionState: DeviceConnectionState
        get() {
            return when (_connectionState) {
                DeviceConnectionState.CONNECTED -> if (routeIsAvailable) _connectionState else DeviceConnectionState.NO_ROUTE
                else -> _connectionState
            }
        }

    // device information service values from the repeated probe's node.
    override val deviceInfoSerialNumber: String?
        get() {
            return _deviceInfoSerialNumber
        }
    override val deviceInfoFirmwareVersion: FirmwareVersion?
        get() {
            return _deviceInfoFirmwareVersion
        }
    override val deviceInfoHardwareRevision: String?
        get() {
            return _deviceInfoHardwareRevision
        }
    override val deviceInfoModelInformation: ModelInformation?
        get() {
            return _deviceInfoModelInformation
        }

    override val productType: CombustionProductType
        get() {
            return uart.productType
        }
    override val isRepeater: Boolean = true
    override var shouldAutoReconnect: Boolean
        get() = TODO("Not yet implemented")
        set(value) {}

    override var isInDfuMode: Boolean
        get() = uart.isInDfuMode
        set(value) {
            uart.isInDfuMode = value
        }

    private var _hopCount: UInt = advertisement.hopCount
    override val hopCount: UInt
        get() {
            return _hopCount
        }

    val probeMac: String
        get() {
            TODO()
        }
    val probeRssi: Int
        get() {
            TODO()
        }
    val probeConnectionState: DeviceConnectionState
        get() {
            TODO()
        }
    val probeIsConnected: Boolean
        get() {
            TODO()
        }

    // message completion handlers
    private val probeFirmwareRevisionHandler = UartBleDevice.MessageCompletionHandler()
    private val probeHardwareRevisionHandler = UartBleDevice.MessageCompletionHandler()
    private val probeModelInfoHandler = UartBleDevice.MessageCompletionHandler()

    // base connection state callback
    private var connectionStateCallback: (suspend (newConnectionState: DeviceConnectionState) -> Unit)? =
        null

    private val meatNetNodeTimeoutMonitor = IdleMonitor()

    init {
        advertisementForProbe[advertisement.serialNumber] = advertisement
        processUartMessages()
    }

    override fun connect() {
        uart.connect()
    }

    override fun disconnect() {
        if (!isHybridDevice) {
            uart.disconnect()
        }
    }

    override fun sendSetProbeColor(color: ProbeColor, callback: ((Boolean, Any?) -> Unit)?) {
        NOT_IMPLEMENTED("Not able to set probe color over MeatNet")
    }

    override fun sendSetProbeID(id: ProbeID, callback: ((Boolean, Any?) -> Unit)?) {
        NOT_IMPLEMENTED("Not able to set probe ID over MeatNet")
    }

    override fun sendSessionInformationRequest(reqId: UInt?, callback: ((Boolean, Any?) -> Unit)?) {
        if (!sessionInfoHandler.isWaiting) {
            // arm the handler if we are not yet waiting for the response
            sessionInfoHandler.wait(
                uart.owner,
                MEATNET_MESSAGE_RESPONSE_TIMEOUT_MS,
                reqId,
                callback
            )
        }

        // keep sending the request (using the same request ID) until the message is handled or it
        // exceeds the timeout.
        sendUartRequest(NodeReadSessionInfoRequest(serialNumber, sessionInfoHandler.requestId))
    }

    fun cancelSessionInfoRequest(reqId: UInt) {
        if (sessionInfoHandler.isWaiting && sessionInfoHandler.requestId == reqId) {
            sessionInfoHandler.cancel()
        }
    }

    override fun sendSetPrediction(
        setPointTemperatureC: Double,
        mode: ProbePredictionMode,
        reqId: UInt?,
        callback: ((Boolean, Any?) -> Unit)?
    ) {
        setPredictionHandler.wait(uart.owner, MEATNET_MESSAGE_RESPONSE_TIMEOUT_MS, reqId, callback)
        sendUartRequest(NodeSetPredictionRequest(serialNumber, setPointTemperatureC, mode, reqId))
    }

    override fun sendConfigureFoodSafe(
        foodSafeData: FoodSafeData,
        reqId: UInt?,
        callback: ((Boolean, Any?) -> Unit)?
    ) {
        configureFoodSafeHandler.wait(
            uart.owner,
            MEATNET_MESSAGE_RESPONSE_TIMEOUT_MS,
            reqId,
            callback
        )
        sendUartRequest(NodeConfigureFoodSafeRequest(serialNumber, foodSafeData, reqId))
    }

    override fun sendResetFoodSafe(reqId: UInt?, callback: ((Boolean, Any?) -> Unit)?) {
        resetFoodSafeHandler.wait(uart.owner, MEATNET_MESSAGE_RESPONSE_TIMEOUT_MS, reqId, callback)
        sendUartRequest(NodeResetFoodSafeRequest(serialNumber, reqId))
    }

    override fun sendLogRequest(
        minSequence: UInt,
        maxSequence: UInt,
        callback: (suspend (ProbeLogResponse) -> Unit)?
    ) {
        logResponseCallback = callback
        sendUartRequest(NodeReadProbeLogsRequest(serialNumber, minSequence, maxSequence))
    }

    override fun sendSetPowerMode(
        powerMode: ProbePowerMode,
        reqId: UInt?,
        callback: ((Boolean, Any?) -> Unit)?,
    ) {
        setPowerModeHandler.wait(uart.owner, MEATNET_MESSAGE_RESPONSE_TIMEOUT_MS, reqId, callback)
        sendUartRequest(NodeSetPowerModeRequest(serialNumber, powerMode, reqId))
    }

    override fun sendResetProbe(reqId: UInt?, callback: ((Boolean, Any?) -> Unit)?) {
        resetProbeHandler.wait(uart.owner, MEATNET_MESSAGE_RESPONSE_TIMEOUT_MS, reqId, callback)
        sendUartRequest(NodeResetProbeRequest(serialNumber, reqId))
    }

    override suspend fun readSerialNumber() = uart.readSerialNumber()
    override suspend fun readFirmwareVersion() = uart.readFirmwareVersion()
    override suspend fun readHardwareRevision() = uart.readHardwareRevision()
    override suspend fun readModelInformation() = uart.readModelInformation()

    override fun readFirmwareVersionAsync(callback: (FirmwareVersion) -> Unit) {
        uart.owner.lifecycleScope.launch {
            readFirmwareVersion()
        }.invokeOnCompletion {
            deviceInfoFirmwareVersion?.let {
                callback(it)
            }
        }
    }

    override fun readHardwareRevisionAsync(callback: (String) -> Unit) {
        uart.owner.lifecycleScope.launch {
            readHardwareRevision()
        }.invokeOnCompletion {
            deviceInfoHardwareRevision?.let {
                callback(it)
            }
        }
    }

    override fun readModelInformationAsync(callback: (ModelInformation) -> Unit) {
        uart.owner.lifecycleScope.launch {
            readModelInformation()
        }.invokeOnCompletion {
            deviceInfoModelInformation?.let {
                callback(it)
            }
        }
    }

    override fun toString(): String {
        return "repeated$productType(${super.toString()})"
    }

    fun readProbeFirmwareVersion(reqId: UInt?, callback: (FirmwareVersion) -> Unit) {
        if (!probeFirmwareRevisionHandler.isWaiting) {
            probeFirmwareRevisionHandler.wait(
                uart.owner,
                MEATNET_MESSAGE_RESPONSE_TIMEOUT_MS,
                reqId
            ) { success, response ->
                if (success) {
                    val resp = response as NodeReadFirmwareRevisionResponse
                    val version = FirmwareVersion.fromString(resp.firmwareRevision)
                    _deviceInfoFirmwareVersion = version
                    callback(version)
                }
            }
        }
        sendUartRequest(
            NodeReadFirmwareRevisionRequest(
                serialNumber,
                probeFirmwareRevisionHandler.requestId
            )
        )
    }

    fun readProbeHardwareRevision(reqId: UInt?, callback: (String) -> Unit) {
        if (!probeHardwareRevisionHandler.isWaiting) {
            probeHardwareRevisionHandler.wait(
                uart.owner,
                MEATNET_MESSAGE_RESPONSE_TIMEOUT_MS,
                reqId
            ) { success, response ->
                if (success) {
                    val resp = response as NodeReadHardwareRevisionResponse
                    val version = resp.hardwareRevision
                    _deviceInfoHardwareRevision = version
                    callback(version)
                }
            }
        }
        sendUartRequest(
            NodeReadHardwareRevisionRequest(
                serialNumber,
                probeHardwareRevisionHandler.requestId
            )
        )
    }

    fun readProbeModelInformation(reqId: UInt?, callback: (ModelInformation) -> Unit) {
        if (!probeModelInfoHandler.isWaiting) {
            probeModelInfoHandler.wait(
                uart.owner,
                MEATNET_MESSAGE_RESPONSE_TIMEOUT_MS,
                reqId
            ) { success, response ->
                if (success) {
                    val resp = response as NodeReadModelInfoResponse
                    val info = resp.modelInfo
                    _deviceInfoModelInformation = info
                    callback(info)
                }
            }
        }
        sendUartRequest(NodeReadModelInfoRequest(serialNumber, probeModelInfoHandler.requestId))
    }

    override fun observeAdvertisingPackets(
        serialNumberFilter: String,
        macFilter: String,
        callback: (suspend (advertisement: DeviceAdvertisingData) -> Unit)?
    ) {
        uart.observeAdvertisingPackets(
            jobKey = serialNumberFilter,
            filter = { advertisement ->
                macFilter == advertisement.mac && advertisement.serialNumber == serialNumberFilter
            }
        ) { advertisement ->
            if (advertisement is ProbeAdvertisingData) {
                callback?.let {
                    advertisementForProbe[serialNumber] = advertisement
                    _hopCount = advertisement.hopCount
                    it(advertisement)
                }
            }
        }
    }

    override fun observeRemoteRssi(callback: (suspend (rssi: Int) -> Unit)?) =
        uart.observeRemoteRssi(callback)

    override fun observeOutOfRange(timeout: Long, callback: (suspend () -> Unit)?) =
        uart.observeOutOfRange(timeout, callback)

    override fun observeConnectionState(callback: (suspend (newConnectionState: DeviceConnectionState) -> Unit)?) {
        connectionStateCallback = callback
        uart.observeConnectionState(this::baseConnectionStateHandler)
    }

    override fun observeProbeStatusUpdates(
        hopCount: UInt?,
        callback: (suspend (status: ProbeStatus, hopCount: UInt?) -> Unit)?
    ) {
        probeStatusCallback = callback
    }

    /**
     * Check for MeatNet node timeouts. There's a situation that we can enter where:
     * - The probe is connected to a MeatNet node
     * - The node is no longer scanning because it reached its two-minute timeout
     * - The probe disconnects from the node
     * - At this point, if the probe is connectable again (either through powering back on or
     *   entering BLE range of the node), the node will not be scanning for the probe and won't find
     *   it.
     *
     * In this case, we wait [timeout] ms to see if the probe reconnects to the node. If it doesn't,
     * we assume the probe is no longer connected to MeatNet, and we mark this route as unavailable.
     * When the probe enters range of the app again, the app will make a direct connection to the
     * probe.
     */
    fun observeMeatNetNodeTimeout(timeout: Long) {
        uart.jobManager.addJob(
            key = serialNumber,
            job = uart.owner.lifecycleScope.launch(
                CoroutineName("${serialNumber}.observeMeatNetNodeTimeout")
            ) {
                while (isActive) {
                    delay(IDLE_POLL_RATE_MS)

                    routeIsAvailable = !meatNetNodeTimeoutMonitor.isIdle(timeout)
                }
            }
        )
    }

    private fun observeUartMessages(callback: (suspend (responses: List<NodeUARTMessage>) -> Unit)? = null) {
        uart.jobManager.addJob(
            key = serialNumber,
            job = uart.owner.lifecycleScope.launch(
                CoroutineName("${serialNumber}.observeUartMessages")
            ) {
                uart.observeUartCharacteristic { data ->

                    callback?.let {
                        val message = NodeUARTMessage.fromData(data.toUByteArray())

                        if (INFO_LOG_MEATNET_TRACE && INFO_LOG_MEATNET_UART_TRACE) {
                            message.forEach { uartMessage ->
                                MEATNET_TRACE_INCLUSION_FILTER.firstOrNull { it == uartMessage.messageId }
                                    ?.let {
                                        val packet = data.joinToString("") {
                                            it.toString(16).padStart(2, '0').uppercase()
                                        }
                                        Log.i(LOG_TAG, "UART-RX: $packet")
                                    }
                            }
                        }

                        it(message)
                    }
                }
            }
        )
    }

    private fun sendUartRequest(request: NodeRequest) {
        if (INFO_LOG_MEATNET_TRACE) {
            MEATNET_TRACE_INCLUSION_FILTER.firstOrNull { it == request.messageId }?.let {
                Log.i(LOG_TAG + "_MEATNET", "$serialNumber: TX Node $id $request")

                if (INFO_LOG_MEATNET_UART_TRACE) {
                    val packet = request.data.joinToString("") {
                        it.toString(16).padStart(2, '0').uppercase()
                    }
                    Log.i(LOG_TAG + "_MEATNET", "UART-TX: $packet")
                }
            }
        }
        uart.writeUartCharacteristic(request.sData)
    }

    private suspend fun handleProbeStatusRequest(message: NodeProbeStatusRequest) {
        // Save hop count from probeStatus
        _hopCount = message.hopCount.hopCount

        // Check for meatnet timeouts
        meatNetNodeTimeoutMonitor.activity()

        probeStatusCallback?.let {
            it(message.probeStatus, message.hopCount.hopCount)
        }
    }

    private suspend fun handleLogResponse(message: NodeReadProbeLogsResponse) {
        // Send log response to callback
        logResponseCallback?.let {
            it(
                ProbeLogResponse(
                    message.sequenceNumber,
                    message.temperatures,
                    message.predictionLog,
                    message.success,
                    message.payloadLength.toUInt()
                )
            )
        }
    }

    private fun processUartMessages() {
        observeUartMessages { messages ->
            for (message in messages) {
                if (INFO_LOG_MEATNET_TRACE) {
                    MEATNET_TRACE_INCLUSION_FILTER.firstOrNull { it == message.messageId }?.let {
                        when (message) {
                            is NodeRequest -> Log.i(
                                LOG_TAG + "_MEATNET",
                                "$serialNumber: RX Node $id $message"
                            )

                            is NodeResponse -> Log.i(
                                LOG_TAG + "_MEATNET",
                                "$serialNumber: RX Node $id $message"
                            )

                            else -> {}
                        }
                    }
                }

                when (message) {
                    // Repeated Responses
                    is NodeReadProbeLogsResponse -> {
                        if (message.serialNumber == serialNumber) {
                            handleLogResponse(message)
                        }
                    }

                    // Synchronous Requests that are responded to with a single message
                    is NodeSetPredictionResponse -> {
                        setPredictionHandler.handled(message.success, null, message.requestId)
                    }

                    is NodeConfigureFoodSafeResponse -> {
                        configureFoodSafeHandler.handled(message.success, null, message.requestId)
                    }

                    is NodeResetFoodSafeResponse -> {
                        resetFoodSafeHandler.handled(message.success, null, message.requestId)
                    }

                    is NodeSetPowerModeResponse -> {
                        setPowerModeHandler.handled(message.success, null, message.requestId)
                    }

                    is NodeResetProbeResponse -> {
                        resetProbeHandler.handled(message.success, null, message.requestId)
                    }

                    is NodeReadFirmwareRevisionResponse -> {
                        probeFirmwareRevisionHandler.handled(
                            message.success,
                            message,
                            message.requestId
                        )
                    }

                    is NodeReadHardwareRevisionResponse -> {
                        probeHardwareRevisionHandler.handled(
                            message.success,
                            message,
                            message.requestId
                        )
                    }

                    is NodeReadModelInfoResponse -> {
                        probeModelInfoHandler.handled(message.success, message, message.requestId)
                    }

                    is NodeReadSessionInfoResponse -> {
                        sessionInfoHandler.handled(
                            message.success,
                            message.sessionInformation,
                            message.requestId
                        )
                    }

                    /// Async Requests that are Broadcast on certain events from a Node
                    is NodeProbeStatusRequest -> {
                        if (message.serialNumber == serialNumber) {
                            handleProbeStatusRequest(message)
                        }
                    }

                    is NodeHeartbeatRequest -> {
                        // Heartbeat message not processed
                    }

                    is NodeGaugeStatusRequest -> {
                        // Processed in NodeBleDevice
                    }
                }
            }
        }
    }

    private suspend fun baseConnectionStateHandler(newConnectionState: DeviceConnectionState) {
        _connectionState = newConnectionState
        connectionStateCallback?.let { it(connectionState) }
    }

    companion object {
        private const val IDLE_POLL_RATE_MS = 1_000L
        const val INFO_LOG_MEATNET_UART_TRACE = false
        val MEATNET_TRACE_INCLUSION_FILTER = listOf<NodeMessageType>(
            // NodeMessageType.SET_ID,
            // NodeMessageType.SET_COLOR,
            // NodeMessageType.SESSION_INFO,
            // NodeMessageType.LOG,
            // NodeMessageType.SET_PREDICTION,
            // NodeMessageType.READ_OVER_TEMPERATURE,
            // NodeMessageType.CONNECTED,
            // NodeMessageType.DISCONNECTED,
            // NodeMessageType.READ_NODE_LIST,
            // NodeMessageType.READ_NETWORK_TOPOLOGY,
            // NodeMessageType.PROBE_SESSION_CHANGED,
            // NodeMessageType.PROBE_STATUS,
            // NodeMessageType.PROBE_FIRMWARE_REVISION,
            // NodeMessageType.PROBE_HARDWARE_REVISION,
            // NodeMessageType.PROBE_MODEL_INFORMATION,
            // NodeMessageType.HEARTBEAT,
            // NodeMessageType.RESET_PROBE,
        )
        val INFO_LOG_MEATNET_TRACE = MEATNET_TRACE_INCLUSION_FILTER.isNotEmpty()
    }
}
