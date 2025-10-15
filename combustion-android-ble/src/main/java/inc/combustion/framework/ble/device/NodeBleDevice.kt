package inc.combustion.framework.ble.device

import android.bluetooth.BluetoothAdapter
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.GaugeStatus
import inc.combustion.framework.ble.NetworkManager
import inc.combustion.framework.ble.device.UartCapableProbe.Companion.MEATNET_MESSAGE_RESPONSE_TIMEOUT_MS
import inc.combustion.framework.ble.scanning.DeviceAdvertisingData
import inc.combustion.framework.ble.uart.meatnet.*
import inc.combustion.framework.service.*
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

internal class NodeBleDevice(
    mac: String,
    owner: LifecycleOwner,
    nodeAdvertisingData: DeviceAdvertisingData,
    adapter: BluetoothAdapter,
    private val uart: UartBleDevice = UartBleDevice(mac, nodeAdvertisingData, owner, adapter),
    private val observeGaugeStatusCallback: suspend (String, GaugeStatus) -> Unit,
    private val observeSilenceAlarmsCallback: suspend (SilenceAlarmsRequest) -> Unit,
) : UartCapableDevice {

    override val id: DeviceID
        get() = uart.id

    private val genericRequestHandler = UartBleDevice.MessageCompletionHandler()
    private val readFeatureFlagsRequest = UartBleDevice.MessageCompletionHandler()
    private val setGaugeHighLowAlarmStatusHandler = UartBleDevice.MessageCompletionHandler()
    private val silenceAlarmsHandler = UartBleDevice.MessageCompletionHandler()

    companion object {
        const val NODE_MESSAGE_RESPONSE_TIMEOUT_MS = 120000L
    }

    override val isSimulated: Boolean = false
    override val isRepeater: Boolean = true

    override val rssi: Int
        get() {
            return uart.rssi
        }
    override val connectionState: DeviceConnectionState
        get() {
            return uart.connectionState
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

    val deviceInfoSerialNumber: String?
        get() {
            return uart.serialNumber
        }

    private val disconnectedCallbacks = mutableMapOf<String, () -> Unit>()

    private val logResponseCallbackForSerialNumber =
        mutableMapOf<String, suspend (NodeReadGaugeLogsResponse) -> Unit>()

    override var isInDfuMode: Boolean
        get() = uart.isInDfuMode
        set(value) {
            uart.isInDfuMode = value
        }

    /**
     * Representation of hybrid device's specialized abilities, device such as [GaugeBleDevice]
     */
    var hybridDeviceChild: NodeHybridDevice? = null
        private set

    init {
        processUartMessages()
        processConnectionState()
    }

    fun createAndAssignNodeHybridDevice(create: (NodeBleDevice) -> NodeHybridDevice) {
        this.hybridDeviceChild = create(this)
    }

    fun clearNodeHybridDevice() {
        this.hybridDeviceChild = null
    }

    fun sendNodeRequest(request: GenericNodeRequest, callback: ((Boolean, Any?) -> Unit)?) {
        val nodeRequest = request.toNodeRequest()
        genericRequestHandler.wait(
            uart.owner,
            NODE_MESSAGE_RESPONSE_TIMEOUT_MS,
            nodeRequest.requestId,
            callback,
        )
        sendUartRequest(nodeRequest)
    }

    fun sendFeatureFlagRequest(reqId: UInt?, callback: ((Boolean, Any?) -> Unit)?) {
        val nodeSerialNumber = deviceInfoSerialNumber ?: return
        readFeatureFlagsRequest.wait(uart.owner, NODE_MESSAGE_RESPONSE_TIMEOUT_MS, reqId, callback)
        sendUartRequest(NodeReadFeatureFlagsRequest(nodeSerialNumber, reqId))
    }

    fun sendSetGaugeHighLowAlarmStatus(
        serialNumber: String,
        highLowAlarmStatus: HighLowAlarmStatus,
        reqId: UInt?,
        callback: ((Boolean, Any?) -> Unit)?,
    ) {
        setGaugeHighLowAlarmStatusHandler.wait(
            uart.owner,
            MEATNET_MESSAGE_RESPONSE_TIMEOUT_MS,
            reqId,
            callback,
        )
        sendUartRequest(NodeSetGaugeHighLowAlarmRequest(serialNumber, highLowAlarmStatus, reqId))
    }

    fun sendGaugeLogRequest(
        serialNumber: String,
        minSequence: UInt,
        maxSequence: UInt,
        reqId: UInt?,
        callback: suspend (NodeReadGaugeLogsResponse) -> Unit,
    ) {
        logResponseCallbackForSerialNumber[serialNumber] = callback
        sendUartRequest(NodeReadGaugeLogsRequest(serialNumber, minSequence, maxSequence, reqId))
    }

    fun silenceAllAlarms(
        reqId: UInt,
        callback: ((Boolean, Any?) -> Unit)?,
    ) {
        silenceAlarms(
            global = true,
            productType = CombustionProductType.UNKNOWN,
            serialNumber = null,
            reqId = reqId,
            callback = callback,
        )
    }

    private fun silenceAlarms(
        global: Boolean,
        productType: CombustionProductType,
        serialNumber: String?,
        reqId: UInt,
        callback: ((Boolean, Any?) -> Unit)?,
    ) {
        silenceAlarmsHandler.wait(
            uart.owner,
            MEATNET_MESSAGE_RESPONSE_TIMEOUT_MS,
            reqId,
            callback,
        )
        sendUartRequest(
            NodeSilenceAlarmsRequest(
                serialNumber = serialNumber,
                global = global,
                productType = productType,
                reqId
            )
        )
    }

    override fun connect() {
        uart.connect()
    }

    override fun disconnect() {
        uart.disconnect()
    }

    fun getDevice() = uart

    fun observeDisconnected(key: String = UUID.randomUUID().toString(), callback: () -> Unit) {
        disconnectedCallbacks[key] = callback
    }

    fun removeDisconnectedObserver(key: String) {
        disconnectedCallbacks.remove(key)
    }

    private fun processConnectionState() {
        uart.observeConnectionState { newConnectionState ->
            if (newConnectionState == DeviceConnectionState.DISCONNECTED) {
                disconnectedCallbacks.toMap().forEach { entry ->
                    entry.value.invoke()
                }
            }
        }
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

    private fun observeUartMessages(callback: (suspend (messages: List<NodeUARTMessage>) -> Unit)) {
        uart.jobManager.addJob(
            key = uart.serialNumber,
            job = uart.owner.lifecycleScope.launch(
                CoroutineName("UartResponseObserver"),
            ) {
                uart.observeUartCharacteristic { data ->
                    val messages = NodeUARTMessage.fromData(data.toUByteArray())
                    callback.invoke(messages)
                }
            }
        )
    }

    private fun processUartMessages() {
        observeUartMessages { messages ->
            messages.forEach { message ->
                when {
                    message is NodeReadFeatureFlagsResponse -> {
                        readFeatureFlagsRequest.handled(message.success, message, message.requestId)
                    }

                    message is GenericNodeResponse -> {
                        val handled = genericRequestHandler.handled(
                            message.success,
                            message,
                            message.requestId,
                        )
                        // If the response wasn't handled then pass it up to the NetworkManager, allows us to handle asynchronous response messages
                        if (!handled) {
                            Log.w(
                                LOG_TAG,
                                "NodeBLEDevice: GenericNodeResponse not handled; Passing on to the NetworkManager GenericNodeMessageFlow: $message",
                            )
                            NetworkManager.flowHolder.mutableGenericNodeMessageFlow.emit(message)
                        }
                    }

                    message is GenericNodeRequest -> {
                        // Publish the request to the flow so it can be handled by the user.
                        NetworkManager.flowHolder.mutableGenericNodeMessageFlow.emit(message)
                    }

                    message is NodeSetGaugeHighLowAlarmResponse -> {
                        setGaugeHighLowAlarmStatusHandler.handled(
                            message.success,
                            null,
                            message.requestId
                        )
                    }

                    message is NodeReadGaugeLogsResponse -> {
                        handleGaugeLogResponse(message)
                    }

                    message is NodeGaugeStatusRequest -> {
                        message.serialNumber?.let { serialNumber ->
                            observeGaugeStatusCallback(serialNumber, message.gaugeStatus)
                        }
                    }

                    (message is NodeRequest) && (message.serialNumber == hybridDeviceChild?.serialNumber) -> {
                        hybridDeviceChild?.processNodeRequest(message)
                    }

                    (message is NodeResponse) && (message is TargetedNodeResponse) && (message.serialNumber == hybridDeviceChild?.serialNumber) -> {
                        hybridDeviceChild?.processNodeResponse(message)
                    }

                    message is NodeSilenceAlarmsRequest -> {
                        when (message.global) {
                            true -> SilenceAlarmsRequest.All(message.requestId)
                            else -> message.serialNumber?.let {
                                SilenceAlarmsRequest.Device(
                                    serialNumber = it,
                                    id = message.requestId,
                                )
                            }
                        }?.let {
                            observeSilenceAlarmsCallback(it)
                        }
                    }

                    else -> {
                        // drop the message
                        //Log.w(LOG_TAG, "NodeBLEDevice: Unhandled response: $response")
                    }
                }
            }
        }
    }

    private suspend fun handleGaugeLogResponse(message: NodeReadGaugeLogsResponse) {
        // Send log response to matching callback
        logResponseCallbackForSerialNumber[message.serialNumber]?.invoke(message)
    }

    // Read the serial number from the device
    suspend fun readSerialNumber() {
        uart.readSerialNumber()
    }
}