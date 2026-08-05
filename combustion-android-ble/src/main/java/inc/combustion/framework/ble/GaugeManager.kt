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
import inc.combustion.framework.ble.uart.meatnet.NodeMessageType
import inc.combustion.framework.ble.uart.meatnet.NodeReadGaugeLogsResponse
import inc.combustion.framework.service.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    private val commandCoordinator: CommandCoordinator = CommandCoordinator(),
) : NodeHybridManager<GaugeBleDevice, GaugeAdvertisingData, Gauge, SimulatedGaugeBleDevice>(
    scope = scope,
    settings = settings,
    dfuDisconnectedNodeCallback = dfuDisconnectedNodeCallback,
) {

    override val arbitrator = GaugeDataLinkArbitrator()

    // Gauge status can arrive concurrently from more than one source (a direct BLE link and/or
    // multiple mesh-relay nodes), all invoking handleStatus() for the same gauge. This mutex
    // serializes the read-decide-write sequence in handleStatus() so two overlapping status
    // updates can't race on the arbitrator's session/sequence bookkeeping or clobber each
    // other's _deviceFlow update -- mirrors the same fix in EngineManager.
    private val handleStatusMutex = Mutex()

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
    override fun Gauge.withStatusNotificationsStale(stale: Boolean): Gauge =
        copy(statusNotificationsStale = stale)

    override fun Gauge.withUploadState(state: ProbeUploadState): Gauge = copy(uploadState = state)
    override fun Gauge.withRecordsDownloaded(count: Int): Gauge = copy(recordsDownloaded = count)
    override fun Gauge.withLogUploadPercent(percent: UInt): Gauge = copy(logUploadPercent = percent)
    override fun Gauge.withSessionInfo(
        info: SessionInformation,
        minSequenceNumber: UInt,
        maxSequenceNumber: UInt
    ): Gauge =
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

    /**
     * Sends via [commandCoordinator] following the same shape as
     * `EngineManager.setControlDevice`. Gauge, like Engine, has no pure-GATT-direct transport
     * distinct from the MeatNet/UART mechanism, so every attempt is a [CommandAttemptKey.Node]
     * keyed by [NodeMessageType.SET_GAUGE_HIGH_LOW_ALARM] and the request ID.
     *
     * Deliberately does not optimistically write [highLowAlarmStatus] into `_deviceFlow` on
     * completion -- see `EngineManager.setControlDevice`'s KDoc for why: a `SUCCESS` result can
     * come from [CommandCoordinator.valueConfirmation]'s "changed to something else" branch, so
     * the next real status update refreshes `_deviceFlow` through [handleStatus] instead.
     */
    fun setHighLowAlarmStatus(
        highLowAlarmStatus: HighLowAlarmStatus,
        completionHandler: (Boolean) -> Unit,
    ) {
        // Gauge.highLowAlarmStatus defaults to a non-null sentinel (HighLowAlarmStatus.DEFAULT),
        // unlike Engine.controlSerialNumber's null default -- so gating on hasReceivedStatus,
        // not nullability, is what actually distinguishes "no confirmed starting value yet" from
        // "confirmed default." See CommandCoordinator.valueConfirmation's KDoc and
        // EngineManager.setControlDevice's equivalent handling.
        val startingHighLowAlarmStatus = _deviceFlow.value.let {
            if (it.hasReceivedStatus) ExtractedValue.Present(it.highLowAlarmStatus.threshold) else ExtractedValue.Absent
        }

        scope.launch {
            val result = commandCoordinator.sendRoutedCommand(
                targetSerialNumber = serialNumber,
                send = {
                    val requestId = makeRequestId()
                    val key = CommandAttemptKey.Node(
                        NodeMessageType.SET_GAUGE_HIGH_LOW_ALARM,
                        requestId,
                    )
                    val onResponse: (Boolean, Any?) -> Unit = { success, _ ->
                        if (success) {
                            commandCoordinator.completeAttempt(key, success = true)
                        }
                    }

                    val sent = simulatedDevice?.sendSetHighLowAlarmStatus(
                        highLowAlarmStatus,
                        requestId,
                        onResponse,
                    ) ?: arbitrator.directLink?.sendSetHighLowAlarmStatus(
                        highLowAlarmStatus,
                        requestId,
                        onResponse,
                    ) ?: run {
                        val nodeLinks = arbitrator.connectedNodeLinks
                        if (nodeLinks.isEmpty()) {
                            null
                        } else {
                            nodeLinks.forEach { node ->
                                node.sendSetGaugeHighLowAlarmStatus(
                                    serialNumber,
                                    highLowAlarmStatus,
                                    requestId,
                                    onResponse,
                                )
                            }
                        }
                    }

                    if (sent != null) setOf(key) else emptySet()
                },
                isConfirmed = CommandCoordinator.valueConfirmation(
                    // Compares HighLowAlarmStatus.Threshold, not the full HighLowAlarmStatus --
                    // AlarmStatus.tripped/alarming are live, device-computed flags, not something
                    // this command sets, so comparing them too would let an unrelated flip
                    // spuriously look like "the commanded value changed." See
                    // AlarmStatus.Threshold's KDoc.
                    startingValue = startingHighLowAlarmStatus,
                    commandedValue = highLowAlarmStatus.threshold,
                    extractValue = { it.extractedAs<GaugeStatus, _> { s -> s.highLowAlarmStatus.threshold } },
                ),
            )

            withContext(Dispatchers.Main.immediate) {
                completionHandler(result == CommandResult.SUCCESS)
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

        handleStatusMutex.withLock {
            if (simulated || arbitrator.shouldUpdateDataFromStatusForNormalMode(
                    status,
                    status.sessionInformation,
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

                // Lets a pending setHighLowAlarmStatus retry loop complete early from this status
                // alone, even if its own response packet is lost -- see CommandCoordinator.
                commandCoordinator.confirmCommandStatus(serialNumber, status)
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
            gaugePrefs = advertisement.gaugePreferences ?: updatedGauge.gaugePrefs,
        )
    }
}
