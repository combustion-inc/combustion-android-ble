/*
 * Project: Combustion Inc. Android Framework
 * File: DataLinkArbitrator.kt
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

import android.util.Log
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.device.DeviceInformationBleDevice
import inc.combustion.framework.ble.device.GaugeBleDevice
import inc.combustion.framework.ble.device.NodeBleDevice
import inc.combustion.framework.ble.device.ProbeBleDevice
import inc.combustion.framework.ble.device.RepeatedProbeBleDevice
import inc.combustion.framework.ble.device.SimulatedProbeBleDevice
import inc.combustion.framework.ble.scanning.GaugeAdvertisingData
import inc.combustion.framework.service.DeviceConnectionState
import inc.combustion.framework.service.DeviceManager
import inc.combustion.framework.service.SessionInformation

internal class GaugeDataLinkArbitrator :
    DataLinkArbitrator<GaugeBleDevice, GaugeAdvertisingData> {

    // direct ble link to probe
    override var bleDevice: GaugeBleDevice? = null
        private set

    // probe discovery timestamp
    override var directLinkDiscoverTimestamp: Long? = null

    override val preferredMeatNetLink: GaugeBleDevice?
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
        directConnectionAction: (GaugeBleDevice) -> Unit,
    ) {
        Log.d(LOG_TAG, "GaugeDataLinkArbitrator.finish()")
        directLink?.let {
            directConnectionAction(it)
        }

        repeaterNodesGetter = null
        bleDevice = null
    }

    override fun addDevice(
        device: GaugeBleDevice,
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

    override fun getPreferredConnectionState(state: DeviceConnectionState): GaugeBleDevice? {
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

    override fun shouldConnect(device: GaugeBleDevice, fromApiCall: Boolean): Boolean {
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
        device: GaugeBleDevice,
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
        currentSessionInfo?.let {
            if (it != sessionInfo) {
                currentSessionInfo = sessionInfo
                currentStatus = status
                return true
            }

            // if status.max > current.max, then we want to update
            // TODO : when gauge has no sensor then always sequenceNumbers of 0 -- how then to determine if should update?
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

    override fun shouldUpdateDataFromAdvertisingPacket(
        device: GaugeBleDevice,
        advertisement: GaugeAdvertisingData,
    ): Boolean = true

    override fun shouldUpdateOnRemoteRssi(device: GaugeBleDevice): Boolean {
        return (device == preferredMeatNetLink)
    }
}