/*
 * Project: Combustion Inc. Android Framework
 * File: NodeLinking.kt
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

package inc.combustion.framework.service

import java.util.Date

/**
 * Serial number of a device in a MeatNet Node's linking table.
 */
sealed class NodeLinkingSerialNumber {
    /** Predictive Thermometer serial number, as the same hex string used elsewhere in the framework. */
    data class Probe(val serialNumber: String) : NodeLinkingSerialNumber()

    /** Ten-character serial number of a MeatNet Node product (Display, Booster, Gauge, Engine). */
    data class Node(val serialNumber: String) : NodeLinkingSerialNumber()

    /**
     * BLE MAC address ("AA:BB:CC:DD:EE:FF") of a device linked under the v1 (MAC-only) protocol
     * that the Node hasn't resolved to a serial number yet. Can't be used with
     * [DeviceManager.linkDeviceToNode] or [DeviceManager.unlinkDeviceFromNode].
     */
    data class LegacyMacAddress(val macAddress: String) : NodeLinkingSerialNumber()
}

/**
 * A device in a MeatNet Node's linked or unlinked table. See [DeviceManager.readNodeLinkedDevices]
 * and [DeviceManager.readNodeUnlinkedDevices].
 *
 * @property productType the type the Node stored with the entry. Serial numbers identify devices on
 * their own, so this is only descriptive: [CombustionProductType.NODE] means the Node didn't know
 * the device's specific type when it wrote the entry, not that it's a plain repeater.
 *
 * @property timestamp when the device was linked or unlinked, or null if the Node doesn't know.
 * @property isLegacy true for an unresolved v1 entry, whose [serialNumber] is a
 * [NodeLinkingSerialNumber.LegacyMacAddress]. Only linked tables contain legacy entries.
 */
data class NodeLinkingTableEntry(
    val productType: CombustionProductType,
    val serialNumber: NodeLinkingSerialNumber,
    val timestamp: Date?,
    val isLegacy: Boolean,
)

/**
 * The entries received so far by a linking-table read, passed to its progress handler after each
 * page. Until [isComplete], [entries] is only a prefix of the table: it is not a replacement for
 * the last complete table and must not be used to infer that other links were removed.
 */
data class NodeLinkingTableProgress(
    val entries: List<NodeLinkingTableEntry>,
    val pagesReceived: Int,
    val totalPages: Int,
    val totalCount: Int,
) {
    val isComplete: Boolean get() = pagesReceived == totalPages
}

/**
 * A heartbeat sent by a MeatNet Node, describing its connections in one direction. See
 * [DeviceManager.nodeHeartbeatFlow].
 *
 * @property serialNumber serial number of the Node that sent this heartbeat. Heartbeats are relayed
 * through MeatNet, so this is often not the Node the phone is connected to.
 * @property macAddress BLE MAC address ("AA:BB:CC:DD:EE:FF") of the sending Node.
 * @property hopCount number of MeatNet hops this heartbeat has taken.
 * @property inbound true if [connections] are the sending Node's inbound connections, false if
 * they are its outbound connections.
 * @property connections the sending Node's four connection slots, including empty ones.
 */
data class NodeHeartbeat(
    val serialNumber: String,
    val macAddress: String,
    val productType: CombustionProductType,
    val hopCount: UInt,
    val inbound: Boolean,
    val connections: List<Connection>,
) {
    /**
     * One connection slot of a heartbeat. When [present] is false the slot is empty and the other
     * fields hold no data.
     */
    data class Connection(
        val present: Boolean,
        val serialNumber: String,
        val productType: CombustionProductType,
        val rssi: Int,
    )
}
