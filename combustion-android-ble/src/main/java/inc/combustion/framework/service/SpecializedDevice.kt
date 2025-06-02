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
    val batteryStatus: ProbeBatteryStatus
    val uploadState: ProbeUploadState
    val minSequence: UInt?
    val maxSequence: UInt?
    val hopCount: UInt?

    val isOverheating: Boolean

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

    companion object {
        const val STATUS_NOTIFICATIONS_IDLE_TIMEOUT_MS = 15000L
    }
}