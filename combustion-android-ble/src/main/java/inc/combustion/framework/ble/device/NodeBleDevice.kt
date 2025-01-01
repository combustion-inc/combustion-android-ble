package inc.combustion.framework.ble.device

import android.bluetooth.BluetoothAdapter
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.NetworkManager
import inc.combustion.framework.ble.scanning.CombustionAdvertisingData
import inc.combustion.framework.ble.uart.meatnet.*
import inc.combustion.framework.service.DebugSettings
import inc.combustion.framework.service.DeviceConnectionState
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

internal class NodeBleDevice(
    mac: String,
    owner: LifecycleOwner,
    nodeAdvertisingData: CombustionAdvertisingData,
    adapter: BluetoothAdapter,
    private val uart: UartBleDevice = UartBleDevice(mac, nodeAdvertisingData, owner, adapter)
) {

    private val genericRequestHandler = UartBleDevice.MessageCompletionHandler()
    private val readFeatureFlagsRequest = UartBleDevice.MessageCompletionHandler()

    companion object {
        const val NODE_MESSAGE_RESPONSE_TIMEOUT_MS = 120000L
    }

    val rssi: Int
        get() {
            return uart.rssi
        }
    val connectionState: DeviceConnectionState
        get() {
            return uart.connectionState
        }
    val isConnected: Boolean
        get() {
            return uart.isConnected.get()
        }
    val isDisconnected: Boolean
        get() {
            return uart.isDisconnected.get()
        }
    val isInRange: Boolean
        get() {
            return uart.isInRange.get()
        }
    val isConnectable: Boolean
        get() {
            return uart.isConnectable.get()
        }

    val deviceInfoSerialNumber: String?
        get() {
            return uart.serialNumber
        }

    private val disconnectedCallbacks = mutableMapOf<String, () -> Unit>()

    var isInDfuMode: Boolean
        get() = uart.isInDfuMode
        set(value) {
            uart.isInDfuMode = value
        }

    init {
        processUartMessages()
        processConnectionState()
    }

    fun sendNodeRequest(request: GenericNodeRequest, callback: ((Boolean, Any?) -> Unit)?) {
        val nodeRequest = request.toNodeRequest()
        genericRequestHandler.wait(
            uart.owner,
            NODE_MESSAGE_RESPONSE_TIMEOUT_MS,
            nodeRequest.requestId,
            callback
        )
        sendUartRequest(nodeRequest)
    }

    fun sendFeatureFlagRequest(reqId: UInt?, callback: ((Boolean, Any?) -> Unit)?) {
        val nodeSerialNumber = deviceInfoSerialNumber ?: return
        readFeatureFlagsRequest.wait(uart.owner, NODE_MESSAGE_RESPONSE_TIMEOUT_MS, reqId, callback)
        sendUartRequest(NodeReadFeatureFlagsRequest(nodeSerialNumber, reqId))
    }

    fun connect() = uart.connect()
    fun disconnect() = uart.disconnect()

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
                when (message) {
                    is NodeReadFeatureFlagsResponse -> {
                        readFeatureFlagsRequest.handled(message.success, message, message.requestId)
                    }

                    is GenericNodeResponse -> {
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

                    is GenericNodeRequest -> {
                        // Publish the request to the flow so it can be handled by the user.
                        NetworkManager.flowHolder.mutableGenericNodeMessageFlow.emit(message)
                    }

                    else -> {
                        // drop the message
                        //Log.w(LOG_TAG, "NodeBLEDevice: Unhandled response: $response")
                    }
                }
            }
        }
    }

    // Read the serial number from the device
    suspend fun readSerialNumber() {
        uart.readSerialNumber()
    }
}