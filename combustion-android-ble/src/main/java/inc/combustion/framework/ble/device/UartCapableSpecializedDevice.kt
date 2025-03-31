/*
 * Project: Combustion Inc. Android Framework
 * File: UartCapableSpecializedDevice.kt
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

package inc.combustion.framework.ble.device

import inc.combustion.framework.ble.scanning.DeviceAdvertisingData
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.DeviceConnectionState
import inc.combustion.framework.service.FirmwareVersion
import inc.combustion.framework.service.ModelInformation

internal interface UartCapableSpecializedDevice : UartCapableDevice {
    val id: DeviceID
    val serialNumber: String

    // mac
    val mac: String

    // device information service
    val deviceInfoSerialNumber: String?
    val deviceInfoFirmwareVersion: FirmwareVersion?
    val deviceInfoHardwareRevision: String?
    val deviceInfoModelInformation: ModelInformation?

    // auto-reconnect flag
    var shouldAutoReconnect: Boolean

    // meatnet
    val hopCount: UInt
    val productType: CombustionProductType

    // advertising updates
    fun observeAdvertisingPackets(
        serialNumberFilter: String,
        macFilter: String,
        callback: (suspend (advertisement: DeviceAdvertisingData) -> Unit)? = null
    )

    fun observeRemoteRssi(callback: (suspend (rssi: Int) -> Unit)? = null)
    fun observeOutOfRange(timeout: Long, callback: (suspend () -> Unit)? = null)
    fun observeConnectionState(callback: (suspend (newConnectionState: DeviceConnectionState) -> Unit)? = null)

    // device information service
    suspend fun readSerialNumber()
    suspend fun readFirmwareVersion()
    suspend fun readHardwareRevision()
    suspend fun readModelInformation()

    fun readFirmwareVersionAsync(callback: (FirmwareVersion) -> Unit)
    fun readHardwareRevisionAsync(callback: (String) -> Unit)
    fun readModelInformationAsync(callback: (ModelInformation) -> Unit)
}