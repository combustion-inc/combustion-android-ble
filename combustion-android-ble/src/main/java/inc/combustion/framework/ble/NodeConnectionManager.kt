/*
 * Project: Combustion Inc. Android Framework
 * File: NodeConnectionManager.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2026. Combustion Inc.
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
import inc.combustion.framework.Constants.MIN_RSSI
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.device.DeviceID
import inc.combustion.framework.ble.device.NodeBleDevice
import inc.combustion.framework.ble.scanning.DeviceAdvertisingData
import inc.combustion.framework.ble.scanning.EngineAdvertisingData
import inc.combustion.framework.ble.scanning.GaugeAdvertisingData
import inc.combustion.framework.ble.scanning.NodeDeviceInfoAdvertisingData
import inc.combustion.framework.ble.scanning.ProbeAdvertisingData
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.Device
import inc.combustion.framework.service.DeviceConnectionState
import inc.combustion.framework.service.Ewma
import inc.combustion.framework.service.MeatNetNode
import inc.combustion.framework.service.dfu.DfuProductType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Keeps track of every MeatNet Node (Display, Booster, Gauge, Engine) heard advertising, publishes
 * them as [MeatNetNode]s, and keeps the phone connected to the Nodes the app asks for. The
 * equivalent of iOS's `MeatNetNode` list, `withinProximityRange` and `Device.connect()`.
 *
 * A Node is tracked by its BLE device ID. Its serial number comes from its own advertising where
 * there is one (Gauge, Engine, and a Display's or Booster's Device Info advertisement), otherwise
 * from its Device Information Service once connected.
 *
 * A held Node (see [connect]) is reconnected whenever it advertises as connectable while
 * disconnected, and [NodeBleDevice.isConnectionHeld] stops the framework's own disconnects from
 * dropping it. Releasing it ([disconnect]) only disconnects if [isNeededByFramework] says nothing
 * else uses the connection. In DFU mode, holds are paused ([pauseHolds]) rather than released.
 *
 * @param nodeDevice returns the [NodeBleDevice] the framework already has for a device ID, if any.
 * @param createNodeDevice creates (and starts tracking) a [NodeBleDevice] from a Node's repeater
 * advertisement, for a Node the framework had no reason to track before.
 * @param isNeededByFramework whether the framework itself uses this Node's connection, e.g. to
 * reach an allowed probe, gauge or engine.
 */
