/*
 * Project: Combustion Inc. Android Framework
 * File: NodeConnectionManagerTest.kt
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
import inc.combustion.framework.ble.device.DeviceID
import inc.combustion.framework.ble.device.NodeBleDevice
import inc.combustion.framework.ble.scanning.NodeDeviceInfoAdvertisingData
import inc.combustion.framework.ble.scanning.ProbeAdvertisingData
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.DeviceConnectionState
import inc.combustion.framework.service.MeatNetNode
import inc.combustion.framework.service.ModelInformation
import inc.combustion.framework.service.dfu.DfuProductType
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NodeConnectionManagerTest {

    private val booster = "CR10007379"
    private val boosterId: DeviceID = "AA:BB:CC:01:02:03"

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    /** A Node device whose connection the test controls. */
    private class FakeNode {
        var connected = false
        var held = false
        var serialNumber: String? = null
        var model: ModelInformation? = null
        val device = mockk<NodeBleDevice>(relaxed = true).also { node ->
            every { node.isConnected } answers { connected }
            every { node.connectionState } answers {
                if (connected) DeviceConnectionState.CONNECTED else DeviceConnectionState.DISCONNECTED
            }
            every { node.isConnectionHeld } answers { held }
            every { node.isConnectionHeld = any() } answers { held = firstArg() }
            every { node.deviceInfoSerialNumber } answers { serialNumber }
            every { node.deviceInfoModelInformation } answers { model }
        }
    }

    private class Harness(scope: TestScope) {
        var time = 1_000_000L
        val nodes = mutableMapOf<DeviceID, FakeNode>()
        val created = mutableListOf<DeviceID>()
        var neededByFramework = false
        val manager = NodeConnectionManager(
            scope = scope.backgroundScope,
            nodeDevice = { id -> nodes[id]?.device },
            createNodeDevice = { advertisement ->
                created += advertisement.id
                nodes.getOrPut(advertisement.id) { FakeNode() }.device
            },
            isNeededByFramework = { neededByFramework },
            now = { time },
        )

        fun node(serial: String): MeatNetNode = manager.nodesFlow.value.single { it.serialNumber == serial }
    }

    private fun deviceInfo(
        rssi: Int,
        highRadioPower: Boolean = false,
        type: CombustionProductType = CombustionProductType.CHARGER,
        isConnectable: Boolean = true,
    ) = NodeDeviceInfoAdvertisingData(
        mac = boosterId,
        name = "",
        rssi = rssi,
        productType = type,
        isConnectable = isConnectable,
        serialNumber = booster,
        highRadioPower = highRadioPower,
    )

    private fun repeater(id: DeviceID = boosterId, rssi: Int = -70, isConnectable: Boolean = true) =
        mockk<ProbeAdvertisingData>(relaxed = true).also {
            every { it.mac } returns id
            every { it.id } returns id
            every { it.productType } returns CombustionProductType.NODE
            every { it.rssi } returns rssi
            every { it.isConnectable } returns isConnectable
        }

    // ---- advertising parser ----

    @Test
    fun `device info advertisement gives serial number and high radio power`() {
        val manufacturerData = UByteArray(23).also {
            it[0] = CombustionProductType.CHARGER.type
            booster.encodeToByteArray().toUByteArray().copyInto(it, 1)
            it[11] = 0x01u
        }

        val parsed = NodeDeviceInfoAdvertisingData.create(
            boosterId, "", -50, true, manufacturerData, CombustionProductType.CHARGER,
        )!!

        assertEquals(booster, parsed.serialNumber)
        assertEquals(CombustionProductType.CHARGER, parsed.productType)
        assertTrue(parsed.highRadioPower)
        assertNull(
            NodeDeviceInfoAdvertisingData.create(
                boosterId, "", -50, true, manufacturerData.copyOf(8), CombustionProductType.CHARGER,
            )
        )
    }

    // ---- the list ----

    @Test
    fun `a Node is listed with the identity from its device info advertisement`() = runTest {
        val h = Harness(this)

        h.manager.handleAdvertisement(deviceInfo(rssi = -70, highRadioPower = true))

        val node = h.node(booster)
        assertEquals(boosterId, node.id)
        assertEquals(CombustionProductType.CHARGER, node.productType)
        assertTrue(node.highRadioPower)
        assertEquals(-70, node.rssi)
        assertEquals(DeviceConnectionState.ADVERTISING_CONNECTABLE, node.connectionState)
        assertFalse(node.isConnectionRequested)
    }

    @Test
    fun `without a device info advertisement, serial number and type come from the connected Node`() = runTest {
        val h = Harness(this)
        val fake = FakeNode().also { h.nodes[boosterId] = it }

        h.manager.handleAdvertisement(repeater())
        assertEquals("", h.manager.nodesFlow.value.single().serialNumber)
        assertEquals(CombustionProductType.NODE, h.manager.nodesFlow.value.single().productType)

        fake.connected = true
        fake.serialNumber = "DS10001234"
        fake.model = ModelInformation(CombustionProductType.NODE, DfuProductType.DISPLAY, "", "")
        h.manager.refresh()

        val node = h.manager.nodesFlow.value.single()
        assertEquals("DS10001234", node.serialNumber)
        assertEquals(CombustionProductType.DISPLAY, node.productType)
        assertEquals(DeviceConnectionState.CONNECTED, node.connectionState)
    }

    @Test
    fun `the list is only republished when a Node's entry changes`() = runTest {
        val h = Harness(this)
        h.manager.handleAdvertisement(deviceInfo(rssi = -70))
        val first = h.manager.nodesFlow.value

        repeat(5) { h.manager.handleAdvertisement(deviceInfo(rssi = -70)) }
        assertTrue("unchanged, so the same list instance", first === h.manager.nodesFlow.value)

        h.manager.handleAdvertisement(deviceInfo(rssi = -60))
        val second = h.manager.nodesFlow.value
        assertTrue(first !== second)
        assertEquals(-67, second.single().rssi) // smoothed: -70 + (2/7) * 10 = -67.1, rounded
    }

    @Test
    fun `proximity range uses iOS thresholds with a gap between them`() = runTest {
        val h = Harness(this)

        h.manager.handleAdvertisement(deviceInfo(rssi = -50))
        assertFalse("-50 is below -48", h.node(booster).isInProximityRange)

        repeat(10) { h.manager.handleAdvertisement(deviceInfo(rssi = -45)) }
        assertTrue(h.node(booster).isInProximityRange)

        repeat(10) { h.manager.handleAdvertisement(deviceInfo(rssi = -52)) }
        assertTrue("-52 is above -55, so still in range", h.node(booster).isInProximityRange)

        repeat(10) { h.manager.handleAdvertisement(deviceInfo(rssi = -60)) }
        assertFalse(h.node(booster).isInProximityRange)
    }

    @Test
    fun `high radio power raises the proximity thresholds by 8 dB`() = runTest {
        val h = Harness(this)

        repeat(10) { h.manager.handleAdvertisement(deviceInfo(rssi = -45, highRadioPower = true)) }
        assertFalse("-45 is below -40", h.node(booster).isInProximityRange)

        repeat(10) { h.manager.handleAdvertisement(deviceInfo(rssi = -38, highRadioPower = true)) }
        assertTrue(h.node(booster).isInProximityRange)
    }

    @Test
    fun `a Node not heard for 15 s is out of range`() = runTest {
        val h = Harness(this)
        repeat(10) { h.manager.handleAdvertisement(deviceInfo(rssi = -40)) }
        assertTrue(h.node(booster).isInProximityRange)

        h.time += NodeConnectionManager.OUT_OF_RANGE_TIMEOUT_MS
        h.manager.refresh()

        val node = h.node(booster)
        assertEquals(DeviceConnectionState.OUT_OF_RANGE, node.connectionState)
        assertFalse(node.isInProximityRange)
    }

    // ---- held connections ----

    @Test
    fun `connecting to a Node the framework doesn't track creates its device and holds it`() = runTest {
        val h = Harness(this)
        h.manager.handleAdvertisement(repeater())
        h.manager.handleAdvertisement(deviceInfo(rssi = -60))

        h.manager.connectToBooster()

        val fake = h.nodes.getValue(boosterId)
        assertEquals(listOf(boosterId), h.created)
        assertTrue(fake.held)
        verify(exactly = 1) { fake.device.connect() }
        assertTrue(h.node(booster).isConnectionRequested)
    }

    @Test
    fun `a held Node is reconnected when it advertises again, at most every 5 s`() = runTest {
        val h = Harness(this)
        val fake = FakeNode().also { h.nodes[boosterId] = it }
        h.manager.handleAdvertisement(deviceInfo(rssi = -60))
        h.manager.connectToBooster()
        verify(exactly = 1) { fake.device.connect() }

        h.time += 1_000
        h.manager.handleAdvertisement(deviceInfo(rssi = -60))
        verify(exactly = 1) { fake.device.connect() }

        h.time += NodeConnectionManager.RECONNECT_INTERVAL_MS
        h.manager.handleAdvertisement(deviceInfo(rssi = -60, isConnectable = false))
        verify(exactly = 1) { fake.device.connect() }

        h.manager.handleAdvertisement(deviceInfo(rssi = -60))
        verify(exactly = 2) { fake.device.connect() }

        fake.connected = true
        h.time += NodeConnectionManager.RECONNECT_INTERVAL_MS
        h.manager.handleAdvertisement(deviceInfo(rssi = -60))
        verify(exactly = 2) { fake.device.connect() }
    }

    @Test
    fun `releasing a Node disconnects it only if the framework doesn't need it`() = runTest {
        val h = Harness(this)
        val fake = FakeNode().also { h.nodes[boosterId] = it }
        h.manager.handleAdvertisement(deviceInfo(rssi = -60))

        h.manager.connectToBooster()
        h.neededByFramework = true
        h.manager.disconnect(booster)
        assertFalse(fake.held)
        verify(exactly = 0) { fake.device.disconnect() }

        h.manager.connectToBooster()
        h.neededByFramework = false
        h.manager.disconnect(booster)
        verify(exactly = 1) { fake.device.disconnect() }
        assertFalse(h.node(booster).isConnectionRequested)
    }

    @Test
    fun `a Node held before its repeater advertisement arrives is connected and held once it does`() = runTest {
        val h = Harness(this)
        h.manager.handleAdvertisement(deviceInfo(rssi = -60))
        h.manager.connectToBooster()
        assertTrue(h.created.isEmpty())

        h.manager.handleAdvertisement(repeater())

        val fake = h.nodes.getValue(boosterId)
        assertTrue("the framework's disconnects must skip it", fake.held)
        verify(exactly = 1) { fake.device.connect() }
    }

    @Test
    fun `device info is read once per connection, even if the read fails`() = runTest {
        val h = Harness(this)
        val fake = FakeNode().also { h.nodes[boosterId] = it }
        h.manager.handleAdvertisement(repeater())
        fake.connected = true

        repeat(3) {
            h.manager.refresh()
            runCurrent()
        }
        coVerify(exactly = 1) { fake.device.readModelInformation() }

        fake.connected = false
        h.manager.refresh()
        fake.connected = true
        h.manager.refresh()
        runCurrent()
        coVerify(exactly = 2) { fake.device.readModelInformation() }
    }

    @Test
    fun `connecting to an unknown Node does nothing`() = runTest {
        val h = Harness(this)

        h.manager.connect("UNKNOWN123")

        assertTrue(h.manager.nodesFlow.value.isEmpty())
        assertTrue(h.created.isEmpty())
    }

    @Test
    fun `clear releases and disconnects held Nodes and forgets every Node`() = runTest {
        val h = Harness(this)
        val fake = FakeNode().also { h.nodes[boosterId] = it }
        h.manager.handleAdvertisement(deviceInfo(rssi = -60))
        h.manager.connectToBooster()

        h.manager.clear()

        assertFalse(fake.held)
        verify(exactly = 1) { fake.device.disconnect() }
        assertTrue(h.manager.nodesFlow.value.isEmpty())
    }

    @Test
    fun `releaseAll clears holds without disconnecting`() = runTest {
        val h = Harness(this)
        val fake = FakeNode().also { h.nodes[boosterId] = it }
        h.manager.handleAdvertisement(deviceInfo(rssi = -60))
        h.manager.connectToBooster()

        h.manager.releaseAll()

        assertFalse(fake.held)
        verify(exactly = 0) { fake.device.disconnect() }
        assertFalse(h.node(booster).isConnectionRequested)
    }

    @Test
    fun `a connected Node is found by the serial number from its advertising`() = runTest {
        val h = Harness(this)
        val fake = FakeNode().also { h.nodes[boosterId] = it }
        h.manager.handleAdvertisement(deviceInfo(rssi = -60))

        assertNull(h.manager.connectedNodeDevice(booster))
        fake.connected = true
        assertEquals(fake.device, h.manager.connectedNodeDevice(booster.lowercase()))
        runCurrent()
    }

    private fun NodeConnectionManager.connectToBooster() = connect(booster)
}
