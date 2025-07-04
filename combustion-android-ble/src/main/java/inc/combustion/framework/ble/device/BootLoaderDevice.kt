/*
 * Project: Combustion Inc. Android Framework
 * File: BootLoaderDevice.kt
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

import android.content.Context
import android.net.Uri
import inc.combustion.framework.ble.dfu.PerformDfuDelegate
import inc.combustion.framework.ble.scanning.AdvertisingData
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

class BootLoaderDevice(
    val performDfuDelegate: PerformDfuDelegate,
    val advertisingData: AdvertisingData,
) {
    val standardId: String = advertisingData.standardId()

    val dfuProductType: DfuProductType
        get() = performDfuDelegate.state.value.device.dfuProductType.takeIf { it != DfuProductType.UNKNOWN }
            ?: if (advertisingData.isLegacyBootLoading) {
                when {
                    advertisingData.name == LEGACY_PROBE_NAME -> DfuProductType.PROBE
                    // can be either charger or display - initially assume charger and retryProductType() will return display
                    else -> DfuProductType.CHARGER
                }
            } else {
                when {
                    advertisingData.name.startsWith(DISPLAY_NAME_PREFIX) -> DfuProductType.DISPLAY
                    advertisingData.name.startsWith(CHARGER_NAME_PREFIX) -> DfuProductType.CHARGER
                    advertisingData.name.startsWith(GAUGE_NAME_PREFIX) -> DfuProductType.GAUGE
                    else -> DfuProductType.PROBE
                }
            }

    private val firstSeenTimestamp: Long = System.currentTimeMillis()

    constructor(
        context: Context,
        advertisingData: AdvertisingData,
    ) : this(
        PerformDfuDelegate(context, advertisingData, advertisingData.standardId()),
        advertisingData
    )

    fun millisSinceFirstSeen(): Long =
        System.currentTimeMillis() - firstSeenTimestamp

    fun performDfu(file: Uri, completionHandler: (Boolean) -> Unit) {
        performDfuDelegate.performDfu(file, completionHandler)
    }

    fun retryProductType(retryCount: Int): DfuProductType =
        if (advertisingData.isLegacyBootLoading && (advertisingData.name == LEGACY_DISPLAY_AND_CHARGER_NAME)) {
            when {
                retryCount % 2 == 0 -> DfuProductType.DISPLAY
                else -> DfuProductType.CHARGER
            }
        } else {
            dfuProductType
        }

    override fun toString(): String {
        return "BootLoaderDevice(standardId=$standardId, dfuProductType=$dfuProductType)"
    }
}

fun AdvertisingData.standardId(): String =
    if (this.isBootLoading) {
        this.id.decrementMacAddress()
    } else this.id


private val AdvertisingData.isLegacyBootLoading
    get() = legacyBootLoadingDeviceNames.contains(name)

val AdvertisingData.isBootLoading: Boolean
    get() = isLegacyBootLoading ||
            (bootLoadingDevicePrefixes.firstOrNull { name.startsWith(it) } != null)

private fun String.decrementMacAddress(): String {
    val sections = this.split(":").toMutableList() // Split MAC into sections

    val lastSection = sections.last().toInt(16) // Convert last section to an integer (hex)
    val decremented = (lastSection - 1).coerceAtLeast(0) // Ensure it doesn’t go below 0

    sections[sections.size - 1] =
        String.format("%02X", decremented) // Convert back to uppercase hex

    return sections.joinToString(":") // Reassemble the MAC address
}