/*
 * Project: Combustion Inc. Android Framework
 * File: GaugeBleDevice.kt
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
import inc.combustion.framework.ble.scanning.GaugeAdvertisingData

internal class GaugeBle(
    override val parent: NodeBleDevice,
    private var gaugeAdvertisingData: GaugeAdvertisingData,
    private val uart: UartBleDevice,
) : GaugeBleBase(), NodeAccessory {
    override val id: DeviceID = gaugeAdvertisingData.mac
    override val serialNumber: String = gaugeAdvertisingData.serialNumber
    override val isConnected: Boolean
        get() = parent.isConnected

    override fun connect() = parent.connect()
    override fun disconnect() = parent.disconnect()

    override fun observeAdvertisingPackets(
        serialNumberFilter: String,
        macFilter: String,
        callback: (suspend (advertisement: DeviceAdvertisingData) -> Unit)?,
    ) {
        uart.observeAdvertisingPackets(
            jobKey = serialNumberFilter,
            filter = { advertisement ->
                macFilter == advertisement.mac && advertisement.serialNumber == serialNumberFilter
            }
        ) { advertisement ->
            if (advertisement is GaugeAdvertisingData) {
                callback?.let {
                    gaugeAdvertisingData = advertisement
                    it(advertisement)
                }
            }
        }
    }
}