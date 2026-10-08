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
    override val sessionInfo: SessionInformation? = null,
    override val statusNotificationsStale: Boolean = false,
    override val uploadState: ProbeUploadState = ProbeUploadState.Unavailable, // TODO : rename class?
    override val minSequence: UInt? = null,
    override val maxSequence: UInt? = null,
    val highLowAlarmStatus: HighLowAlarmStatus = HighLowAlarmStatus.DEFAULT,
    val gaugeStatusFlags: GaugeStatusFlags = GaugeStatusFlags(),
    val temperatureCelsius: SensorTemperature? = null,
    override val recordsDownloaded: Int = 0,
    override val logUploadPercent: UInt = 0u,
    val newRecordFlag: Boolean = false,
    override val hopCount: UInt? = null,
    val gaugePrefs: GaugePreferences? = null,
    /**
     * Raw wire value, not [GaugeID] -- see `GaugeStatus.id`'s KDoc for why. Use [knownId] for the
     * 8-way picker's shape. Null means no status has carried an ID yet: either none has been
     * received, the gauge's firmware predates gauge IDs, or every status so far came through a
     * MeatNet node on older firmware that drops the ID (see [supportsId]).
     */
    val id: UByte? = null,
) : SpecializedDevice {
    override val lowBattery: Boolean
        get() = gaugeStatusFlags.lowBattery

    /**
     * [id] resolved to one of the 8 [GaugeID] values the picker offers, or null if the gauge is
     * actually assigned an ID beyond what those 8 model -- see [GaugeID.fromUByte]'s KDoc -- or if
     * [id] is null. Treat null as "none of the picker's options match," not as "assume ID1."
     */
    val knownId: GaugeID?
        get() = id?.let { GaugeID.fromUByte(it) }

    /**
     * True once a status has carried an ID, so [DeviceManager.setGaugeID] can be applied. False
     * for gauges on firmware that predates gauge IDs (pre-GAU-96), before any status has been
     * received, and for a gauge only reachable through MeatNet nodes on older firmware, which
     * drop the ID when relaying status.
     */
    val supportsId: Boolean
        get() = hasReceivedStatus && (id != null)

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
        get() = gaugeStatusFlags.sensorOverheating

    /**
     * [hopCount] is only ever populated by a status message (never by advertising data alone),
     * so this is true once one has been received.
     */
    override val hasReceivedStatus: Boolean
        get() = hopCount != null

    override val highRadioPower: Boolean
        get() = gaugePrefs?.highRadioPower ?: false
}
