/*
 * Project: Combustion Inc. Android Framework
 * File: BaseAdvertisingData.kt
 * Author: http://github.com/miwright2
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

package inc.combustion.framework.ble.scanning

import com.juul.kable.Advertisement
import inc.combustion.framework.ble.device.DeviceID
import inc.combustion.framework.service.CombustionProductType

/**
 * Representation of Combustion device-specific advertising data.
 *
 * @param mac MAC address of the device.
 * @param name Bluetooth name of the device.
 * @param rssi RSSI of the device.
 * @param productType Combustion product type.
 * @param isConnectable Whether the device can be connected to.
 */
interface AdvertisingData {
    val mac: String
    val name: String
    val rssi: Int
    val productType: CombustionProductType
    val isConnectable: Boolean

    companion object {
        private const val VENDOR_ID = 0x09C7
        private val PRODUCT_TYPE_RANGE = 0..0

        /**
         * Factory method to create a BaseAdvertisingData instance from an
         * advertising packet received from the the DeviceScanner.
         *
         * @param advertisement Advertising packet
         * @return
         */
        fun create(advertisement: Advertisement): AdvertisingData {
            val address = advertisement.identifier
            val name = advertisement.name ?: ""
            val rssi = advertisement.rssi
            val isConnectable = advertisement.isConnectable ?: false
            val base = BaseAdvertisingData(
                mac = address,
                name = name,
                rssi = rssi,
                productType = CombustionProductType.UNKNOWN,
                isConnectable = isConnectable
            )

            // get Combustion's manufacturer data, if not available create the base class.
            val manufacturerData = advertisement.manufacturerData(VENDOR_ID)?.toUByteArray()
                ?: run { return base }

            val type = CombustionProductType.fromUByte(
                manufacturerData.copyOf().sliceArray(PRODUCT_TYPE_RANGE)[0]
            )

            return when (type) {
                CombustionProductType.UNKNOWN -> base
                CombustionProductType.PROBE, CombustionProductType.NODE -> ProbeAdvertisingData.create(
                    address = address,
                    name = name,
                    rssi = rssi,
                    isConnectable = isConnectable,
                    manufacturerData = manufacturerData,
                    type = type,
                )

                CombustionProductType.GAUGE -> GaugeAdvertisingData.create(
                    address = address,
                    name = name,
                    rssi = rssi,
                    isConnectable = isConnectable,
                    manufacturerData = manufacturerData,
                )
            }
        }
    }

    /**
     * Unique ID for the Combustion Device that produced this data class.
     */
    val id: DeviceID
        get() = mac

    val isRepeater: Boolean
        get() = productType.isRepeater
}