/*
 * Project: Combustion Inc. Android Framework
 * File: EngineAdvertisingData.kt
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

import com.juul.kable.Identifier
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.EnginePreferences
import inc.combustion.framework.service.EngineStatusFlags
import inc.combustion.framework.service.SensorTemperature
import inc.combustion.framework.utf8StringFromRange

internal class EngineAdvertisingData(
    mac: String,
    name: String,
    rssi: Int,
    isConnectable: Boolean,
    override val serialNumber: String,
    val engineTemperature: SensorTemperature,
    val engineStatusFlags: EngineStatusFlags,
    val enginePreferences: EnginePreferences,
) : BaseAdvertisingData(
    mac = mac,
    name = name,
    rssi = rssi,
    productType = CombustionProductType.ENGINE,
    isConnectable = isConnectable,
), DeviceAdvertisingData {

    companion object {
        private val SERIAL_RANGE = 1..10
        private val TEMPERATURE_RANGE = 11..12
        private val STATUS_FLAGS_RANGE = 13..13
        private val PREFERENCES_RANGE = 14..14

        internal fun create(
            address: Identifier,
            name: String,
            rssi: Int,
            isConnectable: Boolean,
            manufacturerData: UByteArray,
        ): EngineAdvertisingData {
            val serialNumber = manufacturerData.utf8StringFromRange(SERIAL_RANGE)

            val temperature = SensorTemperature.fromRawDataStart(
                manufacturerData.copyOf().sliceArray(TEMPERATURE_RANGE)
            )

            val statusFlags: EngineStatusFlags = EngineStatusFlags.fromRawByte(
                manufacturerData.copyOf().sliceArray(STATUS_FLAGS_RANGE)[0]
            )

            val preferences = EnginePreferences.fromRawByte(
                manufacturerData.copyOf().sliceArray(PREFERENCES_RANGE)[0]
            )

            return EngineAdvertisingData(
                mac = address,
                name = name,
                rssi = rssi,
                isConnectable = isConnectable,
                serialNumber = serialNumber,
                engineTemperature = temperature,
                engineStatusFlags = statusFlags,
                enginePreferences = preferences,
            )
        }
    }

    override fun toString(): String {
        return "${EngineAdvertisingData::class.simpleName}: ${super.toString()} | $serialNumber"
    }
}