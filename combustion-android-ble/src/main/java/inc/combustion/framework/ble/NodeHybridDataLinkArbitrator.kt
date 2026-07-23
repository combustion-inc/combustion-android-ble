/*
 * Project: Combustion Inc. Android Framework
 * File: NodeHybridDataLinkArbitrator.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2026. Combustion Inc.
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
import inc.combustion.framework.ble.device.*
import inc.combustion.framework.ble.scanning.DeviceAdvertisingData
import inc.combustion.framework.service.DeviceConnectionState
import inc.combustion.framework.service.DeviceManager
import inc.combustion.framework.service.SessionInformation

internal abstract class NodeHybridDataLinkArbitrator<T : NodeHybridBleDevice, D : DeviceAdvertisingData> :
    DataLinkArbitrator<T, D> {

    // direct ble link to probe
    override var bleDevice: T? = null
        protected set

    // probe discovery timestamp
    override var directLinkDiscoverTimestamp: Long? = null

    override val preferredMeatNetLink: T?
        get() = directLink

    override val meatNetIsOutOfRange: Boolean
        get() = bleDevice?.isInRange != false

    private var currentStatus: SpecializedDeviceStatus? = null
    private var currentSessionInfo: SessionInformation? = null

    private var repeaterNodesGetter: (() -> List<NodeBleDevice>)? = null

    private val repeaterNodes: List<NodeBleDevice>
        get() = repeaterNodesGetter?.invoke() ?: emptyList()

    val connectedNodeLinks: List<NodeBleDevice>
        get() {
            return repeaterNodes.filter { it.isConnected }
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
        directConnectionAction: (T) -> Unit,
    ) {
        Log.d(LOG_TAG, "GaugeDataLinkArbitrator.finish()")
        directLink?.let {
            directConnectionAction(it)
        }

        repeaterNodesGetter = null
        bleDevice = null
    }

    override fun addDevice(
        device: T,
        baseDevice: DeviceInformationBleDevice,
    ): Boolean {
        if (bleDevice == null) {
            Log.d(
                LOG_TAG,
                "GaugeDataLinkArbitrator.addDevice(${device.serialNumber}, ${baseDevice.serialNumber}: ${baseDevice.id})"
            )
            bleDevice = device
            return true
        }

        return false
    }

    fun addRepeaterNodes(repeaterNodesGetter: (() -> List<NodeBleDevice>)) {
        this.repeaterNodesGetter = repeaterNodesGetter
    }

    override fun getPreferredConnectionState(state: DeviceConnectionState): T? {
        return bleDevice.takeIf { it?.connectionState == state }
    }

    override fun getNodesNeedingConnection(fromApiCall: Boolean): List<DeviceInformationBleDevice> {
        return bleDevice?.let {
            if (shouldConnect(it, fromApiCall)) {
                listOf(it.baseDevice)
            } else {
                emptyList()
            }
        } ?: emptyList()
    }

    override fun shouldConnect(device: T, fromApiCall: Boolean): Boolean {
        return device.isDisconnected && device.isConnectable && !device.isInDfuMode
    }

    override fun getNodesNeedingDisconnect(
        canDisconnectFromMeatNetDevices: Boolean,
    ): List<DeviceInformationBleDevice> {
        // Should disconnect from a direct connection to the probe
        return bleDevice?.let {
            if (shouldDisconnect(it)) {
                listOf(it.baseDevice)
            } else {
                emptyList()
            }
        } ?: emptyList()
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
        device: T,
        canDisconnectFromMeatNetDevices: Boolean = false,
    ): Boolean = device.isConnected

    /**
     * Sets the auto-reconnect flag for optional directly-connected probe to [shouldAutoReconnect].
     */
    override fun setShouldAutoReconnect(shouldAutoReconnect: Boolean) {
        bleDevice?.shouldAutoReconnect = shouldAutoReconnect
    }

    override fun shouldUpdateDataFromStatusForNormalMode(
        status: SpecializedDeviceStatus,
        sessionInfo: SessionInformation?,
    ): Boolean {
        // if status.max > current.max, then we want to update
        // TODO : when gauge has no sensor then always sequenceNumbers of 0 -- how then to determine if should update?
        val shouldUpdate = currentSessionInfo == null ||
            currentSessionInfo != sessionInfo ||
            status.maxSequenceNumber > (currentStatus?.maxSequenceNumber ?: UInt.MAX_VALUE)

        currentSessionInfo = sessionInfo
        // Only advance the bookkeeping on acceptance -- otherwise a stale/out-of-order status
        // (e.g. a slower mesh relay hop) would lower the bar for what counts as "newer", letting a
        // later, still-stale status get accepted as if it were an advance.
        if (shouldUpdate) {
            currentStatus = status
        }

        return shouldUpdate
    }

    override fun shouldUpdateDataFromAdvertisingPacket(
        device: T,
        advertisement: D,
    ): Boolean = true

    override fun shouldUpdateOnRemoteRssi(device: T): Boolean {
        return (device == preferredMeatNetLink)
    }
}