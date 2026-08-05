/*
 * Project: Combustion Inc. Android Framework
 * File: GaugeManagerTest.kt
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
import inc.combustion.framework.ble.device.SimulatedGaugeBleDevice
import inc.combustion.framework.service.DeviceManager
import inc.combustion.framework.service.GaugeStatusFlags
import inc.combustion.framework.service.HighLowAlarmStatus
import inc.combustion.framework.service.HopCount
import inc.combustion.framework.service.SensorTemperature
import inc.combustion.framework.service.SessionInformation
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Covers `GaugeManager.setHighLowAlarmStatus`, which sends via [CommandCoordinator] -- mirrors
 * the equivalent coverage in `EngineManagerTest` for `setTemperatureSetPoint`, the closest analog
 * (also a single Node-only command, no direct-link complications like `ProbeManagerTest`'s
 * equivalents).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GaugeManagerTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0

        // setHighLowAlarmStatus (and SimulatedGaugeBleDevice's own completion callback) dispatch
        // onto Dispatchers.Main -- tie it to the test's virtual-time scheduler.
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    private fun manager(scope: CoroutineScope): GaugeManager = GaugeManager(
        mac = "AA:BB:CC:DD:EE:FF",
        serialNumber = "12345678",
        scope = scope,
        settings = DeviceManager.Settings(),
        dfuDisconnectedNodeCallback = {},
    )

    private fun gaugeStatus(
        sessionID: UInt = 1u,
        maxSequenceNumber: UInt = 1u,
        highLowAlarmStatus: HighLowAlarmStatus = HighLowAlarmStatus.DEFAULT,
    ): GaugeStatus = GaugeStatus(
        sessionInformation = SessionInformation(sessionID = sessionID, samplePeriod = 1u),
        samplePeriod = 1u,
        temperature = SensorTemperature.NO_DATA,
        gaugeStatusFlags = GaugeStatusFlags(sensorPresent = true),
        minSequenceNumber = 0u,
        maxSequenceNumber = maxSequenceNumber,
        highLowAlarmStatus = highLowAlarmStatus,
        isNewRecord = false,
        hopCount = HopCount.HOP1,
    )

    // setHighLowAlarmStatus

    @Test
    fun `setHighLowAlarmStatus succeeds via a simulated device response, without needing a retry`() =
        runTest {
            val manager = manager(backgroundScope)
            manager.addSimulatedGauge(
                SimulatedGaugeBleDevice(scope = backgroundScope, serialNumber = "12345678"),
            )

            var result: Boolean? = null
            manager.setHighLowAlarmStatus(HighLowAlarmStatus.DEFAULT) { result = it }
            runCurrent()

            assertEquals(true, result)
        }

    @Test
    fun `setHighLowAlarmStatus retries roughly every 5s and fails at the 30s overall timeout when nothing is connected`() =
        runTest {
            val manager = manager(backgroundScope)

            var result: Boolean? = null
            manager.setHighLowAlarmStatus(HighLowAlarmStatus.DEFAULT) { result = it }

            // Nothing connected -- send() can never register an attempt key, so this can only
            // resolve via the overall 30s timeout, not a response.
            advanceTimeBy(29_000)
            runCurrent()
            assertNull(result)

            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(false, result)
        }

    @Test
    fun `setHighLowAlarmStatus completes early from a status update confirming the commanded value, with nothing connected to respond`() =
        runTest {
            val manager = manager(backgroundScope)
            val commandedStatus = HighLowAlarmStatus.DEFAULT.copy(
                highStatus = HighLowAlarmStatus.DEFAULT.highStatus.copy(set = true),
            )

            var result: Boolean? = null
            manager.setHighLowAlarmStatus(commandedStatus) { result = it }
            runCurrent()

            // No response is possible (nothing connected) -- but a status arrives, well before
            // the 30s timeout, already showing the commanded value took effect.
            advanceTimeBy(2_000)
            manager.observedGaugeStatus(
                gaugeStatus(maxSequenceNumber = 1u, highLowAlarmStatus = commandedStatus),
            )
            runCurrent()

            assertEquals(true, result)
        }

    @Test
    fun `setHighLowAlarmStatus does not complete from a status showing a different value when the starting value was never confirmed`() =
        runTest {
            // Regression test: no status has ever been received, so the starting value is
            // unknown (Absent) -- Gauge.highLowAlarmStatus defaulting to a non-null sentinel
            // (HighLowAlarmStatus.DEFAULT) must not be mistaken for a confirmed starting value.
            // A status arriving with some other value must not be treated as "a competing command
            // already changed it" -- there's no confirmed prior value for it to have changed
            // *from*. See CommandCoordinator.valueConfirmation's KDoc.
            val manager = manager(backgroundScope)
            val commandedStatus = HighLowAlarmStatus.DEFAULT.copy(
                highStatus = HighLowAlarmStatus.DEFAULT.highStatus.copy(set = true),
            )
            val competingStatus = HighLowAlarmStatus.DEFAULT.copy(
                lowStatus = HighLowAlarmStatus.DEFAULT.lowStatus.copy(set = true),
            )

            var result: Boolean? = null
            manager.setHighLowAlarmStatus(commandedStatus) { result = it }
            runCurrent()

            advanceTimeBy(2_000)
            manager.observedGaugeStatus(
                gaugeStatus(maxSequenceNumber = 1u, highLowAlarmStatus = competingStatus),
            )
            runCurrent()

            assertNull(result)
        }

    @Test
    fun `setHighLowAlarmStatus completes early when a status shows a different value than a confirmed starting status, instead of bouncing against a competing command`() =
        runTest {
            val manager = manager(backgroundScope)
            val startingStatus = HighLowAlarmStatus.DEFAULT.copy(
                highStatus = HighLowAlarmStatus.DEFAULT.highStatus.copy(
                    temperature = SensorTemperature(10.0),
                ),
            )
            val commandedStatus = HighLowAlarmStatus.DEFAULT.copy(
                highStatus = HighLowAlarmStatus.DEFAULT.highStatus.copy(set = true),
            )
            val competingStatus = HighLowAlarmStatus.DEFAULT.copy(
                lowStatus = HighLowAlarmStatus.DEFAULT.lowStatus.copy(set = true),
            )

            // Establish a confirmed starting value via a real status before the command is even
            // issued.
            manager.observedGaugeStatus(
                gaugeStatus(maxSequenceNumber = 1u, highLowAlarmStatus = startingStatus),
            )

            var result: Boolean? = null
            manager.setHighLowAlarmStatus(commandedStatus) { result = it }
            runCurrent()

            // Neither the confirmed starting value nor the commanded value -- some other actor
            // already changed it. Per CommandCoordinator.valueConfirmation, this still completes
            // as success rather than retrying against that competing command until timeout.
            advanceTimeBy(2_000)
            manager.observedGaugeStatus(
                gaugeStatus(maxSequenceNumber = 2u, highLowAlarmStatus = competingStatus),
            )
            runCurrent()

            assertEquals(true, result)
        }

    @Test
    fun `setHighLowAlarmStatus confirms an exact match even when the starting value was never confirmed`() =
        runTest {
            val manager = manager(backgroundScope)
            val commandedStatus = HighLowAlarmStatus.DEFAULT.copy(
                highStatus = HighLowAlarmStatus.DEFAULT.highStatus.copy(set = true),
            )

            var result: Boolean? = null
            manager.setHighLowAlarmStatus(commandedStatus) { result = it }
            runCurrent()

            advanceTimeBy(2_000)
            manager.observedGaugeStatus(
                gaugeStatus(maxSequenceNumber = 1u, highLowAlarmStatus = commandedStatus),
            )
            runCurrent()

            assertEquals(true, result)
        }

    @Test
    fun `setHighLowAlarmStatus does not confirm when an unrelated tripped or alarming flag differs, only set and temperature matter`() =
        runTest {
            // Regression test: AlarmStatus.tripped/alarming are live, device-computed flags, not
            // something this command sets -- comparing the full HighLowAlarmStatus (rather than
            // just Threshold's set/temperature) would let this spuriously look like a change to
            // the commanded value. See AlarmStatus.Threshold's KDoc.
            val manager = manager(backgroundScope)
            val commandedStatus = HighLowAlarmStatus.DEFAULT.copy(
                highStatus = HighLowAlarmStatus.DEFAULT.highStatus.copy(set = true),
            )

            var result: Boolean? = null
            manager.setHighLowAlarmStatus(commandedStatus) { result = it }
            runCurrent()

            // set/temperature still match the (unconfirmed) starting value -- the command hasn't
            // landed -- but an unrelated tripped flag on the low sensor has flipped.
            advanceTimeBy(2_000)
            manager.observedGaugeStatus(
                gaugeStatus(
                    maxSequenceNumber = 1u,
                    highLowAlarmStatus = HighLowAlarmStatus.DEFAULT.copy(
                        lowStatus = HighLowAlarmStatus.DEFAULT.lowStatus.copy(tripped = true),
                    ),
                ),
            )
            runCurrent()

            assertNull(result)
        }

    @Test
    fun `setHighLowAlarmStatus does not complete from a status that still matches the starting value`() =
        runTest {
            val manager = manager(backgroundScope)
            val commandedStatus = HighLowAlarmStatus.DEFAULT.copy(
                highStatus = HighLowAlarmStatus.DEFAULT.highStatus.copy(set = true),
            )

            var result: Boolean? = null
            manager.setHighLowAlarmStatus(commandedStatus) { result = it }
            runCurrent()

            // Starting value was the default -- a status still showing the default hasn't changed
            // anything yet, so this must not complete the command.
            advanceTimeBy(2_000)
            manager.observedGaugeStatus(gaugeStatus(maxSequenceNumber = 1u))
            runCurrent()

            assertNull(result)
        }
}
