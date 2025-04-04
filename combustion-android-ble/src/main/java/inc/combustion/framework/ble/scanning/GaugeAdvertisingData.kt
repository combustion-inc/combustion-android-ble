/*
 * Project: Combustion Inc. Android Framework
 * File: GaugeAdvertisingData.kt
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

package inc.combustion.framework.ble.scanning

import com.juul.kable.Identifier
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.GaugeStatusFlags
import inc.combustion.framework.service.HighLowAlarmStatus
import inc.combustion.framework.service.Temperature
import inc.combustion.framework.toPercentage
import inc.combustion.framework.utf8StringFromRange

/**
 * Advertises gauge data.
 */
internal class GaugeAdvertisingData(
    mac: String,
    name: String,
    rssi: Int,
    isConnectable: Boolean,
    override val serialNumber: String,
    val gaugeTemperature: Temperature,
    val gaugeStatusFlags: GaugeStatusFlags,
    val batteryPercentage: Int,
    val highLowAlarmStatus: HighLowAlarmStatus,
) : BaseAdvertisingData(
    mac = mac,
    name = name,
    rssi = rssi,
    productType = CombustionProductType.GAUGE,
    isConnectable = isConnectable,
), DeviceAdvertisingData {

    companion object {
        private val SERIAL_RANGE = 1..10
        private val TEMPERATURE_RANGE = 11..12
        private val STATUS_FLAGS_RANGE = 13..13
        private val BATTERY_PERCENTAGE_RANGE = 14..14
        private val HIGH_LOW_ALARM_RANGE = 15..18

        internal fun create(
            address: Identifier,
            name: String,
            rssi: Int,
            isConnectable: Boolean,
            manufacturerData: UByteArray,
        ): GaugeAdvertisingData {
            val serialNumber = manufacturerData.utf8StringFromRange(SERIAL_RANGE)

            val gaugeTemperature = Temperature.fromRawDataStart(
                manufacturerData.copyOf().sliceArray(TEMPERATURE_RANGE)
            )

            val gaugeStatusFlags: GaugeStatusFlags = GaugeStatusFlags.fromRawByte(
                manufacturerData.copyOf().sliceArray(STATUS_FLAGS_RANGE)[0]
            )

            val batteryPercentage: Int = manufacturerData.copyOf()
                .sliceArray(BATTERY_PERCENTAGE_RANGE)[0].toPercentage()

            val highLowAlarmStatus: HighLowAlarmStatus = HighLowAlarmStatus.fromRawData(
                manufacturerData.copyOf().sliceArray(HIGH_LOW_ALARM_RANGE)
            )

            return GaugeAdvertisingData(
                mac = address,
                name = name,
                rssi = rssi,
                isConnectable = isConnectable,
                serialNumber = serialNumber,
                gaugeTemperature = gaugeTemperature,
                gaugeStatusFlags = gaugeStatusFlags,
                batteryPercentage = batteryPercentage,
                highLowAlarmStatus = highLowAlarmStatus,
            )
        }
    }

    override fun toString(): String {
        return "${GaugeAdvertisingData::class.simpleName}: ${super.toString()} | $serialNumber"
    }
}