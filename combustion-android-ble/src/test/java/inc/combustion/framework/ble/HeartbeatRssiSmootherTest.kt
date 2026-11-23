/*
 * Project: Combustion Inc. Android Framework
 * File: HeartbeatRssiSmootherTest.kt
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
import inc.combustion.framework.ble.uart.meatnet.NodeHeartbeatRequest.ConnectionDetail
import inc.combustion.framework.service.CombustionProductType
import org.junit.Assert.assertEquals
import org.junit.Test

class HeartbeatRssiSmootherTest {
    private var nowMs = 100_000L
    private val smoother = HeartbeatRssiSmoother(now = { nowMs })

    @Test
    fun `first reading is used as is, later ones are averaged`() {
        assertEquals(listOf(-60, null, null, null), smoother.smooth(heartbeat(requestId = 1u, -60)))
        // span 6: -60 + 2/7 * (-80 - -60) = -65.7
        assertEquals(listOf(-66, null, null, null), smoother.smooth(heartbeat(requestId = 2u, -80)))
    }

    @Test
    fun `a heartbeat relayed through several Nodes is counted once`() {
        smoother.smooth(heartbeat(requestId = 1u, -60))
        smoother.smooth(heartbeat(requestId = 2u, -80))

        // Same heartbeat arriving through another connected Node: same average, not -69.
        assertEquals(-66, smoother.smooth(heartbeat(requestId = 2u, -80)).first())
    }

    @Test
    fun `a relayed copy arriving after the other direction's heartbeat is still counted once`() {
        smoother.smooth(heartbeat(requestId = 1u, -60, inbound = true))
        smoother.smooth(heartbeat(requestId = 2u, -80, inbound = false, peer = "10001234"))

        assertEquals(-60, smoother.smooth(heartbeat(requestId = 1u, -80, inbound = true)).first())
    }

    @Test
    fun `a peer with no serial number has no smoothed RSSI`() {
        assertEquals(null, smoother.smooth(heartbeat(requestId = 1u, -60, peer = "")).first())
    }

    @Test
    fun `inbound and outbound heartbeats are counted separately`() {
        smoother.smooth(heartbeat(requestId = 1u, -60, inbound = true))

        // Same request ID in the other direction is a different heartbeat.
        assertEquals(-66, smoother.smooth(heartbeat(requestId = 1u, -80, inbound = false)).first())
    }

    @Test
    fun `links are averaged per reporting Node and peer, ignoring serial number case`() {
        smoother.smooth(heartbeat(requestId = 1u, -60, reporter = "DS10001234"))
        smoother.smooth(heartbeat(requestId = 1u, -90, reporter = "CR10007379"))

        val next = smoother.smooth(heartbeat(requestId = 2u, -80, reporter = "ds10001234", peer = "1000da4c"))

        assertEquals(-66, next.first())
    }

    @Test
    fun `missing readings and empty slots aren't averaged`() {
        smoother.smooth(heartbeat(requestId = 1u, -60))

        // No reading (non-negative RSSI): keeps the recent average.
        assertEquals(-60, smoother.smooth(heartbeat(requestId = 2u, 0)).first())
        assertEquals(
            listOf<Int?>(null, null, null, null),
            smoother.smooth(heartbeat(requestId = 3u, rssi = null, reporter = "CR10007379")),
        )
    }

    @Test
    fun `a link not reported for longer than the timeout starts again`() {
        smoother.smooth(heartbeat(requestId = 1u, -60))

        nowMs += HeartbeatRssiSmoother.STALE_TIMEOUT_MS + 1

        assertEquals(-80, smoother.smooth(heartbeat(requestId = 2u, -80)).first())
    }

    @Test
    fun `a stale average isn't returned when the link has no reading`() {
        smoother.smooth(heartbeat(requestId = 1u, -60))

        nowMs += HeartbeatRssiSmoother.STALE_TIMEOUT_MS + 1

        assertEquals(null, smoother.smooth(heartbeat(requestId = 2u, 0)).first())
    }

    @Test
    fun `clear forgets averages and counted heartbeats`() {
        smoother.smooth(heartbeat(requestId = 1u, -60))

        smoother.clear()

        assertEquals(-80, smoother.smooth(heartbeat(requestId = 1u, -80)).first())
    }

    /** A heartbeat from [reporter] with [peer] in its first slot, or no peers if [rssi] is null. */
    private fun heartbeat(
        requestId: UInt,
        rssi: Int?,
        reporter: String = "DS10001234",
        peer: String = "1000DA4C",
        inbound: Boolean = true,
    ): NodeHeartbeatRequest {
        val first = rssi?.let { ConnectionDetail(true, peer, CombustionProductType.PROBE, it) }
            ?: ConnectionDetail(false)
        return NodeHeartbeatRequest(
            serialNumber = reporter,
            macAddress = "AA:BB:CC:01:02:03",
            productType = CombustionProductType.DISPLAY,
            hopCount = 1u,
            inbound = inbound,
            connectionDetails = listOf(first) + List(3) { ConnectionDetail(false) },
            requestId = requestId,
            payloadLength = 71u,
        )
    }
}
