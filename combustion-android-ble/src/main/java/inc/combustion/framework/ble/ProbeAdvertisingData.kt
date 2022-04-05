/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeAdvertisingData.kt
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
package inc.combustion.framework.ble

import android.os.Build
import android.bluetooth.le.ScanResult
import com.juul.kable.Advertisement
import inc.combustion.framework.service.ProbeTemperatures
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

/**
 * Data object for probe's advertising packet.
 *
 * @property name Bluetooth name
 * @property mac Bluetooth MAC address
 * @property rssi Bluetooth RSSI
 * @property serialNumber Device serial number
 * @property type Device type
 * @property isConnectable True if the device is currently connectable
 * @property probeTemperatures Probe's current temperature values.
 */
internal data class ProbeAdvertisingData (
    val name: String,
    val mac: String,
    val rssi: Int,
    val serialNumber: String,
    val type: CombustionProductType,
    val isConnectable: Boolean,
    val probeTemperatures: ProbeTemperatures
) {
    companion object {
        private const val VENDOR_ID = 0x09C7
        private val PRODUCT_TYPE_RANGE = 0..0
        private val SERIAL_RANGE = 1..4
        private val TEMPERATURE_RANGE = 5..17

        fun fromAdvertisement(advertisement: Advertisement): ProbeAdvertisingData? {
            // Check vendor ID of Manufacturing data
            val manufacturerData = advertisement.manufacturerData(VENDOR_ID)?.toUByteArray() ?: return null

            val scanResult = advertisement.getPrivateProperty<Advertisement, ScanResult>("scanResult")

            var serial: UInt = 0u
            // Reverse the byte order (this is a little-endian packed bitfield)
            val rawSerialNumber = manufacturerData.copyOf().sliceArray(SERIAL_RANGE)
            for(byte in rawSerialNumber.reversed()) {
                serial = serial shl 8
                serial = serial or byte.toUInt()
            }

            val serialNumber = Integer.toHexString(serial.toInt()).uppercase()
            val type = CombustionProductType.fromUByte(
                manufacturerData.copyOf().sliceArray(PRODUCT_TYPE_RANGE)[0]
            )

            val probeTemperatures = ProbeTemperatures.fromRawData(manufacturerData.copyOf().sliceArray(TEMPERATURE_RANGE))

            // API level 26 (Andoird 8) and Higher
            return if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ProbeAdvertisingData(
                    name = advertisement.name ?: "Unknown",
                    mac = advertisement.address,
                    rssi = advertisement.rssi,
                    serialNumber,
                    type,
                    isConnectable = scanResult?.isConnectable ?: false,
                    probeTemperatures
                )
            // Lower than API level 26, always return true for isConnectable
            } else {
                ProbeAdvertisingData(
                    name = advertisement.name ?: "Unknown",
                    mac = advertisement.address,
                    rssi = advertisement.rssi,
                    serialNumber,
                    type,
                    isConnectable = true,
                    probeTemperatures
                )
            }

        }
    }

    enum class CombustionProductType(val type: UByte) {
        UNKNOWN(0x00u),
        PROBE(0x01u),
        NODE(0x02u);

        companion object {
            fun fromUByte(byte: UByte) : CombustionProductType {
                return when(byte.toUInt()) {
                    0x01u -> PROBE
                    0x02u -> NODE
                    else -> UNKNOWN
                }
            }
        }
    }
}

/**
 * This extension gets access to the ScanResult to return the isConnectable
 * state of the advertisement.
 *
 * See related feature request:
 * https://github.com/JuulLabs/kable/issues/210
 *
 * @return value of Advertisement.ScanResult.isConnectable
 */
@Suppress("UNCHECKED_CAST")
private inline fun <reified T : Any, R> T.getPrivateProperty(name: String): R? =
    T::class
        .memberProperties
        .firstOrNull { it.name == name }
        ?.apply { isAccessible = true }
        ?.get(this) as? R