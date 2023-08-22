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

import android.se.omapi.Session
import inc.combustion.framework.ble.device.*
import inc.combustion.framework.ble.device.ProbeBleDevice
import inc.combustion.framework.ble.device.ProbeBleDeviceBase
import inc.combustion.framework.ble.device.RepeatedProbeBleDevice
import inc.combustion.framework.ble.scanning.CombustionAdvertisingData
import inc.combustion.framework.service.DeviceConnectionState
import inc.combustion.framework.service.DeviceManager
import inc.combustion.framework.service.SessionInformation

internal class DataLinkArbitrator(
    private val settings: DeviceManager.Settings
) {
    companion object {
        private const val USE_STATIC_LINK: Boolean = false
        private const val USE_SIMPLE_MEATNET_CONNECTION_SCHEME = false
        private const val MULTINODE_PROBE_SETTLING_TIMEOUT_MS = 5000L
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

    // probe discovery timestamp
    var directLinkDiscoverTimestamp: Long? = null

    val directLink: ProbeBleDevice?
        get() {
            return if(probeBleDevice?.isConnected == true) probeBleDevice else null
        }

    val connectedNodeLinks: List<RepeatedProbeBleDevice>
        get() {
            return repeatedProbeBleDevices.filter { it.isConnected }
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

    val hasMeatNetRoute: Boolean
        get() {
            // only applies when using MeatNet
            if(!settings.meatNetEnabled) {
                return false
            }

            for(probe in repeatedProbeBleDevices)  {
                if(probe.isConnected && probe.connectionState != DeviceConnectionState.NO_ROUTE) {
                    return true
                }
            }

            return false
        }

    val meatNetIsOutOfRange: Boolean
        get() {
            val directInRange = probeBleDevice?.isInRange ?: true

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

    fun getPreferredConnectionState(state: DeviceConnectionState): ProbeBleDeviceBase? {
        // if not using MeatNet, then use the probe.
        if(!settings.meatNetEnabled) {
            probeBleDevice?.let {
                if(it.connectionState == state) {
                    return it
                }

                return null
            }
        }

        // if using MeatNet, prefer probe if state matches.
        probeBleDevice?.let {
            if(it.connectionState == state) {
                return it
            }
        }

        // otherwise (and using MeatNet), prefer the MeatNet link with the lowest hop count, that
        // matches the state.
        return repeatedProbeBleDevices.sortedWith(
            compareBy(RepeatedProbeBleDevice::hopCount, RepeatedProbeBleDevice::id)
        ).firstOrNull {
            it.connectionState == state
        }
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
            if(USE_SIMPLE_MEATNET_CONNECTION_SCHEME) {
                return canConnect
            }

            return shouldMultiNodeMeatNetConnect(device)
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


    private fun shouldMultiNodeMeatNetConnect(device: ProbeBleDeviceBase): Boolean {
        val canConnect = device.isDisconnected && device.isConnectable && !device.isInDfuMode

        // if this is from the network, then return if we can connect
        if(device is RepeatedProbeBleDevice) {
            return canConnect
        }

        // if we can connect to this direct probe
        if(canConnect) {

            // if we have a discovery timestamp
            directLinkDiscoverTimestamp?.let { timestamp ->

                // if the settling timeout has elapsed
                if(timestamp + MULTINODE_PROBE_SETTLING_TIMEOUT_MS < System.currentTimeMillis()) {

                    // check if we have a MeatNet link
                    val hasMeatNetLink = repeatedProbeBleDevices.firstOrNull {
                            it.isConnected && it.connectionState != DeviceConnectionState.NO_ROUTE
                        } != null

                    // connect only if we don't have a MeatNet link.
                    return !hasMeatNetLink
                }
            // otherwise, record the discovery timestamp and return false to wait for settling timeout.
            } ?: run {
                directLinkDiscoverTimestamp = System.currentTimeMillis()
            }
        }

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

    private var currentStatus: ProbeStatus? = null
    private var currentSessionInfo: SessionInformation? = null

    fun shouldUpdateDataFromProbeStatus(status: ProbeStatus, sessionInfo: SessionInformation?): Boolean {
        currentSessionInfo?.let {
            if(it != sessionInfo) {
                currentSessionInfo = sessionInfo
                currentStatus = status
                return true
            }

            // if status.max > current.max, then we want to update
            val shouldUpdate = status.maxSequenceNumber > (currentStatus?.maxSequenceNumber ?: UInt.MAX_VALUE)

            currentStatus = status

            return shouldUpdate
        }

        // don't yet have a session info, so want to update data
        return true
    }

    fun shouldUpdateOnRemoteRssi(device: ProbeBleDeviceBase): Boolean {
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
