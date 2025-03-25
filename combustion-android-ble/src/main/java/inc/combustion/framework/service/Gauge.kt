/*
 * Project: Combustion Inc. Android Framework
 * File: Gauge.kt
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

data class Gauge(
    override val baseDevice: Device,
    override val productType: CombustionProductType = CombustionProductType.GAUGE,
    override val dfuProductType: DfuProductType = DfuProductType.GAUGE,
    override val sessionInfo: SessionInformation? = null, // TODO
    override val statusNotificationsStale: Boolean = false,
    override val batteryStatus: ProbeBatteryStatus = ProbeBatteryStatus.OK,
    override val uploadState: ProbeUploadState = ProbeUploadState.Unavailable,
) : AccessoryDevice {

    companion object {
        fun create(serialNumber: String = "", mac: String = ""): Gauge {
            return Gauge(
                baseDevice = Device(
                    serialNumber = serialNumber,
                    mac = mac,
                )
            )
        }
    }

    override val isOverheating: Boolean
        get() = TODO("Not yet implemented")
}