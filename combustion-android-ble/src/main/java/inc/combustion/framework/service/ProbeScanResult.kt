/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeScanResult.kt
 * Author: https://github.com/miwright2
 *
 * MIT License
 *
 * Copyright (c) 2022. Combustion Inc.
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

import com.juul.kable.Advertisement
import inc.combustion.framework.ble.scanning.BaseAdvertisingData
import inc.combustion.framework.ble.scanning.CombustionAdvertisingData

/**
 * Data class for returning BLE scan results.
 *
 * @property serialNumber serial number of probe
 * @property temperatures normal temperature reading of probe
 * @property instantReadTemperature instant read temperature reading of probe
 */
data class ProbeScanResult(
    val serialNumber: String?,
    val mac: String?,
    val rssi: Int,
    val temperatures: ProbeTemperatures?,
    val instantReadTemperature: Double?
){
    companion object {
        fun fromAdvertisement(advertisement: Advertisement) : ProbeScanResult? {
            val data = BaseAdvertisingData.create(advertisement)

            data?.let {
                if(it !is CombustionAdvertisingData)
                    return null

                val serialNumber = it.probeSerialNumber
                val address = it.mac
                val rssi = it.rssi

                val temperatures = if (it.mode == ProbeMode.NORMAL)
                    it.probeTemperatures
                else
                    null

                val instantReadTemperature = if (it.mode == ProbeMode.INSTANT_READ)
                    it.probeTemperatures.values[0]
                else
                    null

                return ProbeScanResult(
                    serialNumber,
                    address,
                    rssi,
                    temperatures ,
                    instantReadTemperature
                )
            }

            return null
        }
    }
}