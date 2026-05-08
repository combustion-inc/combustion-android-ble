/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeIdManagerTest.kt
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
import inc.combustion.framework.service.Probe
import inc.combustion.framework.service.ProbeID
import io.mockk.*
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

@OptIn(ExperimentalCoroutinesApi::class)
class ProbeIdManagerTest {

    private val setProbeID: (String, ProbeID, (Boolean) -> Unit) -> Unit = mockk()

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

    @Test
    fun `availableProbeIDs should initially contain all IDs`() = runTest {
        val probeIdManager = ProbeIdManager(setProbeID, backgroundScope)
        val available = probeIdManager.availableProbeIDs.first()
        assertEquals(ProbeID.entries.toList(), available)
    }

    @Test
    fun `adding a device should update availableProbeIDs`() = runTest {
        val probeIdManager = ProbeIdManager(setProbeID, backgroundScope)
        val serial = "ABC1"
        val probeManager = mockk<ProbeManager>()
        val probe = mockProbe(ProbeID.ID1, isPlaceholder = false)
        
        val deviceFlow = MutableStateFlow(probe)
        every { probeManager.deviceFlow } returns deviceFlow

        probeIdManager.addDevice(serial, probeManager)
        
        // Wait for ID1 to disappear from available IDs
        val available = probeIdManager.availableProbeIDs
            .filter { !it.contains(ProbeID.ID1) }
            .first()
            
        assertFalse("ID1 should not be available", available.contains(ProbeID.ID1))
        assertTrue("ID2 should be available", available.contains(ProbeID.ID2))
    }

    @Test
    fun `addDevice should ignore placeholder devices`() = runTest {
        val probeIdManager = ProbeIdManager(setProbeID, backgroundScope)
        val serial = "ABC1"
        val probeManager = mockk<ProbeManager>()
        val probe = mockProbe(ProbeID.ID1, isPlaceholder = true)
        
        val deviceFlow = MutableStateFlow(probe)
        every { probeManager.deviceFlow } returns deviceFlow

        probeIdManager.addDevice(serial, probeManager)
        advanceUntilIdle()

        val available = probeIdManager.availableProbeIDs.first()
        assertTrue("ID1 should still be available because device was placeholder", available.contains(ProbeID.ID1))
    }

    @Test
    fun `conflict resolution - higher serial gets lower ID`() = runTest {
        val probeIdManager = ProbeIdManager(setProbeID, backgroundScope)
        val serialHigher = "00000014" // 20
        val serialLower = "0000000A" // 10

        val probeManagerLower = mockk<ProbeManager>()
        val deviceFlowLower = MutableStateFlow(mockProbe(ProbeID.ID2, isPlaceholder = false))
        every { probeManagerLower.deviceFlow } returns deviceFlowLower

        val probeManagerHigher = mockk<ProbeManager>()
        val deviceFlowHigher = MutableStateFlow(mockProbe(ProbeID.ID2, isPlaceholder = false))
        every { probeManagerHigher.deviceFlow } returns deviceFlowHigher

        // First device (Lower serial) joins and gets ID2
        probeIdManager.addDevice(serialLower, probeManagerLower)
        probeIdManager.availableProbeIDs.filter { !it.contains(ProbeID.ID2) }.first()

        // Second device (Higher serial) joins and also wants ID2 -> Conflict
        every { setProbeID(any(), any(), any()) } just runs

        probeIdManager.addDevice(serialHigher, probeManagerHigher)
        
        // resolveProbeIdConflict will assign ID1 to Higher and ID2 to Lower
        // We wait for ID1 to be taken as well
        probeIdManager.availableProbeIDs.filter { !it.contains(ProbeID.ID1) }.first()

        // Verify setProbeID calls
        verify(exactly = 1) { setProbeID(serialHigher, ProbeID.ID1, any()) }
        verify(exactly = 1) { setProbeID(serialLower, ProbeID.ID2, any()) }
    }

    @Test
    fun `setProbeID failure should revert changes in map`() = runTest {
        val probeIdManager = ProbeIdManager(setProbeID, backgroundScope)
        val serialHigher = "00000014"
        val serialLower = "0000000A"

        val probeManagerLower = mockk<ProbeManager>()
        val deviceFlowLower = MutableStateFlow(mockProbe(ProbeID.ID2, isPlaceholder = false))
        every { probeManagerLower.deviceFlow } returns deviceFlowLower

        val probeManagerHigher = mockk<ProbeManager>()
        val deviceFlowHigher = MutableStateFlow(mockProbe(ProbeID.ID2, isPlaceholder = false))
        every { probeManagerHigher.deviceFlow } returns deviceFlowHigher

        probeIdManager.addDevice(serialLower, probeManagerLower)
        probeIdManager.availableProbeIDs.filter { !it.contains(ProbeID.ID2) }.first()

        val callbackSlotHigher = slot<(Boolean) -> Unit>()
        val callbackSlotLower = slot<(Boolean) -> Unit>()
        every { setProbeID(serialHigher, ProbeID.ID1, capture(callbackSlotHigher)) } just runs
        every { setProbeID(serialLower, ProbeID.ID2, capture(callbackSlotLower)) } just runs

        probeIdManager.addDevice(serialHigher, probeManagerHigher)
        probeIdManager.availableProbeIDs.filter { !it.contains(ProbeID.ID1) }.first()

        // Simulate failure for higher serial
        callbackSlotHigher.captured.invoke(false)
        
        // ID1 should now be available again
        probeIdManager.availableProbeIDs.filter { it.contains(ProbeID.ID1) }.first()
        
        val available = probeIdManager.availableProbeIDs.first()
        assertTrue(available.contains(ProbeID.ID1))
        assertFalse(available.contains(ProbeID.ID2))
    }

