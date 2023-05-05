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
import inc.combustion.framework.ble.scanning.CombustionAdvertisingData
import inc.combustion.framework.ble.uart.LogResponse
import inc.combustion.framework.service.*
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
    override val probeSerialNumber: String = "%08X".format(Random.nextInt()),
    override val linkId: LinkID = probeSerialNumber + "_" + mac,
    override val hopCount: UInt = 0u,
    override val isInRange: Boolean = true,
    override val isConnectable: Boolean = true,
    override var isInDfuMode: Boolean = false,
    override val productType: CombustionProductType = CombustionProductType.PROBE,
    override var advertisement: CombustionAdvertisingData? = randomAdvertisement(
        mac,
        productType,
        probeSerialNumber,
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
        ) : CombustionAdvertisingData {
            return CombustionAdvertisingData(
                mac,
                "CP",
                randomRSSI(),
                productType,
                true,
                probeSerialNumber,
                ProbeTemperatures.withRandomData(),
                probeID,
                probeColor,
                ProbeMode.NORMAL,
                ProbeBatteryStatus.OK,
                ProbeVirtualSensors.DEFAULT,
                hopCount,
            )
        }
    }

    private var maxSequence = 0u

    private var observeAdvertisingCallback: (suspend (advertisement: CombustionAdvertisingData) -> Unit)? = null
    private var observeRemoteRssiCallback: (suspend (rssi: Int) -> Unit)? = null
    private var observeConnectionStateCallback: (suspend (newConnectionState: DeviceConnectionState) -> Unit)? = null
    private var observeStatusUpdatesCallback: (suspend (status: ProbeStatus) -> Unit)? = null

    override var rssi: Int = randomRSSI()
        private set

    override var connectionState: DeviceConnectionState = DeviceConnectionState.ADVERTISING_CONNECTABLE
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
                    if(!isConnected) {
                        it(randomAdvertisement(mac, productType, probeSerialNumber, hopCount, probeID, probeColor))
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
                    if(isConnected) {
                        it(
                            ProbeStatus(
                                0u,
                                maxSequence,
                                ProbeTemperatures.withRandomData(),
                                probeID,
                                probeColor,
                                ProbeMode.NORMAL,
                                ProbeBatteryStatus.OK,
                                ProbeVirtualSensors.DEFAULT,
                                PredictionStatus.withRandomData()
                            )
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
        deviceInfoSerialNumber = probeSerialNumber
        deviceInfoFirmwareVersion = FirmwareVersion(1, 2, 3, null, null)
        deviceInfoHardwareRevision = "v2.3.4"
        deviceInfoModelInformation = ModelInformation("ABCDEF", "98765")
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
        callback: (suspend (advertisement: CombustionAdvertisingData) -> Unit)?
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

    override fun observeProbeStatusUpdates(callback: (suspend (status: ProbeStatus) -> Unit)?) {
        observeStatusUpdatesCallback = callback
    }

    override fun sendSessionInformationRequest(callback: ((Boolean, Any?) -> Unit)?) {
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
        callback: ((Boolean, Any?) -> Unit)?
    ) {
        callback?.let { it(true, null) }
    }

    override fun sendLogRequest(
        minSequence: UInt,
        maxSequence: UInt,
        callback: (suspend (LogResponse) -> Unit)?
    ) {
        // do nothing
    }

    private fun publishConnectionState() {
        observeConnectionStateCallback?.let {
            owner.lifecycleScope.launch {
                it(connectionState)
            }
        }
    }
}