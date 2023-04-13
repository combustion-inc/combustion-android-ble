/*
 * Project: Combustion Inc. Android Framework
 * File: DataLinkArbitrator.kt
 * Author: https://github.com/miwright2
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

package inc.combustion.framework.ble

import android.util.Log
import inc.combustion.framework.ble.device.*
import inc.combustion.framework.ble.device.ProbeBleDevice
import inc.combustion.framework.ble.device.ProbeBleDeviceBase
import inc.combustion.framework.ble.device.RepeatedProbeBleDevice
import inc.combustion.framework.ble.scanning.CombustionAdvertisingData
import inc.combustion.framework.service.DeviceConnectionState
import inc.combustion.framework.service.DeviceManager

internal class DataLinkArbitrator(
    private val settings: DeviceManager.Settings
) {
    companion object {
        private const val USE_STATIC_LINK: Boolean = false
    }

    // meatnet network nodes
    private val networkNodes = hashMapOf<DeviceID, DeviceInformationBleDevice>()

    // advertising data arbitration
    private val advertisingArbitrator = AdvertisingArbitrator()

    // direct ble link to probe
    var probeBleDevice: ProbeBleDevice? = null
        private set

    // meatnet links to probe
    val repeatedProbeBleDevices = mutableListOf<RepeatedProbeBleDevice>()

    val directLink: ProbeBleDevice?
        get() {
            return if(probeBleDevice?.isConnected == true) probeBleDevice else null
        }

    val preferredMeatNetLink: ProbeBleDeviceBase?
        get() {
            // If meatnet is not enabled, then return the probe connection
            if(!settings.meatNetEnabled) {
                return directLink
            }

            // If meatnet is enabled and connected directly to the probe
            // then use that connection
            directLink?.let {
                return it
            }

            // Use repeated probe device with lowest hop count that has route to the probe,
            // additionally sort by ID to keep the selection consistent when multiple nodes are
            // repeating at the same hop count.
            return repeatedProbeBleDevices.sortedWith(
                compareBy(RepeatedProbeBleDevice::hopCount, RepeatedProbeBleDevice::id)
            ).firstOrNull {
                it.isConnected && it.connectionState != DeviceConnectionState.NO_ROUTE
            }
        }

    val hasNoUartRoute: Boolean
        get() {
            val noDirectRoute = directLink?.isConnected ?: true
            var noRepeatedRoute = true

            for(probe in repeatedProbeBleDevices)  {
                if(!(probe.isConnected && probe.connectionState == DeviceConnectionState.NO_ROUTE)) {
                    noRepeatedRoute = false
                    break
                }
            }

            return noDirectRoute && noRepeatedRoute
        }

    val meatNetIsAdvertisingConnectable: Boolean
        get() {
            if(directLink?.isConnectable == true) {
                return true
            }

            return repeatedProbeBleDevices.firstOrNull { it.isConnectable } != null
        }

    val meatNetIsOutOfRange: Boolean
        get() {
            val directInRange = directLink?.isInRange ?: true

            return repeatedProbeBleDevices.firstOrNull {
                it.isInRange
            } == null && !directInRange
        }

    fun finish() {
        networkNodes.forEach { it.value.finish() }
        repeatedProbeBleDevices.clear()
        networkNodes.clear()
        probeBleDevice = null
    }

    fun addProbe(probe: ProbeBleDevice, baseDevice: DeviceInformationBleDevice): Boolean {
        if(probeBleDevice == null) {
            probeBleDevice = probe
            networkNodes[baseDevice.id] = baseDevice
            return true
        }

        return false
    }

    fun addRepeatedProbe(repeatedProbe: RepeatedProbeBleDevice, repeater: DeviceInformationBleDevice): Boolean {
        repeatedProbeBleDevices.add(repeatedProbe)
        if(!networkNodes.containsKey(repeater.id)) {
            networkNodes[repeater.id] = repeater
        }
        return true
    }

    fun getNodesNeedingConnection(fromApiCall: Boolean = false): List<DeviceInformationBleDevice> {
        val connectable = mutableListOf<DeviceID>()
        // for meatnet links
        repeatedProbeBleDevices.forEach {
            // should connect to this repeated probe
            if(shouldConnect(it, fromApiCall)) {
                // if don't already plan to connect to the repeater for the repeated probe
                if(!connectable.contains(it.id)) {
                    // add to the list of meatnet devices we need to connect to
                    connectable.add(it.id)
                }
            }
        }

        // should direct connect to the probe
        probeBleDevice?.let {
            if(shouldConnect(it, fromApiCall)) {
                connectable.add(it.id)
            }
        }

        return networkNodes.filter {
            entry: Map.Entry<DeviceID, DeviceInformationBleDevice> -> connectable.contains(entry.key)
        }.values.toList()
    }

    fun getNodesNeedingDisconnect(fromApiCall: Boolean = false): List<DeviceInformationBleDevice> {
        val disconnectable = mutableListOf<DeviceID>()
        // for meatnet links
        repeatedProbeBleDevices.forEach {
            // should disconnect from this repeated probe
            if(shouldDisconnect(it, fromApiCall)) {
                // if don't already plan to disconnect from the repeater for the repeated probe
                if(!disconnectable.contains(it.id)) {
                    // add to the list of meatnet devices we need to disconnect from
                    disconnectable.add(it.id)
                }
            }
        }

        // should disconnect form a direct connect to the probe
        probeBleDevice?.let {
            if(shouldDisconnect(it, fromApiCall)) {
                disconnectable.add(it.id)
            }
        }

        return networkNodes.filter {
            entry: Map.Entry<DeviceID, DeviceInformationBleDevice> -> disconnectable.contains(entry.key)
        }.values.toList()
    }

    fun shouldConnect(device: ProbeBleDeviceBase, fromApiCall: Boolean = false): Boolean {
        val canConnect = device.isDisconnected && device.isConnectable && !device.isInDfuMode
        if(settings.meatNetEnabled) {
            // connect to every connectable device when using meatnet
            return canConnect
        }
        else if(device is ProbeBleDevice) {
            // if not using meatnet, and the client app is asking us to connect
            if(fromApiCall) {
                device.shouldAutoReconnect = settings.autoReconnect
                return canConnect
            }

            // if not using meatnet, and this is a call from inside the framework then
            // we want to use the auto-reconnect setting.
            return device.shouldAutoReconnect && canConnect
        }

        // if not meatnet and not a probe
        return false
    }

    private fun shouldDisconnect(device: ProbeBleDeviceBase, fromApiCall: Boolean = false): Boolean {
        val canDisconnect = device.isConnected
        if(settings.meatNetEnabled) {
            // don't disconnect from meatnet
            return false
        }
        else if(device is ProbeBleDevice) {
            // if not using meatnet, and the client app is asking us to disconnect,
            // then turn off the auto-reconnect flag
            if(fromApiCall) {
                device.shouldAutoReconnect = false
            }

            // additionally disconnect if we can
            return canDisconnect
        }

        // if not meatnet and not a probe
        return false
    }

    fun shouldUpdateDataFromAdvertisingPacket(device: ProbeBleDeviceBase, advertisement: CombustionAdvertisingData): Boolean {
        return if(!USE_STATIC_LINK) advertisingArbitrator.shouldUpdate(device, advertisement) else debuggingWithStaticLink(device)
    }

    fun shouldUpdateDataFromProbeStatus(device: ProbeBleDeviceBase): Boolean {
        return arbitrateConnected(device)
    }

    fun shouldUpdateOnRemoteRssi(device: ProbeBleDeviceBase, rssi: Int): Boolean {
        return arbitrateConnected(device)
    }

    private fun arbitrateConnected(device: ProbeBleDeviceBase): Boolean {
        return if(!USE_STATIC_LINK) (device == preferredMeatNetLink) else debuggingWithStaticLink(device)
    }

    private fun debuggingWithStaticLink(device: ProbeBleDeviceBase): Boolean {
        return when(device) {
            is ProbeBleDevice -> true
            is RepeatedProbeBleDevice -> false
            else -> false
        }
    }
}
