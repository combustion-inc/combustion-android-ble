/*
 * Project: Combustion Inc. Android Framework
 * File: HeartbeatRssiSmoother.kt
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

import inc.combustion.framework.ble.uart.meatnet.NodeHeartbeatRequest
import inc.combustion.framework.service.Ewma
import kotlin.math.roundToInt

/**
 * Averages the RSSI that MeatNet Nodes report for their peers in heartbeats, so a link near a
 * signal-band boundary doesn't flip between bands with every heartbeat.
 *
 * Heartbeats are relayed through MeatNet, so averages are kept per (reporting Node, peer) rather
 * than per connected Node. The same heartbeat can reach the phone once through each connected
 * Node; its readings are only counted once.
 */
internal class HeartbeatRssiSmoother(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Link(val reporter: String, val peer: String)

    private data class Sender(val reporter: String, val inbound: Boolean)

    private class Average {
        val rssi = Ewma(span = RSSI_EWMA_SPAN)
        var lastUpdatedMs = 0L
    }

    private val lock = Any()
    private val averages = mutableMapOf<Link, Average>()
    private val lastRequestIds = mutableMapOf<Sender, UInt>()

    /**
     * Adds [heartbeat]'s readings to their links' averages, unless this heartbeat was already
     * counted, and returns the smoothed RSSI (dBm) of each of its connection slots, in order: null
     * for an empty slot, a peer or reporter with no serial number, or a link with no reading in the
     * last [STALE_TIMEOUT_MS].
     */
    fun smooth(heartbeat: NodeHeartbeatRequest): List<Int?> = synchronized(lock) {
        val reporter = heartbeat.serialNumber?.key().orEmpty()
        if (reporter.isEmpty()) return heartbeat.connectionDetails.map { null }

        val time = now()
        val isNew = lastRequestIds.put(Sender(reporter, heartbeat.inbound), heartbeat.requestId) !=
                heartbeat.requestId
        if (isNew) {
            // A link that hasn't been reported for a while starts again from its next reading.
            averages.values.removeAll { time - it.lastUpdatedMs > STALE_TIMEOUT_MS }
        }

        heartbeat.connectionDetails.map { detail ->
            val peer = detail.serialNumber.key()
            if (!detail.present || peer.isEmpty()) return@map null
            val link = Link(reporter, peer)
            // A non-negative RSSI means the Node had no reading for this peer.
            if (isNew && detail.rssi < 0) {
                averages.getOrPut(link) { Average() }.apply {
                    rssi.put(detail.rssi.toDouble())
                    lastUpdatedMs = time
                }
            }
            averages[link]
                ?.takeIf { time - it.lastUpdatedMs <= STALE_TIMEOUT_MS }
                ?.rssi?.value?.roundToInt()
        }
    }

    /** Forgets every average, e.g. when the framework's state is cleared. */
    fun clear() = synchronized(lock) {
        averages.clear()
        lastRequestIds.clear()
    }

    private fun String.key(): String = uppercase()

    companion object {
        /**
         * Same span as iOS's heartbeat smoothing. Nodes report each link every 4 s (a heartbeat
         * every 2 s, alternating inbound and outbound links), and the value is already the Node's
         * own running average, so a real change in signal takes about half a minute to show fully:
         * fine for a signal-quality label, whose point is not to flicker.
         */
        private const val RSSI_EWMA_SPAN = 6u

        /** Same as a Node's out-of-range timeout in [NodeConnectionManager]. */
        const val STALE_TIMEOUT_MS = NodeConnectionManager.OUT_OF_RANGE_TIMEOUT_MS
    }
}
