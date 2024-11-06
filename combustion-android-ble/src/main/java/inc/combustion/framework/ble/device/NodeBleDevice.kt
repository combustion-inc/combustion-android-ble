package inc.combustion.framework.ble.device

import android.bluetooth.BluetoothAdapter
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.scanning.CombustionAdvertisingData
import inc.combustion.framework.ble.uart.meatnet.*
import inc.combustion.framework.ble.uart.meatnet.NodeReadFeatureFlagsRequest
import inc.combustion.framework.service.DebugSettings
import inc.combustion.framework.service.DeviceConnectionState
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class NodeBleDevice(
    mac: String,
    owner: LifecycleOwner,
    private var nodeAdvertisingData: CombustionAdvertisingData,
    adapter: BluetoothAdapter,
    private var uart: UartBleDevice = UartBleDevice(mac, nodeAdvertisingData, owner, adapter)
) {

    protected val genericRequestHandler = UartBleDevice.MessageCompletionHandler()
    protected val readFeatureFlagsRequest = UartBleDevice.MessageCompletionHandler()

    companion object {
        const val NODE_MESSAGE_RESPONSE_TIMEOUT_MS = 5000L
    }

    init {
        processUartResponses()
    }

    val rssi: Int get() { return uart.rssi }
    val connectionState: DeviceConnectionState get() { return uart.connectionState }
    val isConnected: Boolean get() { return uart.isConnected.get() }
    val isDisconnected: Boolean get() { return uart.isDisconnected.get() }
    val isInRange: Boolean get() { return uart.isInRange.get() }
    val isConnectable: Boolean get() { return uart.isConnectable.get() }

    fun sendRequest(request: GenericNodeRequest, callback: ((Boolean, Any?) -> Unit)?) {
        val nodeRequest = request.toNodeRequest()
        genericRequestHandler.wait(uart.owner, NODE_MESSAGE_RESPONSE_TIMEOUT_MS, null, callback)
        sendUartRequest(nodeRequest)
    }

    fun sendFeatureFlagRequest(reqId: UInt?, callback: ((Boolean, Any?) -> Unit)?) {
        val serialNumber = uart.serialNumber?: return
        readFeatureFlagsRequest.wait(uart.owner, NODE_MESSAGE_RESPONSE_TIMEOUT_MS, reqId, callback)
        sendUartRequest(NodeReadFeatureFlagsRequest(serialNumber, reqId))
    }

    var isInDfuMode: Boolean
        get() = uart.isInDfuMode
        set(value) {
            uart.isInDfuMode = value
        }

    fun connect() = uart.connect()
    fun disconnect() = uart.disconnect()

    fun getDevice() = uart

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

    private fun observeUartResponses(callback: (suspend (responses: List<NodeUARTMessage>) -> Unit)? = null) {
        uart.jobManager.addJob(
            key = uart.serialNumber,
            job = uart.owner.lifecycleScope.launch(
                CoroutineName("UartResponseObserver"),
            ) {
                uart.observeUartCharacteristic {data ->
                    val responses = NodeUARTMessage.fromData(data.toUByteArray())
                    callback?.invoke(responses)
                }
            }
        )
    }
    private fun processUartResponses() {
        observeUartResponses { responses ->
            responses.forEach { response ->
                when (response) {
                    is NodeReadFeatureFlagsResponse -> {
                        readFeatureFlagsRequest.handled(response.success, response)
                    }
                    is GenericNodeResponse -> {
                        genericRequestHandler.handled(response.success, response)
                    }
                    else -> {
                        // drop the message
                        // Log.w(LOG_TAG, "Unhandled response: $response")
                    }
                }
            }
        }
    }

    // Read the serial number from the device
    suspend fun readSerialNumber() {
        uart.readSerialNumber()
    }

    val serialNumber: String?
        get() = uart.serialNumber
}