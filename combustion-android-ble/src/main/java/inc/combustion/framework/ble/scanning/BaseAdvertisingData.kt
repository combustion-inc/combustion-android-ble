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
import inc.combustion.framework.service.*

/**
 * Representation of Combustion device-specific advertising data.
 *
 * @param mac MAC address of the device.
 * @param name Bluetooth name of the device.
 * @param rssi RSSI of the device.
 * @param productType Combustion product type.
 * @param isConnectable Whether the device can be connected to.
 */
open class BaseAdvertisingData(
    val mac: String,
    val name: String,
    val rssi: Int,
    val productType: CombustionProductType,
    val isConnectable: Boolean,
) {
    companion object {
        private const val VENDOR_ID = 0x09C7
        private val PRODUCT_TYPE_RANGE = 0..0
        private val SERIAL_RANGE = 1..4
        private val TEMPERATURE_RANGE = 5..17
        private val MODE_COLOR_ID_RANGE = 18..18
        private val STATUS_RANGE = 19..19
        private val NETWORK_INFO_RANGE = 20..20

        /**
         * Factory method to create a BaseAdvertisingData instance from an
         * advertising packet received from the the DeviceScanner.
         *
         * @param advertisement Advertising packet
         * @return
         */
        fun create(advertisement: Advertisement): BaseAdvertisingData {
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

            var serial: UInt = 0u
            // Reverse the byte order (this is a little-endian packed bitfield)
            val rawSerialNumber = manufacturerData.copyOf().sliceArray(SERIAL_RANGE)
            for(byte in rawSerialNumber.reversed()) {
                serial = serial shl 8
                serial = serial or byte.toUInt()
            }

            val serialNumber = Integer.toHexString(serial.toInt()).uppercase().padStart(8, '0')

            val probeTemperatures = ProbeTemperatures.fromRawData(
                manufacturerData.copyOf().sliceArray(TEMPERATURE_RANGE)
            )

            // use mode and color ID if available
            val modeColorId = if (manufacturerData.size > 18)
                manufacturerData.copyOf().sliceArray(MODE_COLOR_ID_RANGE)[0]
            else
                null

            // use device status if available
            val deviceStatus = if (manufacturerData.size > 19)
                manufacturerData.copyOf().sliceArray(STATUS_RANGE)[0]
            else
                null

            // use hopCount if available (and turn it into an unsigned integer)
            val hopCount = if(type.isRepeater && manufacturerData.size > 20)
                HopCount.fromUByte(manufacturerData.copyOf().sliceArray(NETWORK_INFO_RANGE)[0]).hopCount
            else
                0u

            val probeColor = modeColorId?.let { ProbeColor.fromUByte(it) } ?: run { ProbeColor.COLOR1 }
            val probeID = modeColorId?.let { ProbeID.fromUByte(it) } ?: run { ProbeID.ID1 }
            val probeMode = modeColorId?.let { ProbeMode.fromUByte(it) } ?: run { ProbeMode.NORMAL }
            val batteryStatus = deviceStatus?.let { ProbeBatteryStatus.fromUByte(it) } ?: run { ProbeBatteryStatus.OK }
            val virtualSensors = deviceStatus?.let { ProbeVirtualSensors.fromDeviceStatus(it) } ?: run { ProbeVirtualSensors.DEFAULT }

            return when(type) {
                CombustionProductType.UNKNOWN -> {
                    base
                }
                CombustionProductType.PROBE, CombustionProductType.CHARGER, CombustionProductType.DISPLAY -> {
                    CombustionAdvertisingData(
                        mac = address,
                        name = name,
                        rssi = rssi,
                        productType = type,
                        isConnectable = isConnectable,
                        probeSerialNumber = serialNumber,
                        probeTemperatures = probeTemperatures,
                        probeID = probeID,
                        color = probeColor,
                        mode = probeMode,
                        batteryStatus = batteryStatus,
                        virtualSensors = virtualSensors,
                        hopCount = hopCount
                    )
                }
            }
        }
    }

    /**
     * Unique ID for the Combustion Device that produced this data class.
     */
    val id: DeviceID
        get() = mac

    override fun toString(): String {
        return "$mac $name $rssi $productType $isConnectable"
    }
}