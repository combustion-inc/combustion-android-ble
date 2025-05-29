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

import inc.combustion.framework.ble.device.DeviceInformationBleDevice
import inc.combustion.framework.ble.device.UartCapableSpecializedDevice
import inc.combustion.framework.ble.scanning.DeviceAdvertisingData
import inc.combustion.framework.service.DeviceConnectionState
import inc.combustion.framework.service.SessionInformation

internal interface DataLinkArbitrator<T : UartCapableSpecializedDevice, D : DeviceAdvertisingData> {
    companion object {
        const val USE_STATIC_LINK: Boolean = false
        const val USE_SIMPLE_MEATNET_CONNECTION_SCHEME = false
        const val MULTINODE_PROBE_SETTLING_TIMEOUT_MS = 5000L
    }

    val bleDevice: T?

    val directLinkDiscoverTimestamp: Long?

    val directLink: T?
        get() {
            return if (bleDevice?.isConnected == true) bleDevice else null
        }

    val preferredMeatNetLink: T?

    val meatNetIsOutOfRange: Boolean

    /**
     * Cleans up all resources associated with this arbitrator.
     *
     * In an effort to decouple BLE connection and disconnection functionality from the arbitrator
     * class, this method takes two lambdas as parameters: [nodeAction] and
     * [directConnectionAction]. These lambdas are called for each node and the direct connection,
     * and are expected to handle the cleanup/disconnection of each device. The caller may want to
     * consider calling [DeviceInformationBleDevice.finish] on each node, and
     * [UartCapableSpecializedDevice.disconnect] on the direct connection.
     */
    fun finish(
        nodeAction: (DeviceInformationBleDevice) -> Unit,
        directConnectionAction: (T) -> Unit,
    )

    fun addDevice(device: T, baseDevice: DeviceInformationBleDevice): Boolean

    fun getPreferredConnectionState(state: DeviceConnectionState): T?

    fun shouldConnect(device: T, fromApiCall: Boolean = false): Boolean

    fun getNodesNeedingConnection(fromApiCall: Boolean = false): List<DeviceInformationBleDevice>

    fun getNodesNeedingDisconnect(
        canDisconnectFromMeatNetDevices: Boolean = false,
    ): List<DeviceInformationBleDevice>

    fun setShouldAutoReconnect(shouldAutoReconnect: Boolean)

    fun shouldUpdateDataFromAdvertisingPacket(
        device: T,
        advertisement: D,
    ): Boolean

    fun shouldUpdateOnRemoteRssi(device: T): Boolean

    fun shouldUpdateDataFromStatusForNormalMode(
        status: SpecializedDeviceStatus,
        sessionInfo: SessionInformation?,
    ): Boolean
}