/*
 * Project: Combustion Inc. Android Framework
 * File: DeviceIdManagerTest.kt
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
import inc.combustion.framework.service.Gauge
import inc.combustion.framework.service.GaugeID
import inc.combustion.framework.service.Probe
import inc.combustion.framework.service.ProbeID
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the shared [DeviceIdManager] through both of its production configurations -- see
 * `NetworkManager`'s `probeIdManager`/`gaugeIdManager` construction, which these helpers mirror --
 * plus a gauge-specific case for the "only a known id can conflict" behavior [Gauge.knownId]'s
 * nullability exists for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceIdManagerTest {

    private val setProbeID: (String, ProbeID, (Boolean) -> Unit) -> Unit = mockk()
    private val setGaugeID: (String, GaugeID, (Boolean) -> Unit) -> Unit = mockk()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun mockProbe(id: ProbeID?, isPlaceholder: Boolean): Probe {
        val probe = mockk<Probe>(relaxed = true)
        every { probe.id } returns id
        every { probe.isPlaceholder() } returns isPlaceholder
        return probe
    }

    private fun mockGauge(knownId: GaugeID?, isPlaceholder: Boolean): Gauge {
        val gauge = mockk<Gauge>(relaxed = true)
        every { gauge.knownId } returns knownId
        every { gauge.isPlaceholder() } returns isPlaceholder
        return gauge
    }

    private fun probeIdManager(scope: CoroutineScope) = DeviceIdManager<ProbeID, Probe>(
        logTag = "ProbeIdManagerTest",
        allIds = ProbeID.entries,
        extractId = { it.id },
        serialNumberWinsOver = { candidate, other ->
            val candidateNumeric = candidate.toLongOrNull(16)
            val otherNumeric = other.toLongOrNull(16)
            if (candidateNumeric == null || otherNumeric == null) null
            else candidateNumeric > otherNumeric
        },
        setId = setProbeID,
        scope = scope,
    )

    private fun gaugeIdManager(scope: CoroutineScope) = DeviceIdManager<GaugeID, Gauge>(
        logTag = "GaugeIdManagerTest",
        allIds = GaugeID.entries,
        extractId = { it.knownId },
        serialNumberWinsOver = { candidate, other -> candidate > other },
        setId = setGaugeID,
        scope = scope,
    )

    @Test
    fun `availableIds should initially contain all IDs`() = runTest {
        val deviceIdManager = probeIdManager(backgroundScope)
        val available = deviceIdManager.availableIds.first()
        assertEquals(ProbeID.entries.toList(), available)
    }

    @Test
    fun `adding a device should update availableIds`() = runTest {
        val deviceIdManager = probeIdManager(backgroundScope)
        val serial = "ABC1"
        val deviceFlow = MutableStateFlow(mockProbe(ProbeID.ID1, isPlaceholder = false))

        deviceIdManager.addDevice(serial, deviceFlow)

        // Wait for ID1 to disappear from available IDs
        val available = deviceIdManager.availableIds
            .filter { !it.contains(ProbeID.ID1) }
            .first()

        assertFalse("ID1 should not be available", available.contains(ProbeID.ID1))
        assertTrue("ID2 should be available", available.contains(ProbeID.ID2))
    }

    @Test
    fun `addDevice should ignore placeholder devices`() = runTest {
        val deviceIdManager = probeIdManager(backgroundScope)
        val serial = "ABC1"
        val deviceFlow = MutableStateFlow(mockProbe(ProbeID.ID1, isPlaceholder = true))

        deviceIdManager.addDevice(serial, deviceFlow)
        advanceUntilIdle()

        val available = deviceIdManager.availableIds.first()
        assertTrue("ID1 should still be available because device was placeholder", available.contains(ProbeID.ID1))
    }

    @Test
    fun `conflict resolution - higher serial gets lower ID`() = runTest {
        val deviceIdManager = probeIdManager(backgroundScope)
        val serialHigher = "00000014" // 20
        val serialLower = "0000000A" // 10

        val deviceFlowLower = MutableStateFlow(mockProbe(ProbeID.ID2, isPlaceholder = false))
        val deviceFlowHigher = MutableStateFlow(mockProbe(ProbeID.ID2, isPlaceholder = false))

        // First device (Lower serial) joins and gets ID2
        deviceIdManager.addDevice(serialLower, deviceFlowLower)
        deviceIdManager.availableIds.filter { !it.contains(ProbeID.ID2) }.first()

        // Second device (Higher serial) joins and also wants ID2 -> Conflict
        every { setProbeID(any(), any(), any()) } just runs

        deviceIdManager.addDevice(serialHigher, deviceFlowHigher)

        // resolveIdConflict will assign ID1 to Higher and ID2 to Lower
        // We wait for ID1 to be taken as well
        deviceIdManager.availableIds.filter { !it.contains(ProbeID.ID1) }.first()

        // Verify setProbeID calls
        verify(exactly = 1) { setProbeID(serialHigher, ProbeID.ID1, any()) }
        verify(exactly = 1) { setProbeID(serialLower, ProbeID.ID2, any()) }
    }

    @Test
    fun `setProbeID failure should revert changes in map`() = runTest {
        val deviceIdManager = probeIdManager(backgroundScope)
        val serialHigher = "00000014"
        val serialLower = "0000000A"

        val deviceFlowLower = MutableStateFlow(mockProbe(ProbeID.ID2, isPlaceholder = false))
        val deviceFlowHigher = MutableStateFlow(mockProbe(ProbeID.ID2, isPlaceholder = false))

        deviceIdManager.addDevice(serialLower, deviceFlowLower)
        deviceIdManager.availableIds.filter { !it.contains(ProbeID.ID2) }.first()

        val callbackSlotHigher = slot<(Boolean) -> Unit>()
        val callbackSlotLower = slot<(Boolean) -> Unit>()
        every { setProbeID(serialHigher, ProbeID.ID1, capture(callbackSlotHigher)) } just runs
        every { setProbeID(serialLower, ProbeID.ID2, capture(callbackSlotLower)) } just runs

        deviceIdManager.addDevice(serialHigher, deviceFlowHigher)
        deviceIdManager.availableIds.filter { !it.contains(ProbeID.ID1) }.first()

        // Simulate failure for higher serial
        callbackSlotHigher.captured.invoke(false)

        // ID1 should now be available again
        deviceIdManager.availableIds.filter { it.contains(ProbeID.ID1) }.first()

        val available = deviceIdManager.availableIds.first()
        assertTrue(available.contains(ProbeID.ID1))
        assertFalse(available.contains(ProbeID.ID2))
    }

    @Test
    fun `removeDevice should cancel observation and clear assignments`() = runTest {
        val deviceIdManager = probeIdManager(backgroundScope)
        val serial = "ABC1"
        val deviceFlow = MutableStateFlow(mockProbe(ProbeID.ID1, isPlaceholder = false))

        deviceIdManager.addDevice(serial, deviceFlow)
        deviceIdManager.availableIds.filter { !it.contains(ProbeID.ID1) }.first()

        deviceIdManager.removeDevice(serial)
        deviceIdManager.availableIds.filter { it.contains(ProbeID.ID1) }.first()

        assertTrue(deviceIdManager.availableIds.first().contains(ProbeID.ID1))

        // Pushing a new ID to the flow should not update the manager anymore
        deviceFlow.value = mockProbe(ProbeID.ID2, isPlaceholder = false)
        advanceUntilIdle()

        assertTrue(deviceIdManager.availableIds.first().contains(ProbeID.ID2))
    }

    @Test
    fun `clear should cancel all observations and clear all assignments`() = runTest {
        val deviceIdManager = probeIdManager(backgroundScope)
        val serial1 = "ABC1"
        val serial2 = "ABC2"

        val deviceFlow1 = MutableStateFlow(mockProbe(ProbeID.ID1, isPlaceholder = false))
        val deviceFlow2 = MutableStateFlow(mockProbe(ProbeID.ID2, isPlaceholder = false))

        deviceIdManager.addDevice(serial1, deviceFlow1)
        deviceIdManager.addDevice(serial2, deviceFlow2)

        deviceIdManager.availableIds.filter { !it.contains(ProbeID.ID1) && !it.contains(ProbeID.ID2) }.first()

        deviceIdManager.clear()
        deviceIdManager.availableIds.filter { it.contains(ProbeID.ID1) && it.contains(ProbeID.ID2) }.first()

        assertEquals(ProbeID.entries.toList(), deviceIdManager.availableIds.first())
    }

    @Test
    fun `hasIdConflict returns true only if a DIFFERENT device has the ID`() = runTest {
        val deviceIdManager = probeIdManager(backgroundScope)
        val serial1 = "SERIAL1"
        val serial2 = "SERIAL2"
        val deviceFlow = MutableStateFlow(mockProbe(ProbeID.ID1, isPlaceholder = false))

        deviceIdManager.addDevice(serial1, deviceFlow)
        deviceIdManager.availableIds.filter { !it.contains(ProbeID.ID1) }.first()

        assertTrue(deviceIdManager.hasIdConflict(serial2, ProbeID.ID1))
        assertFalse(deviceIdManager.hasIdConflict(serial1, ProbeID.ID1))
        assertFalse(deviceIdManager.hasIdConflict(serial2, ProbeID.ID2))
    }

    @Test
    fun `changing probe ID for same device removes old assignment`() = runTest {
        val deviceIdManager = probeIdManager(backgroundScope)
        val serial = "ABC1"
        val deviceFlow = MutableStateFlow(mockProbe(ProbeID.ID1, isPlaceholder = false))

        deviceIdManager.addDevice(serial, deviceFlow)
        deviceIdManager.availableIds.filter { !it.contains(ProbeID.ID1) }.first()

        // Change ID to ID2
        deviceFlow.value = mockProbe(ProbeID.ID2, isPlaceholder = false)

        // Wait for ID2 to be taken and ID1 to be released
        val available = deviceIdManager.availableIds
            .filter { it.contains(ProbeID.ID1) && !it.contains(ProbeID.ID2) }
            .first()

        assertTrue("Old ID1 should be available", available.contains(ProbeID.ID1))
        assertFalse("New ID2 should be taken", available.contains(ProbeID.ID2))
    }

    @Test
    fun `gauge with unknown id never enters conflict tracking`() = runTest {
        val deviceIdManager = gaugeIdManager(backgroundScope)
        val knownSerial = "GAUGEKNOWN"
        val unknownSerial = "GAUGEUNKNOWN"

        // A gauge assigned an ID beyond the 8 GaugeID models -- Gauge.knownId is null for it.
        val unknownFlow = MutableStateFlow(mockGauge(knownId = null, isPlaceholder = false))
        deviceIdManager.addDevice(unknownSerial, unknownFlow)
        advanceUntilIdle()

        // It never took a slot, so all 8 remain available and it can't conflict with anything.
        assertEquals(GaugeID.entries.toList(), deviceIdManager.availableIds.first())
        assertFalse(deviceIdManager.hasIdConflict(knownSerial, GaugeID.ID1))

        // A gauge with a known id still participates normally.
        val knownFlow = MutableStateFlow(mockGauge(GaugeID.ID1, isPlaceholder = false))
        deviceIdManager.addDevice(knownSerial, knownFlow)
        val available = deviceIdManager.availableIds.filter { !it.contains(GaugeID.ID1) }.first()

        assertFalse(available.contains(GaugeID.ID1))
        assertTrue(deviceIdManager.hasIdConflict(unknownSerial, GaugeID.ID1))
    }

    @Test
    fun `gauge conflict resolution ranks serials lexicographically`() = runTest {
        val deviceIdManager = gaugeIdManager(backgroundScope)
        val serialA = "GAUGE0001"
        val serialB = "GAUGE0002"

        val deviceFlowA = MutableStateFlow(mockGauge(GaugeID.ID2, isPlaceholder = false))
        val deviceFlowB = MutableStateFlow(mockGauge(GaugeID.ID2, isPlaceholder = false))

        deviceIdManager.addDevice(serialA, deviceFlowA)
        deviceIdManager.availableIds.filter { !it.contains(GaugeID.ID2) }.first()

        every { setGaugeID(any(), any(), any()) } just runs

        deviceIdManager.addDevice(serialB, deviceFlowB)
        deviceIdManager.availableIds.filter { !it.contains(GaugeID.ID1) }.first()

        // serialB > serialA lexicographically, so it wins and keeps the lower id.
        verify(exactly = 1) { setGaugeID(serialB, GaugeID.ID1, any()) }
        verify(exactly = 1) { setGaugeID(serialA, GaugeID.ID2, any()) }
    }

    @Test
    fun `gauge reassigned to an unknown id releases its old assignment`() = runTest {
        val deviceIdManager = gaugeIdManager(backgroundScope)
        val serial = "GAUGE0001"
        val deviceFlow = MutableStateFlow(mockGauge(GaugeID.ID1, isPlaceholder = false))

        deviceIdManager.addDevice(serial, deviceFlow)
        deviceIdManager.availableIds.filter { !it.contains(GaugeID.ID1) }.first()

        // Device is reassigned to an id beyond what GaugeID models -- knownId goes back to null.
        deviceFlow.value = mockGauge(knownId = null, isPlaceholder = false)

        val available = deviceIdManager.availableIds.filter { it.contains(GaugeID.ID1) }.first()
        assertTrue("ID1 should be released, not left stale", available.contains(GaugeID.ID1))
        assertFalse(deviceIdManager.hasIdConflict("some other serial", GaugeID.ID1))
    }
}