internal class NodeConnectionManager(
    private val scope: CoroutineScope,
    private val nodeDevice: (DeviceID) -> NodeBleDevice?,
    private val createNodeDevice: (ProbeAdvertisingData) -> NodeBleDevice,
    private val isNeededByFramework: (DeviceID) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Record(val id: DeviceID) {
        var serialNumber: String? = null
        var productType: CombustionProductType? = null
        var highRadioPower = false
        var lastSeenMs = 0L
        var isConnectable = false
        var repeaterAdvertisement: ProbeAdvertisingData? = null
        val rssi = Ewma(span = RSSI_EWMA_SPAN)
        var isInProximityRange = false
        var isHeld = false
        var lastConnectAttemptMs: Long? = null

        // Reset on disconnect, so a failed read is retried once per connection, not every refresh.
        var hasReadDeviceInfo = false
    }

    private val lock = Any()
    private val records = mutableMapOf<DeviceID, Record>()

    // While true (DFU mode), held Nodes can be disconnected and aren't reconnected. Guarded by lock.
    private var holdsPaused = false

    // The last published entry per Node, sorted by device ID, so the list is only rebuilt and
    // republished when an entry actually changes.
    private val published = sortedMapOf<DeviceID, MeatNetNode>()

    private val _nodesFlow = MutableStateFlow<List<MeatNetNode>>(emptyList())

    /** Every Node heard advertising since the last [clear], including ones now out of range. */
    val nodesFlow: StateFlow<List<MeatNetNode>> = _nodesFlow.asStateFlow()

    // Notices Nodes going out of range, and connection changes nothing else reports here.
    private val refreshJob: Job = scope.launch {
        while (isActive) {
            delay(REFRESH_INTERVAL_MS)
            refresh()
        }
    }

    /** Stops the periodic refresh; [scope] outlives this manager when NetworkManager is recreated. */
    fun finish() {
        refreshJob.cancel()
    }

    /**
     * Records an advertisement from a Node: a repeater advertisement ([ProbeAdvertisingData] of
     * type [CombustionProductType.NODE]), a Display's or Booster's Device Info advertisement, or a
     * Gauge's or Engine's advertisement. Ignores anything else.
     */
    fun handleAdvertisement(advertisement: DeviceAdvertisingData) {
        val isNodeAdvertisement = when (advertisement) {
            is ProbeAdvertisingData -> advertisement.productType == CombustionProductType.NODE
            is NodeDeviceInfoAdvertisingData,
            is GaugeAdvertisingData,
            is EngineAdvertisingData -> true

            else -> false
        }
        if (!isNodeAdvertisement) return

        val (record, reconnect) = synchronized(lock) {
            val record = records.getOrPut(advertisement.id) { Record(advertisement.id) }
            if (now() - record.lastSeenMs >= OUT_OF_RANGE_TIMEOUT_MS) {
                // Back in range: start the smoothing again rather than from a stale value.
                record.rssi.reset()
                record.isInProximityRange = false
            }
            record.lastSeenMs = now()
            record.isConnectable = advertisement.isConnectable

            when (advertisement) {
                is ProbeAdvertisingData -> record.repeaterAdvertisement = advertisement
                is NodeDeviceInfoAdvertisingData -> record.identify(
                    advertisement.serialNumber,
                    advertisement.productType,
                    advertisement.highRadioPower,
                )

                is GaugeAdvertisingData -> record.identify(
                    advertisement.serialNumber,
                    CombustionProductType.GAUGE,
                    advertisement.gaugePreferences?.highRadioPower ?: false,
                )

                is EngineAdvertisingData -> record.identify(
                    advertisement.serialNumber,
                    CombustionProductType.ENGINE,
                    advertisement.enginePreferences.highRadioPower,
                )
            }

            record.updateRssi(advertisement.rssi)
            record to record.shouldReconnect()
        }

        if (reconnect) connectNow(record)
        // Adverts are frequent, so only this Node is re-checked, and an RSSI change alone waits for
        // the periodic refresh, which also covers the rest (out of range, connection changes,
        // device info).
        synchronized(lock) { publish(listOf(record), now(), deferRssiOnlyChanges = true) }
    }

    /**
     * Keeps the phone connected to the Node [serialNumber] until [disconnect] is called: connects
     * now if it isn't connected, and again whenever it advertises as connectable while
     * disconnected. Does nothing if no Node with this serial number has been heard. While holds
     * are paused, only records the hold; the Node is connected once they resume.
     */
    fun connect(serialNumber: String) {
        val (record, paused) = synchronized(lock) {
            val record = findRecord(serialNumber)?.also { it.isHeld = true }
            record to holdsPaused
        }
        when {
            record == null -> {
                Log.w(LOG_TAG, "Can't connect to Node $serialNumber: not heard advertising")
                return
            }

            paused -> Log.i(LOG_TAG, "Node $serialNumber: will connect once DFU mode ends")
            nodeDeviceFor(record) == null ->
                Log.i(LOG_TAG, "Node $serialNumber: will connect once its repeater advertisement is heard")

            else -> connectNow(record)
        }
        refresh()
    }

    /**
     * Stops keeping the phone connected to the Node [serialNumber], and disconnects unless the
     * framework still uses the connection.
     */
    fun disconnect(serialNumber: String) {
        val id = synchronized(lock) {
            val record = findRecord(serialNumber) ?: return
            record.release()
            record.id
        }
        // Asked outside lock, as it takes NetworkManager's own locks.
        if (!isNeededByFramework(id)) {
            val node = synchronized(lock) {
                // A connect() since the release must win.
                nodeDevice(id)?.takeIf { records[id]?.isHeld != true }
            }
            node?.disconnect()
        }
        refresh()
    }

    /**
     * The connected Node with serial number [serialNumber], if its serial number is known from
     * its advertising.
     */
    fun connectedNodeDevice(serialNumber: String): NodeBleDevice? {
        val id = synchronized(lock) { findRecord(serialNumber)?.id } ?: return null
        return nodeDevice(id)?.takeIf { it.isConnected }
    }

    /**
     * Pauses every hold for DFU mode, which disconnects everything: held Nodes can then be
     * disconnected, but stay held, and aren't reconnected until [resumeHolds].
     */
    fun pauseHolds() {
        synchronized(lock) {
            holdsPaused = true
            records.values.filter { it.isHeld }.forEach { record ->
                // So the first advertisement after DFU mode can reconnect straight away.
                record.lastConnectAttemptMs = null
                nodeDevice(record.id)?.isConnectionHeld = false
            }
        }
        refresh()
    }

    /**
     * Ends [pauseHolds]. Held Nodes the framework has already reconnected are protected from its
     * disconnects again; the others reconnect on their next connectable advertisement, like the
     * framework's own devices after DFU. Connects nothing itself: Nodes may still be restarting
     * after their update.
     */
    fun resumeHolds() {
        synchronized(lock) {
            holdsPaused = false
            records.values.filter { it.isHeld }.forEach { record ->
                // nodeDevice, not nodeDeviceFor: a device is only created to connect it.
                nodeDevice(record.id)?.isConnectionHeld = true
            }
        }
        refresh()
    }

    /** Releases every held connection without disconnecting; see [clear]. */
    private fun releaseAll() {
        synchronized(lock) {
            records.values.filter { it.isHeld }.forEach { it.release() }
        }
        refresh()
    }

    /**
     * Releases and disconnects every held Node, then forgets every Node. Must run before the
     * caller drops its [NodeBleDevice]s, or a Node connected only because it was held would stay
     * connected with nothing tracking it.
     */
    fun clear() {
        val held = synchronized(lock) { records.values.filter { it.isHeld }.map { it.id } }
        releaseAll()
        held.forEach { nodeDevice(it)?.disconnect() }
        synchronized(lock) {
            records.clear()
            published.clear()
            _nodesFlow.value = emptyList()
        }
    }

    /** Rebuilds the published list; also reads a connected Node's serial number if needed. */
    fun refresh() {
        val toRead = synchronized(lock) {
            if (records.isEmpty()) return
            val toRead = records.values.mapNotNull { record ->
                val node = nodeDevice(record.id)
                if (node == null || !node.isConnected) {
                    record.hasReadDeviceInfo = false
                    null
                } else if (!record.hasReadDeviceInfo &&
                    (node.deviceInfoSerialNumber == null || node.deviceInfoModelInformation == null)
                ) {
                    record.hasReadDeviceInfo = true
                    node
                } else {
                    null
                }
            }
            publish(records.values, now())
            toRead
        }

        toRead.forEach { node ->
            scope.launch {
                if (node.deviceInfoSerialNumber == null) node.readSerialNumber()
                if (node.deviceInfoModelInformation == null) node.readModelInformation()
                refresh()
            }
        }
    }

    /**
     * Rebuilds the entries of [changed] and republishes the list if any of them differs from what
     * was published. With [deferRssiOnlyChanges], an entry whose only change is its RSSI is left
     * for the next [refresh]: advertisements arrive several times a second per Node, and each
     * republish makes collectors rebuild. Must be called holding lock, so concurrent calls can't
     * publish out of order.
     */
    private fun publish(changed: Collection<Record>, time: Long, deferRssiOnlyChanges: Boolean = false) {
        var isChanged = false
        changed.forEach { record ->
            val entry = record.toMeatNetNode(nodeDevice(record.id), time)
            val previous = published[record.id]
            if (entry == previous) return@forEach
            if (deferRssiOnlyChanges && previous != null && entry.withRssi(previous.rssi) == previous) {
                return@forEach
            }
            published[record.id] = entry
            isChanged = true
        }
        if (isChanged) {
            _nodesFlow.value = published.values.toList()
        }
    }

    private fun connectNow(record: Record) {
        // Checked before nodeDeviceFor, which may create a device, and again below.
        if (synchronized(lock) { holdsPaused }) return
        val node = nodeDeviceFor(record) ?: return
        synchronized(lock) {
            // Checked and set under lock: a release or a pause on another thread since the caller
            // decided to connect must win, or the device would stay guarded with nothing holding it.
            if (!record.isHeld || holdsPaused) return
            // Also for a device created only now, when the Node's first repeater advertisement
            // arrived after connect() was called.
            node.isConnectionHeld = true
            if (node.isConnected || node.connectionState == DeviceConnectionState.CONNECTING) return
            record.lastConnectAttemptMs = now()
        }
        Log.i(LOG_TAG, "Connecting to held Node ${record.serialNumber ?: record.id}")
        node.connect()
    }

    // Must be called holding lock.
    private fun Record.release() {
        isHeld = false
        lastConnectAttemptMs = null
        nodeDevice(id)?.isConnectionHeld = false
    }

    private fun nodeDeviceFor(record: Record): NodeBleDevice? =
        nodeDevice(record.id)
            ?: synchronized(lock) { record.repeaterAdvertisement }?.let(createNodeDevice)

    // Must be called holding lock.
    private fun findRecord(serialNumber: String): Record? {
        val target = serialNumber.uppercase()
        return records.values.firstOrNull { record ->
            (record.serialNumber ?: nodeDevice(record.id)?.deviceInfoSerialNumber)
                ?.uppercase() == target
        }
    }

    // Must be called holding lock.
    private fun Record.shouldReconnect(): Boolean {
        if (!isHeld || !isConnectable) return false
        val node = nodeDevice(id) ?: return true
        if (node.isConnected || node.connectionState == DeviceConnectionState.CONNECTING) {
            return false
        }
        val lastAttempt = lastConnectAttemptMs ?: return true
        return now() - lastAttempt >= RECONNECT_INTERVAL_MS
    }

    private fun Record.identify(
        serialNumber: String,
        productType: CombustionProductType,
        highRadioPower: Boolean,
    ) {
        if (serialNumber.isNotEmpty()) this.serialNumber = serialNumber.uppercase()
        this.productType = productType
        this.highRadioPower = highRadioPower
    }

    /** Smooths [rssi] and updates [Record.isInProximityRange], like iOS's `Device.handleRSSIUpdate`. */
    private fun Record.updateRssi(rssi: Int) {
        if (rssi > 0) return
        if (rssi <= MIN_RSSI) {
            this.rssi.reset()
            isInProximityRange = false
            return
        }
        this.rssi.put(rssi.toDouble())
        val smoothed = this.rssi.value ?: return
        val enter = if (highRadioPower) PROXIMITY_RSSI_ENTER_HIGH_POWER else PROXIMITY_RSSI_ENTER
        val leave = if (highRadioPower) PROXIMITY_RSSI_LEAVE_HIGH_POWER else PROXIMITY_RSSI_LEAVE
        if (isInProximityRange && smoothed < leave) {
            isInProximityRange = false
        } else if (!isInProximityRange && smoothed > enter) {
            isInProximityRange = true
        }
    }

    private fun Record.toMeatNetNode(node: NodeBleDevice?, time: Long): MeatNetNode {
        val isInRange = time - lastSeenMs < OUT_OF_RANGE_TIMEOUT_MS
        val connectionState = when (val state = node?.connectionState) {
            DeviceConnectionState.CONNECTING,
            DeviceConnectionState.CONNECTED,
            DeviceConnectionState.DISCONNECTING -> state

            else -> when {
                !isInRange -> DeviceConnectionState.OUT_OF_RANGE
                isConnectable -> DeviceConnectionState.ADVERTISING_CONNECTABLE
                else -> DeviceConnectionState.ADVERTISING_NOT_CONNECTABLE
            }
        }
        val modelInformation = node?.deviceInfoModelInformation
        return MeatNetNode(
            baseDevice = Device(
                serialNumber = serialNumber ?: node?.deviceInfoSerialNumber ?: "",
                mac = id,
                fwVersion = node?.deviceInfoFirmwareVersion,
                hwRevision = node?.deviceInfoHardwareRevision,
                modelInformation = modelInformation,
                rssi = if (isInRange) rssi.value?.roundToInt() ?: MIN_RSSI else MIN_RSSI,
                productType = productType
                    ?: modelInformation?.dfuProductType?.toNodeProductType()
                    ?: CombustionProductType.NODE,
                connectionState = connectionState,
            ),
            highRadioPower = highRadioPower,
            isInProximityRange = isInRange && isInProximityRange,
            isConnectionRequested = isHeld,
        )
    }

    private fun MeatNetNode.withRssi(rssi: Int) = copy(baseDevice = baseDevice.copy(rssi = rssi))

    private fun DfuProductType.toNodeProductType(): CombustionProductType? = when (this) {
        DfuProductType.DISPLAY -> CombustionProductType.DISPLAY
        DfuProductType.CHARGER -> CombustionProductType.CHARGER
        DfuProductType.GAUGE -> CombustionProductType.GAUGE
        DfuProductType.ENGINE -> CombustionProductType.ENGINE
        else -> null
    }

    companion object {
        // Same smoothing and thresholds as iOS's Device (rssiEWMA, PROXIMITY_RSSI_*).
        private const val RSSI_EWMA_SPAN = 6u
        const val PROXIMITY_RSSI_ENTER = -48.0
        const val PROXIMITY_RSSI_LEAVE = -55.0

        // High-power advertisements are 8 dB stronger at the same distance.
        const val PROXIMITY_RSSI_ENTER_HIGH_POWER = -40.0
        const val PROXIMITY_RSSI_LEAVE_HIGH_POWER = -47.0

        const val OUT_OF_RANGE_TIMEOUT_MS = BleManager.OUT_OF_RANGE_TIMEOUT
        const val RECONNECT_INTERVAL_MS = 5_000L
        private const val REFRESH_INTERVAL_MS = 1_000L
    }
}
