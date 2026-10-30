/*
 * Project: Combustion Inc. Android Framework
 * File: NodeLinkingManagerTest.kt
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
import inc.combustion.framework.ble.device.NodeBleDevice
import inc.combustion.framework.ble.uart.meatnet.NodeHeartbeatRequest
import inc.combustion.framework.ble.uart.meatnet.NodeLinkDeviceRequest
import inc.combustion.framework.ble.uart.meatnet.NodeLinkingAckResponse
import inc.combustion.framework.ble.uart.meatnet.NodeLinkingTablePageResponse
import inc.combustion.framework.ble.uart.meatnet.NodeLinkingWire
import inc.combustion.framework.ble.uart.meatnet.NodeMessageType
import inc.combustion.framework.ble.uart.meatnet.NodeReadLinkingTableRequest
import inc.combustion.framework.ble.uart.meatnet.NodeReadProximityLinkingResponse
import inc.combustion.framework.ble.uart.meatnet.NodeRequest
import inc.combustion.framework.ble.uart.meatnet.NodeResponse
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.NodeHeartbeat
import inc.combustion.framework.service.NodeLinkingResult
import inc.combustion.framework.service.NodeLinkingSerialNumber
import inc.combustion.framework.service.NodeLinkingTableEntry
import inc.combustion.framework.service.NodeLinkingTableProgress
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NodeLinkingManagerTest {

    private val node = "CR10007379"

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    // ---- LinkingTableReader ----

    private fun entry(serial: String) = NodeLinkingTableEntry(
        CombustionProductType.PROBE,
        NodeLinkingSerialNumber.Probe(serial),
        timestamp = null,
        isLegacy = false,
    )

    private fun page(page: Int, totalPages: Int, totalCount: Int, vararg serials: String) =
        NodeLinkingWire.Page(page, totalPages, totalCount, serials.map(::entry))

    @Test
    fun `reader collects pages in order until the last one`() {
        val reader = LinkingTableReader()

        val first = reader.accept(0, page(0, 2, 5, "1", "2", "3", "4"))
        assertEquals(1, (first as LinkingTableReader.Step.Continue).nextPage)
        assertEquals(NodeLinkingTableProgress(listOf("1", "2", "3", "4").map(::entry), 1, 2, 5), first.progress)

        val second = reader.accept(1, page(1, 2, 5, "5"))
        assertEquals(listOf("1", "2", "3", "4", "5").map(::entry), (second as LinkingTableReader.Step.Complete).entries)
        assertTrue(second.progress.isComplete)
    }

    @Test
    fun `reader accepts an empty table reported with 0 or 1 pages`() {
        listOf(0, 1).forEach { totalPages ->
            val step = LinkingTableReader().accept(0, page(0, totalPages, 0))
            assertEquals(emptyList<NodeLinkingTableEntry>(), (step as LinkingTableReader.Step.Complete).entries)
            assertTrue(step.progress.isComplete)
        }
    }

    @Test
    fun `reader rejects inconsistent pages`() {
        fun failsAfterFirstPage(second: NodeLinkingWire.Page?) {
            val reader = LinkingTableReader()
            reader.accept(0, page(0, 2, 5, "1", "2", "3", "4"))
            assertTrue("$second", reader.accept(1, second) is LinkingTableReader.Step.Failed)
        }

        failsAfterFirstPage(null) // malformed
        failsAfterFirstPage(page(0, 2, 5, "5")) // wrong page number
        failsAfterFirstPage(page(1, 3, 5, "5")) // total pages changed
        failsAfterFirstPage(page(1, 2, 6, "5")) // total count changed
        failsAfterFirstPage(page(1, 2, 5)) // too few entries on the last page
        failsAfterFirstPage(page(1, 2, 5, "5", "6")) // more entries than the total count

        // A page past the last one.
        assertTrue(LinkingTableReader().accept(0, page(0, 0, 1, "1")) is LinkingTableReader.Step.Failed)
    }

    // ---- NodeLinkingManager ----

    /** A connected Node whose responses the test sends with [respond]. */
    private inner class FakeNode(private val scope: CoroutineScope) {
        val requests = mutableListOf<NodeRequest>()
        private val callbacks = mutableMapOf<UInt, (Boolean, Any?) -> Unit>()
        var connected = true
        val device = mockk<NodeBleDevice>(relaxed = true).also { device ->
            every { device.isConnected } answers { connected }
            every { device.sendLinkingRequest(any(), any()) } answers {
                val request = firstArg<NodeRequest>()
                requests += request
                callbacks[request.requestId] = secondArg()
            }
        }

        /**
         * Answers the most recent request, asynchronously like a real Node. Passes the response's
         * own success flag, as [NodeBleDevice] does.
         */
        fun respond(response: (NodeRequest) -> NodeResponse) {
            val request = requests.last()
            val callback = callbacks.remove(request.requestId)!!
            val message = response(request)
            scope.launch { callback(message.success, message) }
        }
    }

    private fun TestScope.manager(fakeNode: FakeNode?): NodeLinkingManager {
        var nextRequestId = 0u
        return NodeLinkingManager(
            scope = backgroundScope,
            findConnectedNode = { serial -> fakeNode?.device?.takeIf { serial == node } },
            makeRequestId = { ++nextRequestId },
        )
    }

    private fun pageResponse(request: NodeRequest, page: NodeLinkingWire.Page?) = NodeLinkingTablePageResponse(
        success = true,
        requestId = request.requestId,
        responseId = 0u,
        payloadLength = 0u,
        messageType = NodeMessageType.READ_LINKED_DEVICES,
        page = page,
    )

    private fun ack(request: NodeRequest) =
        NodeLinkingAckResponse(true, request.requestId, 0u, 0u, request.messageId as NodeMessageType)

    @Test
    fun `reading a table publishes it only after the last page`() = runTest {
        val fakeNode = FakeNode(backgroundScope)
        val manager = manager(fakeNode)
        val progress = mutableListOf<NodeLinkingTableProgress>()
        var result: NodeLinkingResult<List<NodeLinkingTableEntry>>? = null

        manager.readTable(node, LinkingTableKind.LINKED, { progress += it }) { result = it }
        runCurrent()
        assertEquals(0, (fakeNode.requests.last() as NodeReadLinkingTableRequest).page)

        fakeNode.respond { pageResponse(it, page(0, 2, 5, "1", "2", "3", "4")) }
        runCurrent()
        assertNull(manager.linkedDevicesFlow(node).value)
        assertEquals(1, (fakeNode.requests.last() as NodeReadLinkingTableRequest).page)

        fakeNode.respond { pageResponse(it, page(1, 2, 5, "5")) }
        runCurrent()

        val table = listOf("1", "2", "3", "4", "5").map(::entry)
        assertEquals(NodeLinkingResult.Success(table), result)
        assertEquals(table, manager.linkedDevicesFlow(node).value)
        assertEquals(listOf(1, 2), progress.map { it.pagesReceived })
        assertNull(manager.unlinkedDevicesFlow(node).value)
    }

    @Test
    fun `a failed read keeps the previous table`() = runTest {
        val fakeNode = FakeNode(backgroundScope)
        val manager = manager(fakeNode)

        manager.readTable(node, LinkingTableKind.UNLINKED, null) {}
        runCurrent()
        fakeNode.respond { pageResponse(it, page(0, 1, 1, "1")) }
        runCurrent()
        assertEquals(listOf(entry("1")), manager.unlinkedDevicesFlow(node).value)

        var result: NodeLinkingResult<List<NodeLinkingTableEntry>>? = null
        manager.readTable(node, LinkingTableKind.UNLINKED, null) { result = it }
        runCurrent()
        fakeNode.respond { pageResponse(it, page(0, 2, 5, "2", "3", "4", "5")) }
        runCurrent()
        fakeNode.respond { pageResponse(it, null) } // malformed second page
        runCurrent()

        assertEquals(NodeLinkingResult.InvalidResponse, result)
        assertEquals(listOf(entry("1")), manager.unlinkedDevicesFlow(node).value)
    }

    @Test
    fun `a read whose Node never answers fails after the command timeout`() = runTest {
        val fakeNode = FakeNode(backgroundScope)
        val manager = manager(fakeNode)
        var result: NodeLinkingResult<List<NodeLinkingTableEntry>>? = null

        manager.readTable(node, LinkingTableKind.LINKED, null) { result = it }
        // advanceUntilIdle doesn't run backgroundScope work, so step past the 30 s timeout.
        advanceTimeBy(31_000)
        runCurrent()

        assertEquals(NodeLinkingResult.TimedOut, result)
        assertTrue("retried", fakeNode.requests.size > 1)
    }

    @Test
    fun `starting a read cancels the running one`() = runTest {
        val fakeNode = FakeNode(backgroundScope)
        val manager = manager(fakeNode)
        var first: NodeLinkingResult<List<NodeLinkingTableEntry>>? = null
        var second: NodeLinkingResult<List<NodeLinkingTableEntry>>? = null

        manager.readTable(node, LinkingTableKind.LINKED, null) { first = it }
        runCurrent()
        manager.readTable(node, LinkingTableKind.LINKED, null) { second = it }
        runCurrent()

        assertEquals(NodeLinkingResult.Cancelled, first)
        assertNull(second)

        fakeNode.respond { pageResponse(it, page(0, 1, 1, "9")) }
        runCurrent()

        assertEquals(NodeLinkingResult.Success(listOf(entry("9"))), second)
        assertEquals(listOf(entry("9")), manager.linkedDevicesFlow(node).value)
    }

    @Test
    fun `commands fail straight away when the Node isn't connected`() = runTest {
        val manager = manager(fakeNode = null)
        val results = mutableListOf<NodeLinkingResult<*>>()

        manager.readTable(node, LinkingTableKind.LINKED, null) { results += it }
        manager.setDeviceLinked(node, true, CombustionProductType.PROBE, "1000DA4C", null) { results += it }
        manager.setProximityLinking(node, true) { results += it }
        manager.readProximityLinking(node) { results += it }
        runCurrent()

        assertEquals(List(4) { NodeLinkingResult.NotConnected }, results)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `link and unlink send the device payload`() = runTest {
        val fakeNode = FakeNode(backgroundScope)
        val manager = manager(fakeNode)
        val results = mutableListOf<NodeLinkingResult<Unit>>()

        manager.setDeviceLinked(node, true, CombustionProductType.DISPLAY, "DS10001234", null) { results += it }
        runCurrent()
        val link = fakeNode.requests.last() as NodeLinkDeviceRequest
        assertEquals(NodeMessageType.LINK_DEVICE, link.messageId)
        assertEquals(CombustionProductType.DISPLAY.type, link.data[10])
        fakeNode.respond(::ack)
        runCurrent()

        manager.setDeviceLinked(node, false, CombustionProductType.PROBE, "1000DA4C", null) { results += it }
        runCurrent()
        assertEquals(NodeMessageType.UNLINK_DEVICE, fakeNode.requests.last().messageId)
        fakeNode.respond(::ack)
        runCurrent()

        assertEquals(List(2) { NodeLinkingResult.Success(Unit) }, results)
    }

    @Test
    fun `a link the Node refuses fails straight away, without retrying`() = runTest {
        val fakeNode = FakeNode(backgroundScope)
        val manager = manager(fakeNode)
        var result: NodeLinkingResult<Unit>? = null

        manager.setDeviceLinked(node, true, CombustionProductType.PROBE, "1000DA4C", null) { result = it }
        runCurrent()
        fakeNode.respond { NodeLinkingAckResponse(false, it.requestId, 0u, 0u, NodeMessageType.LINK_DEVICE) }
        runCurrent()

        assertEquals(NodeLinkingResult.Rejected, result)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(1, fakeNode.requests.size)
    }

    @Test
    fun `link with an invalid serial number fails without sending`() = runTest {
        val fakeNode = FakeNode(backgroundScope)
        val manager = manager(fakeNode)
        val results = mutableListOf<NodeLinkingResult<Unit>>()

        manager.setDeviceLinked(node, true, CombustionProductType.CHARGER, "TOO SHORT", null) { results += it }
        manager.setDeviceLinked(node, true, CombustionProductType.PROBE, "XYZ", null) { results += it }
        manager.setDeviceLinked(node, true, CombustionProductType.UNKNOWN, "CR10007379", null) { results += it }
        runCurrent()

        assertEquals(List(3) { NodeLinkingResult.InvalidSerialNumber }, results)
        assertTrue(fakeNode.requests.isEmpty())
    }

    @Test
    fun `proximity linking set and read update the flow`() = runTest {
        val fakeNode = FakeNode(backgroundScope)
        val manager = manager(fakeNode)

        var set: NodeLinkingResult<Unit>? = null
        manager.setProximityLinking(node, true) { set = it }
        runCurrent()
        fakeNode.respond(::ack)
        runCurrent()
        assertEquals(NodeLinkingResult.Success(Unit), set)
        assertEquals(true, manager.proximityLinkingFlow(node).value)

        var read: NodeLinkingResult<Boolean>? = null
        manager.readProximityLinking(node) { read = it }
        runCurrent()
        fakeNode.respond { NodeReadProximityLinkingResponse(true, it.requestId, 0u, 1u, enabled = false) }
        runCurrent()
        assertEquals(NodeLinkingResult.Success(false), read)
        assertEquals(false, manager.proximityLinkingFlow(node).value)
    }

    @Test
    fun `a proximity-linking read without the setting is an invalid response`() = runTest {
        val fakeNode = FakeNode(backgroundScope)
        val manager = manager(fakeNode)
        var read: NodeLinkingResult<Boolean>? = null

        manager.readProximityLinking(node) { read = it }
        runCurrent()
        fakeNode.respond { NodeReadProximityLinkingResponse(true, it.requestId, 0u, 0u, enabled = null) }
        runCurrent()

        assertEquals(NodeLinkingResult.InvalidResponse, read)
        assertNull(manager.proximityLinkingFlow(node).value)
    }

    @Test
    fun `a command whose Node disconnects fails within the connection check, not the timeout`() = runTest {
        val fakeNode = FakeNode(backgroundScope)
        val manager = manager(fakeNode)
        var result: NodeLinkingResult<Unit>? = null

        manager.setDeviceLinked(node, true, CombustionProductType.PROBE, "1000DA4C", null) { result = it }
        runCurrent()
        fakeNode.connected = false
        advanceTimeBy(NodeLinkingManager.CONNECTION_CHECK_INTERVAL_MS)
        runCurrent()

        assertEquals(NodeLinkingResult.NotConnected, result)
        assertTrue(testScheduler.currentTime < 1_000)
    }

    @Test
    fun `clear cancels running commands, whose handlers get Cancelled`() = runTest {
        val fakeNode = FakeNode(backgroundScope)
        val manager = manager(fakeNode)
        val results = mutableListOf<NodeLinkingResult<*>>()

        manager.readTable(node, LinkingTableKind.LINKED, null) { results += it }
        manager.setDeviceLinked(node, true, CombustionProductType.PROBE, "1000DA4C", null) { results += it }
        manager.setProximityLinking(node, true) { results += it }
        manager.readProximityLinking(node) { results += it }
        runCurrent()
        assertEquals(4, fakeNode.requests.size)

        manager.clear()
        runCurrent()

        assertEquals(List(4) { NodeLinkingResult.Cancelled }, results)
        assertNull(manager.proximityLinkingFlow(node).value)
    }

    @Test
    fun `a command that succeeds on a retry stops waiting for the earlier attempt`() = runTest {
        val fakeNode = FakeNode(backgroundScope)
        val manager = manager(fakeNode)
        var result: NodeLinkingResult<Unit>? = null

        manager.setDeviceLinked(node, true, CombustionProductType.PROBE, "1000DA4C", null) { result = it }
        runCurrent()
        advanceTimeBy(5_001) // the retry interval
        runCurrent()
        assertEquals(2, fakeNode.requests.size)
        fakeNode.respond(::ack)
        runCurrent()

        assertEquals(NodeLinkingResult.Success(Unit), result)
        verify { fakeNode.device.cancelLinkingRequest(fakeNode.requests.first().requestId) }
    }

    @Test
    fun `clear resets flows already handed out`() = runTest {
        val fakeNode = FakeNode(backgroundScope)
        val manager = manager(fakeNode)
        val flow = manager.proximityLinkingFlow(node)
        manager.setProximityLinking(node, true) {}
        runCurrent()
        fakeNode.respond(::ack)
        runCurrent()

        manager.clear()

        assertNull(flow.value)
    }

    @Test
    fun `every heartbeat is emitted, including identical ones and ones from different senders`() = runTest {
        val manager = manager(fakeNode = null)
        val received = mutableListOf<NodeHeartbeat>()
        backgroundScope.launch { manager.heartbeatFlow.collect { received += it } }
        runCurrent()

        val fromDisplay = heartbeat("DS10001234")
        manager.handleHeartbeat(fromDisplay)
        manager.handleHeartbeat(fromDisplay)
        manager.handleHeartbeat(heartbeat("CR10007379")) // relayed through the same Node
        runCurrent()

        assertEquals(listOf("DS10001234", "DS10001234", "CR10007379"), received.map { it.serialNumber })
        assertEquals(
            NodeHeartbeat.Connection(true, "1000DA4C", CombustionProductType.PROBE, -60),
            received.first().connections.first(),
        )
    }

    private fun heartbeat(serial: String) = NodeHeartbeatRequest(
        serialNumber = serial,
        macAddress = "AA:BB:CC:01:02:03",
        productType = CombustionProductType.DISPLAY,
        hopCount = 1u,
        inbound = true,
        connectionDetails = listOf(
            NodeHeartbeatRequest.ConnectionDetail(true, "1000DA4C", CombustionProductType.PROBE, -60),
        ) + List(3) { NodeHeartbeatRequest.ConnectionDetail(false) },
        requestId = 1u,
        payloadLength = 71u,
    )
}
