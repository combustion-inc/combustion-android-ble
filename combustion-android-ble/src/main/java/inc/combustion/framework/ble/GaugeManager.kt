/*
 * Project: Combustion Inc. Android Framework
 * File: GaugeManager.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2025. Combustion Inc.
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

import androidx.lifecycle.LifecycleOwner
import inc.combustion.framework.ble.device.SimulatedGaugeBleDevice
import inc.combustion.framework.ble.scanning.GaugeAdvertisingData
import inc.combustion.framework.service.DeviceManager
import inc.combustion.framework.service.Gauge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class GaugeManager(
    serialNumber: String,
    val owner: LifecycleOwner,
    private val settings: DeviceManager.Settings,
) {
    // holds the current state and data for this probe
    private val _gauge = MutableStateFlow(Gauge.create(serialNumber = serialNumber))

    // the flow that is consumed to get state and date updates
    val gaugeFlow = _gauge.asStateFlow()

    val gauge: Gauge
        get() {
            return _gauge.value
        }

    // serial number of the probe that is being managed by this manager
    val serialNumber: String
        get() {
            return _gauge.value.serialNumber
        }

    // TODO : implement

    private var simulatedGauge: SimulatedGaugeBleDevice? = null

    fun connect() {
//        arbitrator.getNodesNeedingConnection(true).forEach { node ->
//            node.connect()
//        }

        simulatedGauge?.shouldConnect = true
        simulatedGauge?.connect()
    }

    fun disconnect(canDisconnectFromMeatNetDevices: Boolean = false) {
//        arbitrator.getNodesNeedingDisconnect(
////            canDisconnectFromMeatNetDevices = canDisconnectFromMeatNetDevices
////        ).forEach { node ->
////            Log.d(LOG_TAG, "ProbeManager: disconnecting $node")
////
////            // Since this is an explicit disconnect, we want to also disable the auto-reconnect flag
////            arbitrator.setShouldAutoReconnect(false)
////            node.disconnect()
////        }

//        arbitrator.directLinkDiscoverTimestamp = null

        simulatedGauge?.shouldConnect = false
        simulatedGauge?.disconnect()
    }

    private fun updateDataFromAdvertisement(
        advertisement: GaugeAdvertisingData,
        currentGauge: Gauge,
    ): Gauge {
        var updatedGauge = currentGauge.copy(
            baseDevice = _gauge.value.baseDevice.copy(rssi = advertisement.rssi),
        )

        // TODO

        return updatedGauge
    }

    fun addSimulatedGauge(simGauge: SimulatedGaugeBleDevice) {
        if (simulatedGauge != null) return
        var updatedGauge = _gauge.value

        // process simulated connection state changes
//        simGauge.observeConnectionState { state ->
//            updatedGauge = handleConnectionState(simProbe, state, updatedProbe)
//        }

        // process simulated out of range
//        simGauge.observeOutOfRange(OUT_OF_RANGE_TIMEOUT) {
//            updatedGauge = handleOutOfRange(updatedProbe)
//        }

        // process simulated advertising packets
        simGauge.observeAdvertisingPackets(serialNumber, simGauge.mac) { advertisement ->
            if (advertisement is GaugeAdvertisingData) {
                updatedGauge = updateDataFromAdvertisement(advertisement, updatedGauge)
                if (settings.autoReconnect && simGauge.shouldConnect) {
                    simGauge.connect()
                }
            }
        }

        simulatedGauge = simGauge

        _gauge.update {
            updatedGauge.copy(baseDevice = it.baseDevice.copy(mac = simGauge.mac))
        }
    }
}