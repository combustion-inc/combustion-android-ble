/*
 * Project: Combustion Inc. Android Framework
 * File: BootloadingAdvertisingData.kt
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

import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.dfu.DfuProductType

private const val LEGACY_PROBE_NAME = "CI Probe BL"
private const val LEGACY_DISPLAY_AND_CHARGER_NAME = "CI Timer BL"
private const val LEGACY_GAUGE_NAME = "CI Gauge BL"

private const val PROBE_NAME_PREFIX = "Thermom_DFU_"
private const val DISPLAY_NAME_PREFIX = "Display_DFU_"
private const val CHARGER_NAME_PREFIX = "Charger_DFU_"
private const val GAUGE_NAME_PREFIX = "Gauge_DFU_"

private val legacyBootLoadingDeviceNames =
    setOf(LEGACY_PROBE_NAME, LEGACY_DISPLAY_AND_CHARGER_NAME, LEGACY_GAUGE_NAME)
private val bootLoadingDevicePrefixes =
    setOf(PROBE_NAME_PREFIX, DISPLAY_NAME_PREFIX, CHARGER_NAME_PREFIX, GAUGE_NAME_PREFIX)

private val String.isLegacyBootLoading
    get() = legacyBootLoadingDeviceNames.contains(this)

val String.isBootLoading: Boolean
    get() = isLegacyBootLoading ||
            (bootLoadingDevicePrefixes.firstOrNull { this.startsWith(it) } != null)

internal class BootloadingAdvertisingData(
    mac: String,
    name: String,
    rssi: Int,
    productType: CombustionProductType,
    isConnectable: Boolean,
    val isLegacyBootloading: Boolean,
) : BaseAdvertisingData(
    mac = mac,
    name = name,
    rssi = rssi,
    productType = productType,
    isConnectable = isConnectable,
) {
    val dfuProductType: DfuProductType
        get() = if (!isLegacyBootloading) {
            when {
                name.startsWith(DISPLAY_NAME_PREFIX) -> DfuProductType.DISPLAY
                name.startsWith(CHARGER_NAME_PREFIX) -> DfuProductType.CHARGER
                name.startsWith(GAUGE_NAME_PREFIX) -> DfuProductType.GAUGE
                else -> DfuProductType.PROBE
            }
        } else {
            DfuProductType.fromCombustionProductType(productType)
        }

    val isLegacyDisplayOrCharger: Boolean
        get() = isLegacyBootloading && (name == LEGACY_DISPLAY_AND_CHARGER_NAME)

    fun standardId(): String = id.decrementMacAddress()

    private fun String.decrementMacAddress(): String {
        val sections = this.split(":").toMutableList() // Split MAC into sections

        val lastSection = sections.last().toInt(16) // Convert last section to an integer (hex)
        val decremented = (lastSection - 1).coerceAtLeast(0) // Ensure it doesn’t go below 0

        sections[sections.size - 1] =
            String.format("%02X", decremented) // Convert back to uppercase hex

        return sections.joinToString(":") // Reassemble the MAC address
    }

    companion object {
        fun create(
            mac: String,
            name: String,
            rssi: Int,
            isConnectable: Boolean,
        ): BootloadingAdvertisingData? {
            if (!name.isBootLoading) return null

            val isLegacyBootloading = name.isLegacyBootLoading

            val productType = if (isLegacyBootloading) {
                when (name) {
                    LEGACY_PROBE_NAME -> CombustionProductType.PROBE
                    LEGACY_GAUGE_NAME -> CombustionProductType.GAUGE
                    LEGACY_DISPLAY_AND_CHARGER_NAME -> CombustionProductType.NODE
                    else -> CombustionProductType.UNKNOWN
                }
            } else {
                when {
                    name.startsWith(DISPLAY_NAME_PREFIX) || name.startsWith(
                        CHARGER_NAME_PREFIX
                    ) -> CombustionProductType.NODE

                    name.startsWith(GAUGE_NAME_PREFIX) -> CombustionProductType.GAUGE
                    name.startsWith(PROBE_NAME_PREFIX) -> CombustionProductType.PROBE
                    else -> CombustionProductType.UNKNOWN
                }
            }

            return BootloadingAdvertisingData(
                mac = mac,
                name = name,
                rssi = rssi,
                productType = productType,
                isConnectable = isConnectable,
                isLegacyBootloading = isLegacyBootloading,
            )
        }
    }
}