/*
 * Project: Combustion Inc. Android Framework
 * File: SimulatedProbeBleDevice.kt
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

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import inc.combustion.framework.ble.ProbeStatus
import inc.combustion.framework.ble.scanning.DeviceAdvertisingData
import inc.combustion.framework.ble.scanning.ProbeAdvertisingData
import inc.combustion.framework.ble.uart.ProbeLogResponse
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.DeviceConnectionState
import inc.combustion.framework.service.FirmwareVersion
import inc.combustion.framework.service.FoodSafeData
import inc.combustion.framework.service.FoodSafeStatus
import inc.combustion.framework.service.ModelInformation
import inc.combustion.framework.service.OverheatingSensors
import inc.combustion.framework.service.PredictionStatus
import inc.combustion.framework.service.ProbeBatteryStatus
import inc.combustion.framework.service.ProbeColor
import inc.combustion.framework.service.ProbeID
import inc.combustion.framework.service.ProbeMode
import inc.combustion.framework.service.ProbePowerMode
import inc.combustion.framework.service.ProbePredictionMode
import inc.combustion.framework.service.ProbeTemperatures
import inc.combustion.framework.service.ProbeVirtualSensors
import inc.combustion.framework.service.SessionInformation
import inc.combustion.framework.service.ThermometerPreferences
import inc.combustion.framework.service.dfu.DfuProductType
import kotlinx.coroutines.launch
import kotlin.concurrent.fixedRateTimer
import kotlin.random.Random

internal class SimulatedProbeBleDevice(
    private val owner: LifecycleOwner,
    override val mac: String = "%02X:%02X:%02X:%02X:%02X:%02X".format(
        Random.nextBytes(1).first(),
        Random.nextBytes(1).first(),
        Random.nextBytes(1).first(),
        Random.nextBytes(1).first(),
        Random.nextBytes(1).first(),
        Random.nextBytes(1).first()
    ),
    override val id: DeviceID = mac,
    override val serialNumber: String = "%08X".format(Random.nextInt()),
    override val linkId: LinkID = serialNumber + "_" + mac,
    override val hopCount: UInt = 0u,
    override val isInRange: Boolean = true,
    override val isConnectable: Boolean = true,
    override var isInDfuMode: Boolean = false,
    override val productType: CombustionProductType = CombustionProductType.PROBE,
    override var advertisement: ProbeAdvertisingData? = randomAdvertisement(
        mac,
        productType,
        serialNumber,
        hopCount,
        ProbeID.ID1,
        ProbeColor.COLOR1
    ),
    var shouldConnect: Boolean = false
) : ProbeBleDeviceBase() {
    companion object {
        fun randomRSSI(): Int {
            return Random.nextInt(-80, -40)
        }

        fun randomAdvertisement(
            mac: String,
            productType: CombustionProductType,
            probeSerialNumber: String,
            hopCount: UInt,
            probeID: ProbeID,
            probeColor: ProbeColor
        ): ProbeAdvertisingData {
            val probeTemperatures = ProbeTemperatures.withRandomData()
            return ProbeAdvertisingData(
                mac = mac,
                name = "CP",
                rssi = randomRSSI(),
                productType = productType,
                isConnectable = true,
                serialNumber = probeSerialNumber,
                probeTemperatures = probeTemperatures,
                probeID = probeID,
                color = probeColor,
                mode = ProbeMode.NORMAL,
                batteryStatus = ProbeBatteryStatus.OK,
                virtualSensors = ProbeVirtualSensors.DEFAULT,
                overheatingSensors = OverheatingSensors.fromTemperatures(probeTemperatures),
                hopCount = hopCount,
            )
        }
    }

    private var maxSequence = 0u

    private var observeAdvertisingCallback: (suspend (advertisement: ProbeAdvertisingData) -> Unit)? =
        null
    private var observeRemoteRssiCallback: (suspend (rssi: Int) -> Unit)? = null
    private var observeConnectionStateCallback: (suspend (newConnectionState: DeviceConnectionState) -> Unit)? =
        null
    private var observeStatusUpdatesCallback: (suspend (status: ProbeStatus, hopCount: UInt?) -> Unit)? =
        null

    override var rssi: Int = randomRSSI()
        private set

    override var connectionState: DeviceConnectionState =
        DeviceConnectionState.ADVERTISING_CONNECTABLE
        private set

    override var isConnected: Boolean = false
        private set

    override var isDisconnected: Boolean = true
        private set

    override var deviceInfoSerialNumber: String? = null
        private set

    override var deviceInfoFirmwareVersion: FirmwareVersion? = null
        private set

    override var deviceInfoHardwareRevision: String? = null
        private set

    override var deviceInfoModelInformation: ModelInformation? = null
        private set

    private var probeID = ProbeID.ID1
    private var probeColor = ProbeColor.COLOR1

    init {
        fixedRateTimer(name = "SimAdvertising", initialDelay = 1000, period = 1000) {
            owner.lifecycleScope.launch {
                observeAdvertisingCallback?.let {
                    if (!isConnected) {
                        it(
                            randomAdvertisement(
                                mac,
                                productType,
                                serialNumber,
                                hopCount,
                                probeID,
                                probeColor
                            )
                        )
                    }
                }
                observeRemoteRssiCallback?.let {
                    it(randomRSSI())
                }
            }
        }

        fixedRateTimer(name = "SimStatusNotifications", initialDelay = 1000, period = 5000) {
            owner.lifecycleScope.launch {
                maxSequence += 1u
                observeStatusUpdatesCallback?.let {
                    val probeTemperatures = ProbeTemperatures.withRandomData()
                    if (isConnected) {
                        it(
                            ProbeStatus(
                                minSequenceNumber = 0u,
                                maxSequenceNumber = maxSequence,
                                temperatures = probeTemperatures,
                                id = probeID,
                                color = probeColor,
                                mode = ProbeMode.NORMAL,
                                batteryStatus = ProbeBatteryStatus.OK,
                                virtualSensors = ProbeVirtualSensors.DEFAULT,
                                predictionStatus = PredictionStatus.withRandomData(),
                                foodSafeData = FoodSafeData.RANDOM,
                                foodSafeStatus = FoodSafeStatus.RANDOM,
                                overheatingSensors = OverheatingSensors.fromTemperatures(
                                    probeTemperatures
                                ),
                                thermometerPrefs = ThermometerPreferences.DEFAULT,
                            ),
                            null,
                        )
                    }
                }
            }
        }
    }

    override fun connect() {
        isDisconnected = false
        isConnected = true
        connectionState = DeviceConnectionState.CONNECTED
        deviceInfoSerialNumber = serialNumber
        deviceInfoFirmwareVersion = FirmwareVersion(1, 2, 3, null, null)
        deviceInfoHardwareRevision = "v2.3.4"
        deviceInfoModelInformation = ModelInformation(
            productType = CombustionProductType.PROBE,
            dfuProductType = DfuProductType.PROBE,
            sku = "ABCDEF",
            manufacturingLot = "98765"
        )
        observeConnectionStateCallback?.let {
            owner.lifecycleScope.launch {
                it(connectionState)
            }
        }
        publishConnectionState()
    }

    override fun disconnect() {
        isDisconnected = true
        isConnected = false
        deviceInfoSerialNumber = null
        deviceInfoFirmwareVersion = null
        deviceInfoHardwareRevision = null
        deviceInfoModelInformation = null
        connectionState = DeviceConnectionState.ADVERTISING_CONNECTABLE
        publishConnectionState()
    }

    override fun observeAdvertisingPackets(
        serialNumberFilter: String,
        macFilter: String,
        callback: (suspend (advertisement: DeviceAdvertisingData) -> Unit)?
    ) {
        observeAdvertisingCallback = callback
    }

    override fun observeRemoteRssi(callback: (suspend (rssi: Int) -> Unit)?) {
        observeRemoteRssiCallback = callback
    }

    override fun observeOutOfRange(timeout: Long, callback: (suspend () -> Unit)?) {
        // simulated probe does not go out of range
    }

    override fun observeConnectionState(callback: (suspend (newConnectionState: DeviceConnectionState) -> Unit)?) {
        observeConnectionStateCallback = callback
        publishConnectionState()
    }

    override suspend fun readSerialNumber() {
        // nothing to do -- handled on connect
    }

    override suspend fun readFirmwareVersion() {
        // nothing to do -- handled on connect
    }

    override suspend fun readHardwareRevision() {
        // nothing to do -- handled on connect
    }

    override suspend fun readModelInformation() {
        // nothing to do -- handled on connect
    }

    override fun readFirmwareVersionAsync(callback: (FirmwareVersion) -> Unit) {
        // nothing to do -- handled on connect
    }

    override fun readHardwareRevisionAsync(callback: (String) -> Unit) {
        // nothing to do -- handled on connect
    }

    override fun readModelInformationAsync(callback: (ModelInformation) -> Unit) {
        // nothing to do -- handled on connect
    }

    override fun observeProbeStatusUpdates(
        hopCount: UInt?,
        callback: (suspend (status: ProbeStatus, hopCount: UInt?) -> Unit)?
    ) {
        observeStatusUpdatesCallback = callback
    }

    override fun sendSessionInformationRequest(reqId: UInt?, callback: ((Boolean, Any?) -> Unit)?) {
        val sessionInformation = SessionInformation(0x12345678u, 5000u)
        callback?.let { it(true, sessionInformation) }
    }

    override fun sendSetProbeColor(color: ProbeColor, callback: ((Boolean, Any?) -> Unit)?) {
        probeColor = color
        callback?.let { it(true, null) }
    }

    override fun sendSetProbeID(id: ProbeID, callback: ((Boolean, Any?) -> Unit)?) {
        probeID = id
        callback?.let { it(true, null) }
    }

    override fun sendSetPrediction(
        setPointTemperatureC: Double,
        mode: ProbePredictionMode,
        reqId: UInt?,
        callback: ((Boolean, Any?) -> Unit)?
    ) {
        callback?.let { it(true, null) }
    }

    override fun sendConfigureFoodSafe(
        foodSafeData: FoodSafeData,
        reqId: UInt?,
        callback: ((Boolean, Any?) -> Unit)?
    ) {
        callback?.let { it(true, null) }
    }

    override fun sendResetFoodSafe(reqId: UInt?, callback: ((Boolean, Any?) -> Unit)?) {
        callback?.let { it(true, null) }
    }

    override fun sendLogRequest(
        minSequence: UInt,
        maxSequence: UInt,
        callback: (suspend (ProbeLogResponse) -> Unit)?
    ) {
        // do nothing
    }

    override fun sendResetProbe(reqId: UInt?, callback: ((Boolean, Any?) -> Unit)?) {
        callback?.let { it(true, null) }
    }

    override val isSimulated: Boolean = true
    override val isRepeater: Boolean = false
    override var shouldAutoReconnect: Boolean = false

    override fun sendSetPowerMode(
        powerMode: ProbePowerMode,
        reqId: UInt?,
        callback: ((Boolean, Any?) -> Unit)?
    ) {
        callback?.let { it(true, null) }
    }

    private fun publishConnectionState() {
        observeConnectionStateCallback?.let {
            owner.lifecycleScope.launch {
                it(connectionState)
            }
        }
    }
}