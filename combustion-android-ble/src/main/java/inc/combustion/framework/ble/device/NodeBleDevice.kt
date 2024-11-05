package inc.combustion.framework.ble.device

import android.bluetooth.BluetoothAdapter
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.scanning.CombustionAdvertisingData
import inc.combustion.framework.ble.uart.Response
import inc.combustion.framework.ble.uart.meatnet.*
import inc.combustion.framework.ble.uart.meatnet.NodeReadFeatureFlagsRequest
import inc.combustion.framework.service.DebugSettings
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

    protected val encryptedRequestHandler = UartBleDevice.MessageCompletionHandler()
    protected val readFeatureFlagsRequest = UartBleDevice.MessageCompletionHandler()

    companion object {
        const val NODE_MESSAGE_RESPONSE_TIMEOUT_MS = 5000L
    }

    init {
        processUartResponses()
    }
    fun sendEncryptedRequest(request: EncryptedNodeRequest, callback: ((Boolean, Any?) -> Unit)?) {
        encryptedRequestHandler.wait(uart.owner, NODE_MESSAGE_RESPONSE_TIMEOUT_MS,null, callback)
        sendUartRequestEncrypted(request)
    }

    fun sendFeatureFlagRequest(reqId: UInt?, callback: ((Boolean, Any?) -> Unit)?) {
        readFeatureFlagsRequest.wait(uart.owner, NODE_MESSAGE_RESPONSE_TIMEOUT_MS, reqId, callback)
        sendUartRequest(NodeReadFeatureFlagsRequest(uart.serialNumber!!, reqId))
    }

    var isInDfuMode: Boolean
        get() = uart.isInDfuMode
        set(value) {
            uart.isInDfuMode = value
        }

    fun connect() = uart.connect()
    fun disconnect() = uart.disconnect()

    fun getDevice() = uart

    private fun sendUartRequestEncrypted(request: EncryptedNodeRequest) {
        uart.owner.lifecycleScope.launch(Dispatchers.IO) {
            if (DebugSettings.DEBUG_LOG_BLE_UART_IO) {
                /*val packet = request.data.joinToString("") {
                    it.toString(16).padStart(2, '0').uppercase()
                }*/
                Log.d(LOG_TAG, "UART-TX: sending encrypted request")
            }
            Log.d("ben", "UART-TX: sending encrypted request")
            uart.writeUartCharacteristic(request.sData)
        }
    }

    private fun sendUartRequest(request: NodeRequest) {
        uart.owner.lifecycleScope.launch(Dispatchers.IO) {
            if (DebugSettings.DEBUG_LOG_BLE_UART_IO) {
                /*val packet = request.data.joinToString("") {
                    it.toString(16).padStart(2, '0').uppercase()
                }*/
                Log.d(LOG_TAG, "UART-TX: sending encrypted request")
            }
            Log.d("ben", "UART-TX: sending request $request")
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
                    //val responses = EncryptedNodeResponse.fromData(data.toUByteArray())
                    val responses = NodeUARTMessage.fromData(data.toUByteArray())
                    //Log.d("ben", "received responses $responses")
                    callback?.invoke(responses)
                }
            }
        )
    }
    private fun processUartResponses() {
        observeUartResponses { responses ->
            responses.forEach { response ->
                when (response) {
                    is EncryptedNodeResponse -> {
                        Log.d("ben", "received encrypted node response")
                        encryptedRequestHandler.handled(response.success, response)
                    }
                    is NodeReadFeatureFlagsResponse -> {
                        Log.d("ben", "received feature flags response")
                        readFeatureFlagsRequest.handled(response.success, response)
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
    // TODO: feature flags?
}