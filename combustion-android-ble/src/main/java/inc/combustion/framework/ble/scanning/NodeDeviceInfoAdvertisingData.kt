/*
 * Project: Combustion Inc. Android Framework
 * File: NodeDeviceInfoAdvertisingData.kt
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

package inc.combustion.framework.ble.scanning

import inc.combustion.framework.isBitSet
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.utf8StringFromRange

/**
 * A Display's or Booster's own "Device Info" advertisement, which it interleaves with its MeatNet
 * Node advertisements (those carry the repeated probe data). See "Display Device Info" in the
 * Display BLE specification and "Booster Device Info" in the Booster BLE specification.
 *
 * Gives the Node's serial number and radio power before the phone connects to it. Older firmware
 * doesn't send it.
 */
internal class NodeDeviceInfoAdvertisingData(
    mac: String,
    name: String,
    rssi: Int,
    productType: CombustionProductType,
    isConnectable: Boolean,
    override val serialNumber: String,
    val highRadioPower: Boolean,
) : BaseAdvertisingData(
    mac = mac,
    name = name,
    rssi = rssi,
    productType = productType,
    isConnectable = isConnectable,
), DeviceAdvertisingData {

    companion object {
        // Offsets into the manufacturer data after the vendor ID: product type (0), serial number,
        // preferences, then reserved bytes.
        private val SERIAL_RANGE = 1..10
        private const val PREFERENCES_INDEX = 11
        private const val HIGH_RADIO_POWER_BIT = 0

        /**
         * Returns null if [manufacturerData] is too short to hold a serial number.
         */
        internal fun create(
            address: String,
            name: String,
            rssi: Int,
            isConnectable: Boolean,
            manufacturerData: UByteArray,
            type: CombustionProductType,
        ): NodeDeviceInfoAdvertisingData? {
            if (manufacturerData.size <= SERIAL_RANGE.last) return null

            return NodeDeviceInfoAdvertisingData(
                mac = address,
                name = name,
                rssi = rssi,
                productType = type,
                isConnectable = isConnectable,
                serialNumber = manufacturerData.utf8StringFromRange(SERIAL_RANGE),
                highRadioPower = manufacturerData.getOrNull(PREFERENCES_INDEX)
                    ?.isBitSet(HIGH_RADIO_POWER_BIT)
                    ?: false,
            )
        }
    }

    override fun toString(): String {
        return "${NodeDeviceInfoAdvertisingData::class.simpleName}: ${super.toString()} | $serialNumber"
    }
}
