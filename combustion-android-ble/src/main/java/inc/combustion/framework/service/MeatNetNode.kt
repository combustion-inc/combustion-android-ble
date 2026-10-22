/*
 * Project: Combustion Inc. Android Framework
 * File: MeatNetNode.kt
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

package inc.combustion.framework.service

import inc.combustion.framework.ble.device.DeviceID

/**
 * A physical MeatNet Node the phone has heard over Bluetooth: a Display, Booster, Gauge or Engine,
 * in its role as a Node (identity, radio and connection). Never a probe, the MeatNet Cloud or
 * this phone. The equivalent of iOS's `MeatNetNode`. See [DeviceManager.meatNetNodesFlow].
 *
 * A Gauge or Engine is listed here as well as being published as a [Gauge] or [Engine], which
 * carries its sensor data.
 *
 * [baseDevice] holds the same fields as for [Probe], [Gauge] and [Engine], with two differences:
 * - [Device.serialNumber] is empty until known: from the Node's advertising (Gauge, Engine, and
 *   Display and Booster firmware that sends Device Info advertisements), otherwise once the phone
 *   has connected to it.
 * - [Device.rssi] is smoothed, and rounded to whole dB, so the list doesn't change on every
 *   advertisement.
 *
 * @property highRadioPower whether the Node transmits at high radio power (+8 dBm), from its own
 * advertising; false if unknown.
 * @property isInProximityRange whether the Node is close to the phone, judged from [Device.rssi]
 * with the same thresholds as iOS: it becomes true above -48 dBm and false again below -55 dBm,
 * or -40 dBm and -47 dBm when [highRadioPower].
 * @property isConnectionRequested whether the app is keeping the phone connected to this Node; see
 * [DeviceManager.connectToNode].
 */
data class MeatNetNode(
    val baseDevice: Device,
    val highRadioPower: Boolean,
    val isInProximityRange: Boolean,
    val isConnectionRequested: Boolean,
) {
    val id: DeviceID get() = baseDevice.id
    val serialNumber: String get() = baseDevice.serialNumber
    val mac: String get() = baseDevice.mac
    val productType: CombustionProductType? get() = baseDevice.productType
    val connectionState: DeviceConnectionState get() = baseDevice.connectionState
    val rssi: Int get() = baseDevice.rssi
}
