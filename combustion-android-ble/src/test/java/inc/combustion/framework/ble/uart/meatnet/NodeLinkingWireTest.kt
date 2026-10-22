/*
 * Project: Combustion Inc. Android Framework
 * File: NodeLinkingWireTest.kt
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

package inc.combustion.framework.ble.uart.meatnet

import android.util.Log
import inc.combustion.framework.ble.getCRC16CCITT
import inc.combustion.framework.ble.putLittleEndianUInt32At
import inc.combustion.framework.ble.putLittleEndianUShortAt
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.NodeLinkingSerialNumber
import inc.combustion.framework.service.NodeLinkingTableEntry
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date

/**
 * Byte layouts follow the MeatNet Node BLE specification's "Read Linked Devices (0x73)" through
 * "Sync Link Table (0x7B)" and "Product Serial Number" sections.
 */
class NodeLinkingWireTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    // ---- product serial number ----

    @Test
    fun `probe serial number is a little-endian UInt32 in bytes 0-3, padded with 0xFF`() {
        val encoded = NodeLinkingWire.encodeProductSerialNumber(CombustionProductType.PROBE, "1000DA4C")

        assertArrayEquals(
            ubyteArrayOf(0x4Cu, 0xDAu, 0x00u, 0x10u, 0xFFu, 0xFFu, 0xFFu, 0xFFu, 0xFFu, 0xFFu, 0xFFu, 0xFFu)
                .toByteArray(),
            encoded!!.toByteArray(),
        )
    }

    @Test
    fun `node serial number is 10 UTF-8 bytes in bytes 0-9, padded with 0xFF`() {
        val encoded = NodeLinkingWire.encodeProductSerialNumber(CombustionProductType.CHARGER, "CR10007379")!!

        assertEquals("CR10007379", String(encoded.sliceArray(0 until 10).toByteArray(), Charsets.UTF_8))
        assertEquals(listOf<UByte>(0xFFu, 0xFFu), encoded.slice(10 until 12))
    }

    @Test
    fun `invalid serial numbers are rejected`() {
        assertNull(NodeLinkingWire.encodeProductSerialNumber(CombustionProductType.PROBE, "not hex"))
        assertNull(NodeLinkingWire.encodeProductSerialNumber(CombustionProductType.PROBE, "1FFFFFFFF"))
        assertNull(NodeLinkingWire.encodeProductSerialNumber(CombustionProductType.DISPLAY, "SHORT"))
        assertNull(NodeLinkingWire.encodeProductSerialNumber(CombustionProductType.DISPLAY, "ELEVENCHARS"))
        assertNull(NodeLinkingWire.encodeProductSerialNumber(CombustionProductType.UNKNOWN, "CR10007379"))
    }

    @Test
    fun `device payload is type, serial number and little-endian timestamp`() {
        val payload = NodeLinkingWire.encodeDevicePayload(CombustionProductType.PROBE, "1000DA4C", 0x01020304u)!!

        assertEquals(17, payload.size)
        assertEquals(CombustionProductType.PROBE.type, payload[0])
        assertEquals(listOf<UByte>(0x04u, 0x03u, 0x02u, 0x01u), payload.slice(13 until 17))
    }

    @Test
    fun `timestamp converts to Unix seconds, null as 0`() {
        with(NodeLinkingWire) {
            assertEquals(1_700_000_000u, Date(1_700_000_000_000L).toTimestamp())
            assertEquals(0u, null.toTimestamp())
        }
    }

    @Test
    fun `device payload round-trips through parseDevicePayload`() {
        val payload = NodeLinkingWire.encodeDevicePayload(CombustionProductType.GAUGE, "GA12345678", 42u)!!

        val parsed = NodeLinkingWire.parseDevicePayload(payload, 0, payload.size)

        assertEquals(
            NodeLinkingWire.DevicePayload(
                CombustionProductType.GAUGE,
                NodeLinkingSerialNumber.Node("GA12345678"),
                42u,
            ),
            parsed,
        )
        assertNull(NodeLinkingWire.parseDevicePayload(payload, 0, 16))
    }

    // ---- pages ----

    @Test
    fun `page with probe, node and legacy entries is parsed`() {
        val payload = pagePayload(
            page = 0, totalPages = 1, totalCount = 3,
            entry(CombustionProductType.PROBE, probeSerial(0x1000DA4Cu), timestamp = 1_700_000_000u),
            entry(CombustionProductType.CHARGER, nodeSerial("CR10007379"), timestamp = 0u),
            entry(CombustionProductType.DISPLAY, legacyMac(0xAAu, 0xBBu, 0xCCu, 0x01u, 0x02u, 0x03u), legacy = 1u),
        )

        val page = NodeLinkingWire.parsePage(payload, 0, payload.size, permitsLegacyEntries = true)!!

        assertEquals(NodeLinkingWire.Page(0, 1, 3, page.entries), page)
        assertEquals(
            listOf(
                NodeLinkingTableEntry(
                    CombustionProductType.PROBE,
                    NodeLinkingSerialNumber.Probe("1000DA4C"),
                    Date(1_700_000_000_000L),
                    isLegacy = false,
                ),
                NodeLinkingTableEntry(
                    CombustionProductType.CHARGER,
                    NodeLinkingSerialNumber.Node("CR10007379"),
                    timestamp = null,
                    isLegacy = false,
                ),
                NodeLinkingTableEntry(
                    CombustionProductType.DISPLAY,
                    NodeLinkingSerialNumber.LegacyMacAddress("AA:BB:CC:01:02:03"),
                    timestamp = null,
                    isLegacy = true,
                ),
            ),
            page.entries,
        )
    }

    @Test
    fun `empty page is parsed`() {
        val payload = pagePayload(page = 0, totalPages = 0, totalCount = 0)

        assertEquals(
            NodeLinkingWire.Page(0, 0, 0, emptyList()),
            NodeLinkingWire.parsePage(payload, 0, payload.size, permitsLegacyEntries = true),
        )
    }

    @Test
    fun `malformed pages are rejected`() {
        val entry = entry(CombustionProductType.PROBE, probeSerial(1u))

        // More than 4 entries on a page.
        val five = pagePayload(0, 2, 5, entry, entry, entry, entry, entry)
        assertNull(NodeLinkingWire.parsePage(five, 0, five.size, permitsLegacyEntries = true))

        // More entries on the page than in the whole table.
        val tooMany = pagePayload(0, 1, 1, entry, entry)
        assertNull(NodeLinkingWire.parsePage(tooMany, 0, tooMany.size, permitsLegacyEntries = true))

        // Payload shorter than its entries.
        val short = pagePayload(0, 1, 1, entry)
        assertNull(NodeLinkingWire.parsePage(short, 0, short.size - 1, permitsLegacyEntries = true))

        // Legacy flag that isn't 0 or 1.
        val badLegacy = pagePayload(0, 1, 1, entry(CombustionProductType.PROBE, probeSerial(1u), legacy = 2u))
        assertNull(NodeLinkingWire.parsePage(badLegacy, 0, badLegacy.size, permitsLegacyEntries = true))
    }

    @Test
    fun `legacy entries are rejected in unlinked tables`() {
        val payload = pagePayload(
            0, 1, 1,
            entry(CombustionProductType.DISPLAY, legacyMac(1u, 2u, 3u, 4u, 5u, 6u), legacy = 1u),
        )

        assertTrue(NodeLinkingWire.parsePage(payload, 0, payload.size, permitsLegacyEntries = true) != null)
        assertNull(NodeLinkingWire.parsePage(payload, 0, payload.size, permitsLegacyEntries = false))
    }

    // ---- full frames ----

    @Test
    fun `read linked devices response frame is parsed into a page response`() {
        val payload = pagePayload(0, 1, 1, entry(CombustionProductType.PROBE, probeSerial(0x1000DA4Cu)))
        val frame = responseFrame(NodeMessageType.READ_LINKED_DEVICES, requestId = 7u, payload = payload)

        val response = NodeUARTMessage.fromData(frame).single() as NodeLinkingTablePageResponse

        assertEquals(7u, response.requestId)
        assertEquals(NodeMessageType.READ_LINKED_DEVICES, response.messageId)
        assertEquals(NodeLinkingSerialNumber.Probe("1000DA4C"), response.page!!.entries.single().serialNumber)
    }

    @Test
    fun `malformed page frame is still a response, with no page`() {
        val payload = pagePayload(0, 1, 1, entry(CombustionProductType.DISPLAY, legacyMac(1u, 2u, 3u, 4u, 5u, 6u), legacy = 1u))
        val frame = responseFrame(NodeMessageType.READ_UNLINKED_DEVICES, requestId = 7u, payload = payload)

        val response = NodeUARTMessage.fromData(frame).single() as NodeLinkingTablePageResponse

        assertNull(response.page)
    }

    @Test
    fun `read proximity linking response frame is parsed`() {
        val frame = responseFrame(NodeMessageType.READ_PROXIMITY_LINKING, requestId = 3u, payload = ubyteArrayOf(1u))

        val response = NodeUARTMessage.fromData(frame).single() as NodeReadProximityLinkingResponse

        assertEquals(true, response.enabled)
    }

    @Test
    fun `ack responses are parsed for link, unlink, set proximity linking and sync`() {
        listOf(
            NodeMessageType.LINK_DEVICE,
            NodeMessageType.UNLINK_DEVICE,
            NodeMessageType.SET_PROXIMITY_LINKING,
            NodeMessageType.SYNC_LINK_TABLE,
        ).forEach { type ->
            val response = NodeUARTMessage.fromData(responseFrame(type, 9u, UByteArray(0))).single()
            assertTrue("$type", response is NodeLinkingAckResponse)
            assertEquals(type, response.messageId)
        }
    }

    @Test
    fun `device linked notification is parsed and doesn't stop the messages after it from being parsed`() {
        val notification = requestFrame(
            NodeMessageType.DEVICE_LINKED,
            NodeLinkingWire.encodeDevicePayload(CombustionProductType.PROBE, "1000DA4C", 5u)!!,
        )
        val sync = requestFrame(NodeMessageType.SYNC_LINK_TABLE, UByteArray(0))
        val ack = responseFrame(NodeMessageType.LINK_DEVICE, 1u, UByteArray(0))

        val messages = NodeUARTMessage.fromData(notification + sync + ack)

        assertEquals(3, messages.size)
        val linked = messages[0] as NodeIncomingLinkingRequest
        assertEquals(NodeLinkingSerialNumber.Probe("1000DA4C"), linked.device!!.serialNumber)
        assertTrue(messages[1] is NodeIncomingLinkingRequest)
        assertTrue(messages[2] is NodeLinkingAckResponse)
    }

    @Test
    fun `requests encode their payloads`() {
        val read = NodeReadLinkingTableRequest("CR10007379", NodeMessageType.READ_UNLINKED_DEVICES, page = 2, requestId = 1u)
        assertEquals(NodeMessageType.READ_UNLINKED_DEVICES.value, read.data[4])
        assertEquals(listOf<UByte>(1u, 2u), read.data.slice(9..10)) // payload length, page

        val set = NodeSetProximityLinkingRequest("CR10007379", enabled = true, requestId = 1u)
        assertEquals(listOf<UByte>(1u, 1u), set.data.slice(9..10))

        val readProximity = NodeReadProximityLinkingRequest("CR10007379", requestId = 1u)
        assertEquals(0.toUByte(), readProximity.data[9])
        assertFalse(readProximity.data.size > 10)
    }

    // ---- helpers ----

    private fun probeSerial(value: UInt): UByteArray =
        UByteArray(12) { 0xFFu }.also { it.putLittleEndianUInt32At(0, value) }

    private fun nodeSerial(value: String): UByteArray =
        UByteArray(12) { 0xFFu }.also { value.encodeToByteArray().toUByteArray().copyInto(it) }

    private fun legacyMac(vararg bytes: UByte): UByteArray =
        UByteArray(12) { 0xFFu }.also { bytes.toUByteArray().copyInto(it) }

    private fun entry(
        productType: CombustionProductType,
        serialNumber: UByteArray,
        timestamp: UInt = 0u,
        legacy: UByte = 0u,
    ): RawEntry {
        val entry = UByteArray(18)
        entry[0] = productType.type
        serialNumber.copyInto(entry, destinationOffset = 1)
        entry.putLittleEndianUInt32At(13, timestamp)
        entry[17] = legacy
        return RawEntry(entry)
    }

    // vararg can't be a UByteArray, so entries are wrapped.
    private class RawEntry(val bytes: UByteArray)

    private fun pagePayload(page: Int, totalPages: Int, totalCount: Int, vararg entries: RawEntry): UByteArray =
        ubyteArrayOf(page.toUByte(), totalPages.toUByte(), totalCount.toUByte(), entries.size.toUByte()) +
                entries.fold(UByteArray(0)) { acc, e -> acc + e.bytes }

    private fun responseFrame(type: NodeMessageType, requestId: UInt, payload: UByteArray, success: Boolean = true): UByteArray {
        val frame = UByteArray(15 + payload.size)
        frame[0] = 0xCAu
        frame[1] = 0xFEu
        frame[4] = type.value or 0x80u
        frame.putLittleEndianUInt32At(5, requestId)
        frame.putLittleEndianUInt32At(9, requestId + 1u)
        frame[13] = if (success) 1u else 0u
        frame[14] = payload.size.toUByte()
        payload.copyInto(frame, destinationOffset = 15)
        frame.putLittleEndianUShortAt(2, frame.copyOfRange(4, frame.size).getCRC16CCITT())
        return frame
    }

    private fun requestFrame(type: NodeMessageType, payload: UByteArray): UByteArray =
        NodeRequest(payload, type, requestId = 11u, serialNumber = null).data
}
