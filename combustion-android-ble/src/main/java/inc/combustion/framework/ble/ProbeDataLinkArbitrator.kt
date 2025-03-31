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
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.DataLinkArbitrator.Companion.MULTINODE_PROBE_SETTLING_TIMEOUT_MS
import inc.combustion.framework.ble.DataLinkArbitrator.Companion.USE_SIMPLE_MEATNET_CONNECTION_SCHEME
import inc.combustion.framework.ble.DataLinkArbitrator.Companion.USE_STATIC_LINK
import inc.combustion.framework.ble.device.DeviceID
import inc.combustion.framework.ble.device.DeviceInformationBleDevice
import inc.combustion.framework.ble.device.ProbeBleDevice
import inc.combustion.framework.ble.device.ProbeBleDeviceBase
import inc.combustion.framework.ble.device.RepeatedProbeBleDevice
import inc.combustion.framework.ble.device.SimulatedProbeBleDevice
import inc.combustion.framework.ble.scanning.ProbeAdvertisingData
import inc.combustion.framework.service.DeviceConnectionState
import inc.combustion.framework.service.DeviceManager
import inc.combustion.framework.service.ProbeMode
import inc.combustion.framework.service.SessionInformation

// Number of seconds to ignore other lower-priority (higher hop count) sources of information for Instant Read
private const val INSTANT_READ_IDLE_TIMEOUT = 1000L

