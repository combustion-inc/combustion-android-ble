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
import com.juul.kable.characteristicOf
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.ProbeStatus
import inc.combustion.framework.ble.device.UartCapableProbe.Companion.PROBE_MESSAGE_RESPONSE_TIMEOUT_MS
import inc.combustion.framework.ble.device.UartCapableProbe.Companion.makeLinkId
import inc.combustion.framework.ble.scanning.DeviceAdvertisingData
import inc.combustion.framework.ble.scanning.ProbeAdvertisingData
import inc.combustion.framework.ble.uart.*
import inc.combustion.framework.service.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion

/**
 * Class representing a directly-connected probe.
 *
 * [mac] is the MAC address of the probe. [probeAdvertisingData] is the most recent advertising data
 * obtained for this probe--after construction, this can be obtained from [advertisement]. [uart]
 * is the interface used for sending and receiving UART messages to and from the probe.
 */
internal class ProbeBleDevice(
    mac: String,
    scope: CoroutineScope,
    private var probeAdvertisingData: ProbeAdvertisingData,
    adapter: BluetoothAdapter,
    private val uart: UartBleDevice = UartBleDevice(mac, probeAdvertisingData, scope, adapter),
) : ProbeBleDeviceBase() {

    companion object {
        val DEVICE_STATUS_CHARACTERISTIC = characteristicOf(
            service = "00000100-CAAB-3792-3D44-97AE51C1407A",
            characteristic = "00000101-CAAB-3792-3D44-97AE51C1407A"
        )
    }

    override val advertisement: ProbeAdvertisingData
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

    override val serialNumber: String = probeAdvertisingData.serialNumber
    override val isSimulated: Boolean = false
    override val isRepeater: Boolean = false

    // ble properties
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

    // device information service values from the probe
    override val deviceInfoSerialNumber: String?
        get() {
            return uart.serialNumber
        }
    override val deviceInfoFirmwareVersion: FirmwareVersion?
        get() {
            return uart.firmwareVersion
        }
    override val deviceInfoHardwareRevision: String?
        get() {
            return uart.hardwareRevision
        }
    override val deviceInfoModelInformation: ModelInformation?
        get() {
            return uart.modelInformation
        }

    override val productType: CombustionProductType
        get() {
            return uart.productType
        }

    override var isInDfuMode: Boolean
        get() = uart.isInDfuMode
        set(value) {
            uart.isInDfuMode = value
        }

    // auto-reconnect flag
    override var shouldAutoReconnect: Boolean = false

    // instance used for connection/disconnection
    var baseDevice: DeviceInformationBleDevice = uart

    override val hopCount: UInt = 0u

    private var probeStatusJob: Job? = null

    private var logResponseCallback: (suspend (ProbeLogResponse) -> Unit)? = null

    private val silenceAlarmsHandler = UartBleDevice.MessageCompletionHandler()

    init {
        processUartResponses()
    }

    // connection management
    override fun connect() = uart.connect()
    override fun disconnect() = uart.disconnect()

    override fun sendSessionInformationRequest(reqId: UInt?, callback: ((Boolean, Any?) -> Unit)?) {
        sessionInfoHandler.wait(uart.scope, PROBE_MESSAGE_RESPONSE_TIMEOUT_MS, null, callback)
        sendUartRequest(SessionInfoRequest())
    }

    override fun sendSetProbeColor(color: ProbeColor, callback: ((Boolean, Any?) -> Unit)?) {
        setColorHandler.wait(uart.scope, PROBE_MESSAGE_RESPONSE_TIMEOUT_MS, null, callback)
        sendUartRequest(SetColorRequest(color))
    }

    override fun sendSetProbeID(id: ProbeID, callback: ((Boolean, Any?) -> Unit)?) {
        setIdHandler.wait(uart.scope, PROBE_MESSAGE_RESPONSE_TIMEOUT_MS, null, callback)
        sendUartRequest(SetIDRequest(id))
    }

    override fun sendSetPrediction(
        setPointTemperatureC: Double,
        mode: ProbePredictionMode,
        reqId: UInt?,
        callback: ((Boolean, Any?) -> Unit)?
    ) {
        setPredictionHandler.wait(uart.scope, PROBE_MESSAGE_RESPONSE_TIMEOUT_MS, null, callback)
        sendUartRequest(SetPredictionRequest(setPointTemperatureC, mode))
    }

    override fun sendConfigureFoodSafe(
        foodSafeData: FoodSafeData,
        reqId: UInt?,
        callback: ((Boolean, Any?) -> Unit)?
    ) {
        configureFoodSafeHandler.wait(uart.scope, PROBE_MESSAGE_RESPONSE_TIMEOUT_MS, null, callback)
        sendUartRequest(ConfigureFoodSafeRequest(foodSafeData = foodSafeData))
    }

    override fun sendResetFoodSafe(reqId: UInt?, callback: ((Boolean, Any?) -> Unit)?) {
        resetFoodSafeHandler.wait(uart.scope, PROBE_MESSAGE_RESPONSE_TIMEOUT_MS, null, callback)
        sendUartRequest(ResetFoodSafeRequest())
    }

    override fun sendResetProbe(reqId: UInt?, callback: ((Boolean, Any?) -> Unit)?) {
        resetProbeHandler.wait(uart.scope, PROBE_MESSAGE_RESPONSE_TIMEOUT_MS, reqId, callback)
        sendUartRequest(ResetProbeRequest())
    }

    override fun sendSetPowerMode(
        powerMode: ProbePowerMode,
        reqId: UInt?,
        callback: ((Boolean, Any?) -> Unit)?
    ) {
        setPowerModeHandler.wait(uart.scope, PROBE_MESSAGE_RESPONSE_TIMEOUT_MS, reqId, callback)
        sendUartRequest(SetPowerModeRequest(powerMode))
    }

    override fun sendLogRequest(
        minSequence: UInt,
        maxSequence: UInt,
        callback: (suspend (ProbeLogResponse) -> Unit)?
    ) {
        logResponseCallback = callback
        sendUartRequest(ProbeLogRequest(minSequence, maxSequence))
    }

    override fun sendSetProbeHighLowAlarmStatus(
        highLowAlarmStatus: ProbeHighLowAlarmStatus,
        reqId: UInt?,
        callback: ((Boolean, Any?) -> Unit)?,
    ) {
        setProbeHighLowAlarmStatusHandler.wait(
            uart.scope,
            PROBE_MESSAGE_RESPONSE_TIMEOUT_MS,
            reqId,
            callback,
        )
        sendUartRequest(SetProbeHighLowAlarmRequest(highLowAlarmStatus))
    }

    fun silenceProbeAlarms(callback: ((Boolean, Any?) -> Unit)?) {
        silenceAlarmsHandler.wait(
            scope = uart.scope,
            duration = PROBE_MESSAGE_RESPONSE_TIMEOUT_MS,
            reqId = null,
            callback = callback,
        )
        sendUartRequest(SilenceProbeAlarmsRequest())
    }


    override suspend fun readSerialNumber() = uart.readSerialNumber()
    override suspend fun readFirmwareVersion() = uart.readFirmwareVersion()
    override suspend fun readHardwareRevision() = uart.readHardwareRevision()
    override suspend fun readModelInformation() = uart.readModelInformation()

    override fun readFirmwareVersionAsync(callback: (FirmwareVersion) -> Unit) {
        uart.scope.launch {
            readFirmwareVersion()
        }.invokeOnCompletion {
            deviceInfoFirmwareVersion?.let {
                callback(it)
            }
        }
    }

    override fun readHardwareRevisionAsync(callback: (String) -> Unit) {
        uart.scope.launch {
            readHardwareRevision()
        }.invokeOnCompletion {
            deviceInfoHardwareRevision?.let {
                callback(it)
            }
        }
    }

    override fun readModelInformationAsync(callback: (ModelInformation) -> Unit) {
        uart.scope.launch {
            readModelInformation()
        }.invokeOnCompletion {
            deviceInfoModelInformation?.let {
                callback(it)
            }
        }
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
                    probeAdvertisingData = advertisement
                    it(advertisement)
                }
            }
        }
    }

    override fun observeRemoteRssi(callback: (suspend (rssi: Int) -> Unit)?) =
        uart.observeRemoteRssi(callback)

    override fun observeOutOfRange(timeout: Long, callback: (suspend () -> Unit)?) =
        uart.observeOutOfRange(timeout, callback)

    override fun observeConnectionState(callback: (suspend (newConnectionState: DeviceConnectionState) -> Unit)?) =
        uart.observeConnectionState(callback)

    override fun observeProbeStatusUpdates(
        hopCount: UInt?,
        callback: (suspend (status: ProbeStatus, hopCount: UInt?) -> Unit)?
    ) {
        if (probeStatusJob?.isActive != true) {
            val job = uart.scope.launch(
                CoroutineName("$serialNumber.observeProbeStatusUpdates")
            ) {
                uart.peripheral.observe(DEVICE_STATUS_CHARACTERISTIC)
                    .onCompletion {
                        Log.d(LOG_TAG, "Device Status Characteristic Monitor Complete")
                    }
                    .catch {
                        Log.w(LOG_TAG, "Device Status Characteristic Monitor Catch: $it")
                    }
                    .collect { data ->
                        ProbeStatus.fromRawData(data.toUByteArray())?.let { status ->
                            callback?.let {
                                it(status, hopCount)
                            }
                        }
                    }
            }
            probeStatusJob = job
            uart.jobManager.addJob(key = serialNumber, job = job)
        }
    }

    override fun toString(): String {
        return "ProbeBleDevice(${super.toString()}"
    }

    private fun observeUartResponses(callback: (suspend (responses: List<Response>) -> Unit)? = null) {
        uart.jobManager.addJob(
            key = serialNumber,
            job = uart.scope.launch(
                CoroutineName("$serialNumber.observeUartResponses"),
            ) {
                withContext(Dispatchers.Main) {
                    uart.observeUartCharacteristic { data ->
                        callback?.let {
                            it(Response.fromData(data.toUByteArray()))
                        }
                    }
                }
            }
        )
    }

    private fun sendUartRequest(request: Request) {
        uart.scope.launch(Dispatchers.IO) {
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
                    is ProbeLogResponse -> logResponseCallback?.let { it(response) }
                    is SessionInfoResponse -> sessionInfoHandler.handled(
                        response.success,
                        response.sessionInformation,
                    )

                    is SetColorResponse -> setColorHandler.handled(response.success, null)
                    is SetIDResponse -> setIdHandler.handled(response.success, null)
                    is SetPredictionResponse -> setPredictionHandler.handled(response.success, null)
                    is ConfigureFoodSafeResponse -> configureFoodSafeHandler.handled(
                        response.success,
                        null,
                    )

                    is ResetFoodSafeResponse -> resetFoodSafeHandler.handled(response.success, null)
                    is SetPowerModeResponse -> setPowerModeHandler.handled(response.success, null)
                    is ResetProbeResponse -> resetProbeHandler.handled(response.success, null)
                    is SetProbeHighLowAlarmResponse -> setProbeHighLowAlarmStatusHandler.handled(
                        response.success,
                        null,
                    )

                    is SilenceProbeAlarmsResponse -> silenceAlarmsHandler.handled(
                        response.success,
                        null,
                    )
                }
            }
        }
    }
}
