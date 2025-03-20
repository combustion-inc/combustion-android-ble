/*
 * Project: Combustion Inc. Android Framework
 * File: CombustionAdvertisingData.kt
 * Author: httpsL//github.com/miwright2
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

import com.juul.kable.Identifier
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.HopCount
import inc.combustion.framework.service.OverheatingSensors
import inc.combustion.framework.service.ProbeBatteryStatus
import inc.combustion.framework.service.ProbeColor
import inc.combustion.framework.service.ProbeID
import inc.combustion.framework.service.ProbeMode
import inc.combustion.framework.service.ProbeTemperatures
import inc.combustion.framework.service.ProbeVirtualSensors

/**
 * Advertises probe data - source can be a probe or a repeater node.
 */
internal class ProbeAdvertisingData(
    mac: String,
    name: String,
    rssi: Int,
    productType: CombustionProductType,
    isConnectable: Boolean,
    override val serialNumber: String,
    val probeTemperatures: ProbeTemperatures,
    val probeID: ProbeID,
    val color: ProbeColor,
    val mode: ProbeMode,
    val batteryStatus: ProbeBatteryStatus,
    val virtualSensors: ProbeVirtualSensors,
    val overheatingSensors: OverheatingSensors,
    val hopCount: UInt = 0u,
) : BaseAdvertisingData(mac, name, rssi, productType, isConnectable), DeviceAdvertisingData {

    companion object {
        private val SERIAL_RANGE = 1..4
        private val TEMPERATURE_RANGE = 5..17
        private val MODE_COLOR_ID_RANGE = 18..18
        private val STATUS_RANGE = 19..19
        private val NETWORK_INFO_RANGE = 20..20

        fun create(
            address: Identifier,
            name: String,
            rssi: Int,
            isConnectable: Boolean,
            manufacturerData: UByteArray,
            type: CombustionProductType,
        ): ProbeAdvertisingData {
            var serial: UInt = 0u
            // Reverse the byte order (this is a little-endian packed bitfield)
            val rawSerialNumber =
                manufacturerData.copyOf().sliceArray(SERIAL_RANGE)
            for (byte in rawSerialNumber.reversed()) {
                serial = serial shl 8
                serial = serial or byte.toUInt()
            }

            // When a repeater doesn't have connections, it uses '0' in its advertising data. Pass
            // that on unchanged when we see it.
            val serialNumber = if (serial == 0u) {
                Integer.toHexString(serial.toInt()).uppercase()
            } else {
                Integer.toHexString(serial.toInt()).uppercase().padStart(8, '0')
            }

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
            val hopCount = if (type.isRepeater && manufacturerData.size > 20)
                HopCount.fromUByte(
                    manufacturerData.copyOf().sliceArray(NETWORK_INFO_RANGE)[0]
                ).hopCount
            else
                0u

            val probeColor =
                modeColorId?.let { ProbeColor.fromUByte(it) } ?: run { ProbeColor.COLOR1 }
            val probeID = modeColorId?.let { ProbeID.fromUByte(it) } ?: run { ProbeID.ID1 }
            val probeMode = modeColorId?.let { ProbeMode.fromUByte(it) } ?: run { ProbeMode.NORMAL }
            val batteryStatus = deviceStatus?.let { ProbeBatteryStatus.fromUByte(it) }
                ?: run { ProbeBatteryStatus.OK }
            val virtualSensors = deviceStatus?.let { ProbeVirtualSensors.fromDeviceStatus(it) }
                ?: run { ProbeVirtualSensors.DEFAULT }
            val overheatingSensors = OverheatingSensors.fromTemperatures(probeTemperatures)

            return ProbeAdvertisingData(
                mac = address,
                name = name,
                rssi = rssi,
                productType = type,
                isConnectable = isConnectable,
                serialNumber = serialNumber,
                probeTemperatures = probeTemperatures,
                probeID = probeID,
                color = probeColor,
                mode = probeMode,
                batteryStatus = batteryStatus,
                virtualSensors = virtualSensors,
                overheatingSensors = overheatingSensors,
                hopCount = hopCount,
            )
        }
    }

    override fun toString(): String {
        return "${super.toString()} | $serialNumber $mode $hopCount"
    }
}
