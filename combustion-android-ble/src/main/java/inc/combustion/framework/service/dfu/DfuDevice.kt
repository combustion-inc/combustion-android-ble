/*
 * Project: Combustion Inc. Android Framework
 * File: DfuDevice.kt
 * Author:
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

package inc.combustion.framework.service.dfu

import inc.combustion.framework.ble.device.DeviceID
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.DeviceConnectionState
import inc.combustion.framework.service.FirmwareVersion

/**
 * Representation of a Combustion device that's capable of performing a firmware upgrade.
 *
 * @param serialNumber The device serial number.
 * @param mac The device's MAC address.
 * @param fwVersion The device's firmware version.
 * @param hwRevision The device's hardware revision.
 * @param rssi The BLE RSSI value.
 * @param isDirectConnection Whether this device representation is obtained through a direct
 *                           connection to the device or if it's rebroadcast through a MeatNet node.
 * @param productType The device product type.
 * @param connectionState The device's current BLE connection state.
 */
data class DfuDevice(
    val serialNumber: String = "",
    val mac: String,
    val fwVersion: FirmwareVersion? = null,
    val hwRevision: String? = null,
    val rssi: Int = 0,
    val isDirectConnection: Boolean? = null,
    val productType: CombustionProductType? = null,
    val connectionState: DeviceConnectionState = DeviceConnectionState.DISCONNECTED,
) {
    val id: DeviceID = mac

    companion object {
        fun createFromID(id: DeviceID): DfuDevice {
            return DfuDevice(mac = id)
        }
    }
}