    @Test
    fun `removeDevice should cancel observation and clear assignments`() = runTest {
        val probeIdManager = ProbeIdManager(setProbeID, backgroundScope)
        val serial = "ABC1"
        val probeManager = mockk<ProbeManager>()
        val probe = mockProbe(ProbeID.ID1, isPlaceholder = false)
        val deviceFlow = MutableStateFlow(probe)
        every { probeManager.deviceFlow } returns deviceFlow

        probeIdManager.addDevice(serial, probeManager)
        probeIdManager.availableProbeIDs.filter { !it.contains(ProbeID.ID1) }.first()

        probeIdManager.removeDevice(serial)
        probeIdManager.availableProbeIDs.filter { it.contains(ProbeID.ID1) }.first()

        assertTrue(probeIdManager.availableProbeIDs.first().contains(ProbeID.ID1))

        // Pushing a new ID to the flow should not update the manager anymore
        deviceFlow.value = mockProbe(ProbeID.ID2, isPlaceholder = false)
        advanceUntilIdle()

        assertTrue(probeIdManager.availableProbeIDs.first().contains(ProbeID.ID2))
    }

    @Test
    fun `clear should cancel all observations and clear all assignments`() = runTest {
        val probeIdManager = ProbeIdManager(setProbeID, backgroundScope)
        val serial1 = "ABC1"
        val serial2 = "ABC2"
        val probeManager1 = mockk<ProbeManager>(relaxed = true)
        val probeManager2 = mockk<ProbeManager>(relaxed = true)
        
        val deviceFlow1 = MutableStateFlow(mockProbe(ProbeID.ID1, isPlaceholder = false))
        val deviceFlow2 = MutableStateFlow(mockProbe(ProbeID.ID2, isPlaceholder = false))
        
        every { probeManager1.deviceFlow } returns deviceFlow1
        every { probeManager2.deviceFlow } returns deviceFlow2

        probeIdManager.addDevice(serial1, probeManager1)
        probeIdManager.addDevice(serial2, probeManager2)
        
        probeIdManager.availableProbeIDs.filter { !it.contains(ProbeID.ID1) && !it.contains(ProbeID.ID2) }.first()

        probeIdManager.clear()
        probeIdManager.availableProbeIDs.filter { it.contains(ProbeID.ID1) && it.contains(ProbeID.ID2) }.first()

        assertEquals(ProbeID.entries.toList(), probeIdManager.availableProbeIDs.first())
    }

    @Test
    fun `hasProbeIdConflict returns true only if a DIFFERENT device has the ID`() = runTest {
        val probeIdManager = ProbeIdManager(setProbeID, backgroundScope)
        val serial1 = "SERIAL1"
        val serial2 = "SERIAL2"
        val probeManager = mockk<ProbeManager>()
        val deviceFlow = MutableStateFlow(mockProbe(ProbeID.ID1, isPlaceholder = false))
        every { probeManager.deviceFlow } returns deviceFlow

        probeIdManager.addDevice(serial1, probeManager)
        probeIdManager.availableProbeIDs.filter { !it.contains(ProbeID.ID1) }.first()

        assertTrue(probeIdManager.hasProbeIdConflict(serial2, ProbeID.ID1))
        assertFalse(probeIdManager.hasProbeIdConflict(serial1, ProbeID.ID1))
        assertFalse(probeIdManager.hasProbeIdConflict(serial2, ProbeID.ID2))
    }
    
    @Test
    fun `changing probe ID for same device removes old assignment`() = runTest {
        val probeIdManager = ProbeIdManager(setProbeID, backgroundScope)
        val serial = "ABC1"
        val probeManager = mockk<ProbeManager>()
        val deviceFlow = MutableStateFlow(mockProbe(ProbeID.ID1, isPlaceholder = false))
        every { probeManager.deviceFlow } returns deviceFlow

        probeIdManager.addDevice(serial, probeManager)
        probeIdManager.availableProbeIDs.filter { !it.contains(ProbeID.ID1) }.first()

        // Change ID to ID2
        deviceFlow.value = mockProbe(ProbeID.ID2, isPlaceholder = false)
        
        // Wait for ID2 to be taken and ID1 to be released
        val available = probeIdManager.availableProbeIDs
            .filter { it.contains(ProbeID.ID1) && !it.contains(ProbeID.ID2) }
            .first()

        assertTrue("Old ID1 should be available", available.contains(ProbeID.ID1))
        assertFalse("New ID2 should be taken", available.contains(ProbeID.ID2))
    }
}
