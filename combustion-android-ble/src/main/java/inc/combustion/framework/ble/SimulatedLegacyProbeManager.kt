/*
 * Project: Combustion Inc. Android Framework
 * File: SimulatedLegacyProbeManager.kt
 * Author: https://github.com/jjohnstz
 *
 * MIT License
 *
 * Copyright (c) 2022. Combustion Inc.
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
package inc.combustion.framework.ble

import android.bluetooth.BluetoothAdapter
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import inc.combustion.framework.service.*
import kotlinx.coroutines.launch
import kotlin.concurrent.fixedRateTimer
import kotlin.random.Random

/**
 * Manages simulated probe communications and state.
 *
 * @property mac Bluetooth MAC address
 * @property advertisingData Advertising packet for probe.
 * @constructor Constructs a new instance and starts simulating
 *
 * @param owner Lifecycle owner for managing coroutine scope.
 * @param adapter Bluetooth adapter.
 */
internal class SimulatedLegacyProbeManager (
    mac: String,
    owner: LifecycleOwner,
    advertisingData: LegacyProbeAdvertisingData,
    adapter: BluetoothAdapter
): LegacyProbeManager(mac, owner, advertisingData, adapter) {

    private var maxSequence = 0u

    companion object {
        const val SIMULATED_MAC = "00:00:00:00:00:00"

        fun create(owner: LifecycleOwner, adapter: BluetoothAdapter) : SimulatedLegacyProbeManager {
            val fakeSerialNumber = "%08X".format(Random.nextInt())
            val fakeMacAddress = "%02X:%02X:%02X:%02X:%02X:%02X".format(
                Random.nextBytes(1).first(),
                Random.nextBytes(1).first(),
                Random.nextBytes(1).first(),
                Random.nextBytes(1).first(),
                Random.nextBytes(1).first(),
                Random.nextBytes(1).first())

            val data = LegacyProbeAdvertisingData(
                "CP",
                fakeMacAddress,
                randomRSSI(),
                fakeSerialNumber,
                LegacyProbeAdvertisingData.CombustionProductType.PROBE,
                true,
                ProbeTemperatures.withRandomData(),
                ProbeID.ID1,
                ProbeColor.COLOR1,
                ProbeMode.NORMAL,
                ProbeBatteryStatus.OK,
                ProbeVirtualSensors.DEFAULT,
            )

            return SimulatedLegacyProbeManager(SIMULATED_MAC, owner, data, adapter)
        }

        fun randomRSSI() : Int {
            return Random.nextInt(-80, -40)
        }
    }

    init {
        fixedRateTimer(name = "FakeAdvertising",
            initialDelay = 1000, period = 1000) {
            owner.lifecycleScope.launch {
                simulateAdvertising()
            }
        }

        fixedRateTimer(name = "FakeStatusNotifications",
            initialDelay = 1000, period = 1000) {
            owner.lifecycleScope.launch {
                simulateStatusNotifications()
            }
        }
    }

    override fun connect() {
        legacyProbeBleDevice.isConnected.set(true)
        legacyProbeBleDevice.firmwareVersion = "v1.2.3"
        legacyProbeBleDevice.hardwareRevision = "v2.3.4"
    }

    override fun disconnect() {
        legacyProbeBleDevice.isConnected.set(false)
    }

    override fun sendLogRequest(minSequence: UInt, maxSequence: UInt) {
        // Do nothing
    }

    private suspend fun simulateAdvertising() {
        val data = LegacyProbeAdvertisingData("SIM",
            legacyProbeBleDevice.advertisingData.mac,
            randomRSSI(),
            legacyProbeBleDevice.advertisingData.serialNumber,
            LegacyProbeAdvertisingData.CombustionProductType.PROBE,
            true,
            ProbeTemperatures.withRandomData(),
            ProbeID.ID1,
            ProbeColor.COLOR1,
            ProbeMode.NORMAL,
            ProbeBatteryStatus.OK,
            ProbeVirtualSensors.DEFAULT
        )

        super.onNewAdvertisement(data)
    }

    private suspend fun simulateStatusNotifications() {
        if(!legacyProbeBleDevice.isConnected.get()) return

        legacyProbeBleDevice.remoteRssi.set(randomRSSI())

        maxSequence += 1u
        simulatedProbeStatus(
            ProbeStatus(
                0u,
                maxSequence,
                ProbeTemperatures.withRandomData(),
                ProbeID.ID1,
                ProbeColor.COLOR1,
                ProbeMode.NORMAL,
                ProbeBatteryStatus.OK,
                ProbeVirtualSensors.DEFAULT,
                PredictionStatus.withRandomData()
            )
        )
    }
}