internal class ProbeDataLinkArbitrator(
    private val settings: DeviceManager.Settings,
    private val instantReadIdleMonitor: IdleMonitor = IdleMonitor(),
    private var currentHopCount: UInt? = null,
) : DataLinkArbitrator<ProbeBleDeviceBase, ProbeAdvertisingData> {

    // meatnet network nodes
    private val networkNodes = hashMapOf<DeviceID, DeviceInformationBleDevice>()

    // advertising data arbitration
    private val advertisingArbitrator = AdvertisingArbitrator()

    // direct ble link to probe
    override var bleDevice: ProbeBleDeviceBase? = null
        private set

    // meatnet links to probe
    val repeatedProbeBleDevices = mutableListOf<RepeatedProbeBleDevice>()

    // probe discovery timestamp
    override var directLinkDiscoverTimestamp: Long? = null

    val connectedNodeLinks: List<RepeatedProbeBleDevice>
        get() {
            return repeatedProbeBleDevices.filter { it.isConnected }
        }

    override val preferredMeatNetLink: ProbeBleDeviceBase?
        get() {
            // If meatnet is not enabled, then return the probe connection
            if (!settings.meatNetEnabled) {
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
            if (!settings.meatNetEnabled) {
                return false
            }

            for (probe in repeatedProbeBleDevices) {
                if (probe.isConnected && probe.connectionState != DeviceConnectionState.NO_ROUTE) {
                    return true
                }
            }

            return false
        }

    override val meatNetIsOutOfRange: Boolean
        get() {
            val directInRange = bleDevice?.isInRange ?: true

            return repeatedProbeBleDevices.firstOrNull {
                it.isInRange
            } == null && !directInRange
        }

    /**
     * Cleans up all resources associated with this arbitrator.
     *
     * In an effort to decouple BLE connection and disconnection functionality from the arbitrator
     * class, this method takes two lambdas as parameters: [nodeAction] and
     * [directConnectionAction]. These lambdas are called for each node and the direct connection,
     * and are expected to handle the cleanup/disconnection of each device. The caller may want to
     * consider calling [DeviceInformationBleDevice.finish] on each node, and
     * [ProbeBleDevice.disconnect] on the direct connection.
     */
    override fun finish(
        nodeAction: (DeviceInformationBleDevice) -> Unit,
        directConnectionAction: (ProbeBleDeviceBase) -> Unit,
    ) {
        Log.d(LOG_TAG, "DataLinkArbitrator.finish()")
        networkNodes.values
            .forEach { nodeAction(it) }

        directLink?.let {
            directConnectionAction(it)
        }

        repeatedProbeBleDevices.clear()
        networkNodes.clear()
        bleDevice = null
    }

    override fun addDevice(device: ProbeBleDeviceBase, baseDevice: DeviceInformationBleDevice): Boolean {
        if (bleDevice == null) {
            Log.d(
                LOG_TAG,
                "DataLinkArbitrator.addProbe(${device.serialNumber}, ${baseDevice.serialNumber}: ${baseDevice.id})"
            )
            bleDevice = device
            networkNodes[baseDevice.id] = baseDevice
            return true
        }

        return false
    }

    fun addRepeatedProbe(
        repeatedProbe: RepeatedProbeBleDevice,
        repeater: DeviceInformationBleDevice,
    ): Boolean {
        Log.d(
            LOG_TAG,
            "DataLinkArbitrator.addRepeatedProbe(${repeatedProbe.serialNumber}, ${repeater.id})"
        )
        repeatedProbeBleDevices.add(repeatedProbe)
        if (!networkNodes.containsKey(repeater.id)) {
            networkNodes[repeater.id] = repeater
        }
        return true
    }

    override fun getPreferredConnectionState(state: DeviceConnectionState): ProbeBleDeviceBase? {
        // if not using MeatNet, then use the probe.
        if (!settings.meatNetEnabled) {
            bleDevice?.let {
                if (it.connectionState == state) {
                    return it
                }

                return null
            }
        }

        // if using MeatNet, prefer probe if state matches.
        bleDevice?.let {
            if (it.connectionState == state) {
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
            if (shouldConnect(it, fromApiCall)) {
                // if don't already plan to connect to the repeater for the repeated probe
                if (!connectable.contains(it.id)) {
                    // add to the list of meatnet devices we need to connect to
                    connectable.add(it.id)
                }
            }
        }

        // should direct connect to the probe
        bleDevice?.let {
            if (shouldConnect(it, fromApiCall)) {
                connectable.add(it.id)
            }
        }

        return networkNodes.filter { entry: Map.Entry<DeviceID, DeviceInformationBleDevice> ->
            connectable.contains(entry.key)
        }.values.toList()
    }

    fun getNodesNeedingDisconnect(
        canDisconnectFromMeatNetDevices: Boolean = false,
    ): List<DeviceInformationBleDevice> {
        val nodesToDisconnect = repeatedProbeBleDevices
            .filter { shouldDisconnect(it, canDisconnectFromMeatNetDevices) }
            .map { it.id }
            .toMutableSet()

        // Should disconnect from a direct connection to the probe
        bleDevice?.let {
            if (shouldDisconnect(it)) {
                nodesToDisconnect.add(it.id)
            }
        }

        Log.d(LOG_TAG, "DataLinkArbitrator.getNodesNeedingDisconnect: $nodesToDisconnect")

        return networkNodes.filter { entry: Map.Entry<DeviceID, DeviceInformationBleDevice> ->
            nodesToDisconnect.contains(entry.key)
        }.values.toList()
    }

    override fun shouldConnect(device: ProbeBleDeviceBase, fromApiCall: Boolean): Boolean {
        val canConnect = device.isDisconnected && device.isConnectable && !device.isInDfuMode
        if (settings.meatNetEnabled) {
            // connect to every connectable device when using meatnet
            if (USE_SIMPLE_MEATNET_CONNECTION_SCHEME) {
                return canConnect
            }

            return shouldMultiNodeMeatNetConnect(device)
        } else if (device is ProbeBleDevice) {
            // if not using meatnet, and the client app is asking us to connect
            if (fromApiCall) {
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
        if (device is RepeatedProbeBleDevice) {
            return canConnect
        }

        // if we can connect to this direct probe
        if (canConnect) {

            // if we have a discovery timestamp
            directLinkDiscoverTimestamp?.let { timestamp ->

                // if the settling timeout has elapsed
                if (timestamp + MULTINODE_PROBE_SETTLING_TIMEOUT_MS < System.currentTimeMillis()) {

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

    /**
     * Returns whether or not we should disconnect from [device].
     *
     * The conditions for disconnection are different for different types of devices:
     * - [ProbeBleDevice]s can always be disconnected from and will return true if currently
     *   connected.
     * - [SimulatedProbeBleDevice]s can always be disconnected from and will return true.
     * - A [RepeatedProbeBleDevice], by default, will return false if
     *   [DeviceManager.Settings.meatNetEnabled] is true--this can be overridden by setting
     *   [canDisconnectFromMeatNetDevices] to true.
     */
    private fun shouldDisconnect(
        device: ProbeBleDeviceBase,
        canDisconnectFromMeatNetDevices: Boolean = false,
    ): Boolean {
        when (device) {
            is ProbeBleDevice -> {
                // We can always disconnect from a directly-connected probe.
                return device.isConnected
            }

            is RepeatedProbeBleDevice -> {
                return (canDisconnectFromMeatNetDevices || !settings.meatNetEnabled)
            }

            is SimulatedProbeBleDevice -> {
                return true
            }
        }

        return false
    }

    /**
     * Sets the auto-reconnect flag for optional directly-connected probe to [shouldAutoReconnect].
     */
    override fun setShouldAutoReconnect(shouldAutoReconnect: Boolean) {
        bleDevice?.shouldAutoReconnect = shouldAutoReconnect
    }

    override fun shouldUpdateDataFromAdvertisingPacket(
        device: ProbeBleDeviceBase,
        advertisement: ProbeAdvertisingData,
    ): Boolean {
        return if (!USE_STATIC_LINK) advertisingArbitrator.shouldUpdate(
            device,
            advertisement
        ) else debuggingWithStaticLink(device)
    }

    private var currentStatus: ProbeStatus? = null
    private var currentSessionInfo: SessionInformation? = null

    fun shouldUpdateDataFromProbeStatus(
        status: ProbeStatus,
        sessionInfo: SessionInformation?,
        hopCount: UInt?,
    ): Boolean = when (status.mode) {
        ProbeMode.INSTANT_READ -> shouldUpdateDataFromProbeStatusForInstantReadMode(hopCount)
        else -> shouldUpdateDataFromProbeStatusForNormalMode(status, sessionInfo)
    }

    private fun shouldUpdateDataFromProbeStatusForNormalMode(
        status: ProbeStatus,
        sessionInfo: SessionInformation?,
    ): Boolean {
        currentSessionInfo?.let {
            if (it != sessionInfo) {
                currentSessionInfo = sessionInfo
                currentStatus = status
                return true
            }

            // if status.max > current.max, then we want to update
            val shouldUpdate =
                status.maxSequenceNumber > (currentStatus?.maxSequenceNumber ?: UInt.MAX_VALUE)

            currentStatus = status

            return shouldUpdate
        } ?: run {
            currentSessionInfo = sessionInfo
            currentStatus = status
        }

        // don't yet have a session info, so want to update data
        return true
    }

    private fun shouldUpdateDataFromProbeStatusForInstantReadMode(hopCount: UInt?): Boolean {
        val immutableCurrentHopCount = currentHopCount
        return when {
            // If hopCount is nil, this is direct from a Probe and we should always update.
            hopCount == null -> true
            // This hop count is equal or better priority than the last, so update.
            (immutableCurrentHopCount != null) && (hopCount <= immutableCurrentHopCount) -> true
            // If we haven't received Instant Read data for more than the lockout period, we should always update.
            else -> instantReadIdleMonitor.isIdle(INSTANT_READ_IDLE_TIMEOUT)
        }.also { shouldUpdate ->
            if (shouldUpdate) {
                instantReadIdleMonitor.activity()
                currentHopCount = hopCount
            }
        }
    }

    override fun shouldUpdateOnRemoteRssi(device: ProbeBleDeviceBase): Boolean {
        return if (!USE_STATIC_LINK) (device == preferredMeatNetLink) else debuggingWithStaticLink(
            device
        )
    }

    private fun debuggingWithStaticLink(device: ProbeBleDeviceBase): Boolean {
        return when (device) {
            is ProbeBleDevice -> true
            is RepeatedProbeBleDevice -> false
            else -> false
        }
    }
}
