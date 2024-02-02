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
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

/**
 * Class representing a directly-connected probe.
 *
 * [mac] is the MAC address of the probe. [probeAdvertisingData] is the most recent advertising data
 * obtained for this probe--after construction, this can be obtained from [advertisement]. [uart]
 * is the interface used for sending and receiving UART messages to and from the probe.
 */
internal class ProbeBleDevice (
    mac: String,
    owner: LifecycleOwner,
    private var probeAdvertisingData: CombustionAdvertisingData,
    adapter: BluetoothAdapter,
    private val uart: UartBleDevice = UartBleDevice(mac, probeAdvertisingData, owner, adapter),
) : ProbeBleDeviceBase() {

    companion object {
        val DEVICE_STATUS_CHARACTERISTIC = characteristicOf(
            service = "00000100-CAAB-3792-3D44-97AE51C1407A",
            characteristic = "00000101-CAAB-3792-3D44-97AE51C1407A"
        )
    }

    override val advertisement: CombustionAdvertisingData
        get() {
            return probeAdvertisingData
        }

    override val mac = uart.mac

    // identifiers
    override val linkId: LinkID
        get() {
            return makeLinkId(advertisement)
        }
    override val id = uart.id

    override val probeSerialNumber: String = probeAdvertisingData.probeSerialNumber

    // ble properties
    override val rssi: Int get() { return uart.rssi }
    override val connectionState: DeviceConnectionState get() { return uart.connectionState }
    override val isConnected: Boolean get() { return uart.isConnected.get() }
    override val isDisconnected: Boolean get() { return uart.isDisconnected.get() }
    override val isInRange: Boolean get() { return uart.isInRange.get() }
    override val isConnectable: Boolean get() { return uart.isConnectable.get() }

    // device information service values from the probe
    override val deviceInfoSerialNumber: String? get() { return uart.serialNumber }
    override val deviceInfoFirmwareVersion: FirmwareVersion? get() { return uart.firmwareVersion }
    override val deviceInfoHardwareRevision: String? get() { return uart.hardwareRevision }
    override val deviceInfoModelInformation: ModelInformation? get() { return uart.modelInformation }

    override val productType: CombustionProductType get() { return uart.productType}

    override var isInDfuMode: Boolean
        get() = uart.isInDfuMode
        set(value) { uart.isInDfuMode = value }

    // auto-reconnect flag
    var shouldAutoReconnect: Boolean = false

    // instance used for connection/disconnection
    var baseDevice: DeviceInformationBleDevice = uart

    override val hopCount: UInt = 0u

    private var probeStatusJob: Job? = null

    private var logResponseCallback: (suspend (LogResponse) -> Unit)? = null

    init {
        processUartResponses()
    }

    // connection management
    override fun connect() = uart.connect()
    override fun disconnect() = uart.disconnect()

    override fun sendSessionInformationRequest(reqId: UInt?, callback: ((Boolean, Any?) -> Unit)?)  {
        sessionInfoHandler.wait(uart.owner, PROBE_MESSAGE_RESPONSE_TIMEOUT_MS, null, callback)
        sendUartRequest(SessionInfoRequest())
    }

    override fun sendSetProbeColor(color: ProbeColor, callback: ((Boolean, Any?) -> Unit)?) {
        setColorHandler.wait(uart.owner, PROBE_MESSAGE_RESPONSE_TIMEOUT_MS, null, callback)
        sendUartRequest(SetColorRequest(color))
    }

    override fun sendSetProbeID(id: ProbeID, callback: ((Boolean, Any?) -> Unit)?) {
        setIdHandler.wait(uart.owner, PROBE_MESSAGE_RESPONSE_TIMEOUT_MS, null, callback)
        sendUartRequest(SetIDRequest(id))
    }

    override fun sendSetPrediction(setPointTemperatureC: Double, mode: ProbePredictionMode, reqId: UInt?, callback: ((Boolean, Any?) -> Unit)?) {
        setPredictionHandler.wait(uart.owner, PROBE_MESSAGE_RESPONSE_TIMEOUT_MS, null, callback)
        sendUartRequest(SetPredictionRequest(setPointTemperatureC, mode))
    }

    override fun sendConfigureFoodSafe(foodSafeData: FoodSafeData, reqId: UInt?, callback: ((Boolean, Any?) -> Unit)?) {
        configureFoodSafeHandler.wait(uart.owner, PROBE_MESSAGE_RESPONSE_TIMEOUT_MS, null, callback)
        sendUartRequest(ConfigureFoodSafeRequest(foodSafeData = foodSafeData))
    }

    override fun sendResetFoodSafe(reqId: UInt?, callback: ((Boolean, Any?) -> Unit)?) {
        resetFoodSafeHandler.wait(uart.owner, PROBE_MESSAGE_RESPONSE_TIMEOUT_MS, null, callback)
        sendUartRequest(ResetFoodSafeRequest())
    }

    override fun sendLogRequest(minSequence: UInt, maxSequence: UInt, callback: (suspend (LogResponse) -> Unit)?) {
        logResponseCallback = callback
        sendUartRequest(LogRequest(minSequence, maxSequence))
    }

    override suspend fun readSerialNumber() = uart.readSerialNumber()

    override suspend fun readFirmwareVersion() = uart.readFirmwareVersion()
    fun readProbeFirmwareVersion(callback: (FirmwareVersion) -> Unit) {
        uart.owner.lifecycleScope.launch {
            readFirmwareVersion()
        }.invokeOnCompletion {
            deviceInfoFirmwareVersion?.let {
                callback(it)
            }
        }
    }

    override suspend fun readHardwareRevision() = uart.readHardwareRevision()
    fun readProbeHardwareRevision(callback: (String) -> Unit) {
        uart.owner.lifecycleScope.launch {
            readHardwareRevision()
        }.invokeOnCompletion {
            deviceInfoHardwareRevision?.let {
                callback(it)
            }
        }
    }

    override suspend fun readModelInformation() = uart.readModelInformation()
    fun readProbeModelInformation(callback: (ModelInformation) -> Unit) {
        uart.owner.lifecycleScope.launch {
            readModelInformation()
        }.invokeOnCompletion {
            deviceInfoModelInformation?.let {
                callback(it)
            }
        }
    }

    override fun observeAdvertisingPackets(serialNumberFilter: String, macFilter: String, callback: (suspend (advertisement: CombustionAdvertisingData) -> Unit)?) {
        uart.observeAdvertisingPackets(
            jobKey = serialNumberFilter,
            filter = { advertisement ->
                macFilter == advertisement.mac && advertisement.probeSerialNumber == serialNumberFilter
            }
        ) { advertisement ->
            callback?.let {
                probeAdvertisingData = advertisement
                it(advertisement)
            }
        }
    }

    override fun observeRemoteRssi(callback: (suspend (rssi: Int) -> Unit)?) = uart.observeRemoteRssi(callback)
    override fun observeOutOfRange(timeout: Long, callback: (suspend () -> Unit)?) = uart.observeOutOfRange(timeout, callback)
    override fun observeConnectionState(callback: (suspend (newConnectionState: DeviceConnectionState) -> Unit)?) = uart.observeConnectionState(callback)

    override fun observeProbeStatusUpdates(callback: (suspend (status: ProbeStatus) -> Unit)?) {
        if (probeStatusJob?.isActive != true) {
            val job = uart.owner.lifecycleScope.launch(
                CoroutineName("${probeSerialNumber}.observeProbeStatusUpdates")
            ) {
                uart.peripheral.observe(DEVICE_STATUS_CHARACTERISTIC)
                    .onCompletion {
                        Log.d(LOG_TAG, "Device Status Characteristic Monitor Complete")
                    }
                    .catch {
                        Log.i(LOG_TAG, "Device Status Characteristic Monitor Catch: $it")
                    }
                    .collect { data ->
                        ProbeStatus.fromRawData(data.toUByteArray())?.let { status ->
                            callback?.let {
                                it(status)
                            }
                        }
                    }
            }
            probeStatusJob = job
            uart.jobManager.addJob(key = probeSerialNumber, job = job)
        }
    }

    private fun observeUartResponses(callback: (suspend (responses: List<Response>) -> Unit)? = null) {
        uart.jobManager.addJob(
            key = probeSerialNumber,
            job = uart.owner.lifecycleScope.launch(
                CoroutineName("${probeSerialNumber}.observeUartResponses")
            ){
                uart.observeUartCharacteristic { data ->
                    callback?.let {
                        it(Response.fromData(data.toUByteArray()))
                    }
                }
            }
        )
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

    private fun processUartResponses() {
        observeUartResponses { responses ->
            for (response in responses) {
                when (response) {
                    is LogResponse -> logResponseCallback?.let { it(response) }
                    is SessionInfoResponse -> sessionInfoHandler.handled(response.success, response.sessionInformation)
                    is SetColorResponse -> setColorHandler.handled(response.success, null)
                    is SetIDResponse -> setIdHandler.handled(response.success, null)
                    is SetPredictionResponse -> setPredictionHandler.handled(response.success, null)
                    is ConfigureFoodSafeResponse -> configureFoodSafeHandler.handled(response.success, null)
                    is ResetFoodSafeResponse -> resetFoodSafeHandler.handled(response.success, null)
                }
            }
        }
    }
}
