/*
 * Project: Combustion Inc. Android Framework
 * File: GaugeManager.kt
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

package inc.combustion.framework.ble

import android.util.Log
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.device.DeviceID
import inc.combustion.framework.ble.device.DeviceInformationBleDevice
import inc.combustion.framework.ble.device.GaugeBleDevice
import inc.combustion.framework.ble.device.SimulatedGaugeBleDevice
import inc.combustion.framework.ble.scanning.DeviceAdvertisingData
import inc.combustion.framework.ble.scanning.GaugeAdvertisingData
import inc.combustion.framework.ble.uart.meatnet.NodeReadGaugeLogsResponse
import inc.combustion.framework.service.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * This class is responsible for managing and arbitrating the data links to a gauge.
 * When MeatNet is enabled that includes data links through repeater devices over
 * MeatNet and direct links to gauge.  When MeatNet is disabled, this class
 * manages only direct links to the gauge. The class is responsible for presenting
 * a common interface over both scenarios.
 *
 * @property scope Coroutine scope.
 * @property settings Service settings.
 * @constructor
 * Constructs a gauge manager
 *
 * @param serialNumber The serial number of the gauge being managed.
 */
internal class GaugeManager(
    mac: String,
    serialNumber: String,
    scope: CoroutineScope,
    settings: DeviceManager.Settings,
    dfuDisconnectedNodeCallback: (DeviceID) -> Unit,
) : NodeHybridManager<GaugeBleDevice, GaugeAdvertisingData, Gauge, SimulatedGaugeBleDevice>(
    scope = scope,
    settings = settings,
    dfuDisconnectedNodeCallback = dfuDisconnectedNodeCallback,
) {

    override val arbitrator = GaugeDataLinkArbitrator()

    override val _deviceFlow =
        MutableStateFlow(Gauge.create(serialNumber = serialNumber, mac = mac))

    override val deviceFlow: StateFlow<Gauge> = _deviceFlow.asStateFlow()

    override val device: Gauge
        get() = _deviceFlow.value

    // the flow that produces LogResponses from MeatNet
    private val _logResponseFlow = MutableSharedFlow<NodeReadGaugeLogsResponse>(
        replay = 0, extraBufferCapacity = 50, BufferOverflow.SUSPEND
    )
    override val logResponseFlow = _logResponseFlow.asSharedFlow()

    // the flow that produces GaugeStatus updates from MeatNet
    private val _normalModeStatusFlow = MutableSharedFlow<GaugeStatus>(
        replay = 0, extraBufferCapacity = 10, BufferOverflow.DROP_OLDEST
    )
    override val normalModeStatusFlow: SharedFlow<SpecializedDeviceStatus> =
        _normalModeStatusFlow.asSharedFlow()

    init {
        monitorStatusNotifications()
    }

    // Abstract method implementations

    override fun Gauge.withBaseDevice(baseDevice: Device): Gauge = copy(baseDevice = baseDevice)
    override fun Gauge.withStatusNotificationsStale(stale: Boolean): Gauge = copy(statusNotificationsStale = stale)
    override fun Gauge.withUploadState(state: ProbeUploadState): Gauge = copy(uploadState = state)
    override fun Gauge.withRecordsDownloaded(count: Int): Gauge = copy(recordsDownloaded = count)
    override fun Gauge.withLogUploadPercent(percent: UInt): Gauge = copy(logUploadPercent = percent)
    override fun Gauge.withSessionInfo(info: SessionInformation, minSequenceNumber: UInt, maxSequenceNumber: UInt): Gauge =
        copy(minSequence = minSequenceNumber, maxSequence = maxSequenceNumber, sessionInfo = info)

    override fun castToAdvertisementType(advertisement: DeviceAdvertisingData): GaugeAdvertisingData? =
        advertisement as? GaugeAdvertisingData

    // Public API

    fun hasGauge(): Boolean = hasDevice()

    fun addGauge(
        gauge: GaugeBleDevice,
        baseDevice: DeviceInformationBleDevice,
        advertisement: GaugeAdvertisingData,
    ) = addDevice(gauge, baseDevice, advertisement)

    fun addSimulatedGauge(simGauge: SimulatedGaugeBleDevice) = addSimulatedDevice(simGauge)

    fun setHighLowAlarmStatus(
        highLowAlarmStatus: HighLowAlarmStatus,
        completionHandler: (Boolean) -> Unit,
    ) {
        val onCompletion: (Boolean) -> Unit = { success ->
            if (success) {
                _deviceFlow.update {
                    _deviceFlow.value.copy(
                        highLowAlarmStatus = highLowAlarmStatus,
                    )
                }
            }
            completionHandler(success)
        }

        val requestId = makeRequestId()
        simulatedDevice?.sendSetHighLowAlarmStatus(highLowAlarmStatus, requestId) { status, _ ->
            onCompletion(status)
        } ?: arbitrator.directLink?.sendSetHighLowAlarmStatus(
            highLowAlarmStatus,
            requestId,
        ) { status, _ ->
            onCompletion(status)
        } ?: run {
            val nodeLinks = arbitrator.connectedNodeLinks
            if (nodeLinks.isNotEmpty()) {
                val handled = AtomicBoolean(false)
                nodeLinks.forEach { node ->
                    node.sendSetGaugeHighLowAlarmStatus(
                        serialNumber,
                        highLowAlarmStatus,
                        requestId,
                    ) { status, _ ->
                        if (!handled.getAndSet(true)) {
                            onCompletion(status)
                        }
                    }
                }
            } else {
                onCompletion(false)
            }
        }
    }

    override fun sendLogRequest(startSequenceNumber: UInt, endSequenceNumber: UInt) {
        val requestId = makeRequestId()
        val callback: suspend (NodeReadGaugeLogsResponse) -> Unit = {
            _logResponseFlow.emit(it)
        }
        simulatedDevice?.sendGaugeLogRequest(
            startSequenceNumber,
            endSequenceNumber,
            requestId,
            callback,
        ) ?: arbitrator.directLink?.sendGaugeLogRequest(
            startSequenceNumber,
            endSequenceNumber,
            requestId,
            callback,
        ) ?: run {
            val nodeLinks = arbitrator.connectedNodeLinks
            if (nodeLinks.isNotEmpty()) {
                val handledSequenceNumbers = mutableSetOf<UInt>()
                nodeLinks.forEach { node ->
                    node.sendGaugeLogRequest(
                        serialNumber,
                        startSequenceNumber,
                        endSequenceNumber,
                        requestId,
                    ) {
                        if (!handledSequenceNumbers.contains(it.sequenceNumber)) {
                            handledSequenceNumbers.add(it.sequenceNumber)
                            callback(it)
                        }
                    }
                }
            }
        }
    }

    override suspend fun updateDataFromSimulatedStatus(status: SpecializedDeviceStatus) {
        handleStatus(status as GaugeStatus, simulated = true)
    }

    suspend fun observedGaugeStatus(gaugeStatus: GaugeStatus) {
        handleStatus(gaugeStatus, simulated = false)
    }

    private suspend fun handleStatus(
        status: GaugeStatus,
        simulated: Boolean = simulatedDevice != null,
    ) {
        Log.v(LOG_TAG, "GaugeManager.handleStatus: $serialNumber $status")

        // since status from gauge with no sensor currently does not trigger a status update, we need to
        // update statusNotificationsMonitor before check
        // TODO : do update after check if logic is changed
        statusNotificationsMonitor.activity()

        if (simulated || arbitrator.shouldUpdateDataFromStatusForNormalMode(
                status,
                sessionInfo,
            )
        ) {
            handleSessionInfo(
                status.sessionInformation,
                minSequenceNumber = status.minSequenceNumber,
                maxSequenceNumber = status.maxSequenceNumber,
            )

            _normalModeStatusFlow.emit(status)

            if (!simulated) {
                fetchDeviceInfo()
            }

            _deviceFlow.update {
                it.copy(
                    highLowAlarmStatus = status.highLowAlarmStatus,
                    gaugeStatusFlags = status.gaugeStatusFlags,
                    temperatureCelsius = if (status.gaugeStatusFlags.sensorPresent) status.temperature else null,
                    newRecordFlag = status.isNewRecord,
                    hopCount = status.hopCount.hopCount,
                )
            }
        }
    }

    override fun updateDataFromAdvertisement(
        advertisement: GaugeAdvertisingData,
        current: Gauge,
    ): Gauge {
        val updatedGauge = current.copy(
            baseDevice = current.baseDevice.copy(rssi = advertisement.rssi),
        )

        return updatedGauge.copy(
            gaugeStatusFlags = advertisement.gaugeStatusFlags,
            temperatureCelsius = if (advertisement.gaugeStatusFlags.sensorPresent) advertisement.gaugeTemperature else null,
            highLowAlarmStatus = advertisement.highLowAlarmStatus,
        )
    }
}
