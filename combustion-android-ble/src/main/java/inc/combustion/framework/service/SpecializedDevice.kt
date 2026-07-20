/*
 * Project: Combustion Inc. Android Framework
 * File: ConcreteDevice.kt
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

package inc.combustion.framework.service

import inc.combustion.framework.service.dfu.DfuProductType

interface SpecializedDevice {
    val baseDevice: Device
    val productType: CombustionProductType
    val dfuProductType: DfuProductType
    val sessionInfo: SessionInformation?
    val statusNotificationsStale: Boolean
    val lowBattery: Boolean
    val uploadState: ProbeUploadState
    val recordsDownloaded: Int
    val logUploadPercent: UInt
    val minSequence: UInt?
    val maxSequence: UInt?
    val hopCount: UInt?

    /**
     * Whether a status message has been received from this device yet. Fields that are only
     * ever populated by a status message (never by advertising data alone) can't be trusted to
     * reflect the device's actual state until this is true.
     */
    val hasReceivedStatus: Boolean

    val isOverheating: Boolean

    /**
     * Whether the device transmits at high radio power (+8 dBm) rather than normal power
     * (+0 dBm).
     *
     * When using RSSI for proximity detection, applications should adjust thresholds based on
     * this value: high power devices show approximately 8 dB higher (less negative) RSSI at the
     * same physical distance as normal power devices.
     */
    val highRadioPower: Boolean

    val serialNumber: String
        get() = baseDevice.serialNumber
    val mac: String
        get() = baseDevice.mac
    val fwVersion: FirmwareVersion?
        get() = baseDevice.fwVersion
    val hwRevision: String?
        get() = baseDevice.hwRevision
    val modelInformation: ModelInformation?
        get() = baseDevice.modelInformation
    val rssi: Int
        get() = baseDevice.rssi
    val connectionState: DeviceConnectionState
        get() = baseDevice.connectionState

    /**
     * Returns true if not an actual full valid state but still has placeholder values
     */
    fun isPlaceholder(): Boolean {
        return (maxSequence == null) && (sessionInfo == null)
    }

    companion object {
        const val STATUS_NOTIFICATIONS_IDLE_TIMEOUT_MS = 15000L
    }
}