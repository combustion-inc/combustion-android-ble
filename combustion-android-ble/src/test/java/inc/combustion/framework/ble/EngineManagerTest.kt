/*
 * Project: Combustion Inc. Android Framework
 * File: EngineManagerTest.kt
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
import inc.combustion.framework.ble.device.SimulatedEngineBleDevice
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.DeviceManager
import inc.combustion.framework.service.EngineBatteryStatus
import inc.combustion.framework.service.EngineChargingFault
import inc.combustion.framework.service.EngineControllerFlags
import inc.combustion.framework.service.EngineControllerState
import inc.combustion.framework.service.EngineControllerStatus
import inc.combustion.framework.service.EngineFanState
import inc.combustion.framework.service.EngineFanStatus
import inc.combustion.framework.service.EngineStatusFlags
import inc.combustion.framework.service.HopCount
import inc.combustion.framework.service.SensorTemperature
import inc.combustion.framework.service.SessionInformation
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `controlDeviceConnected` disconnect-confirmation ("debounce") logic used to live here. This
 * framework layer is BLE-only and has no visibility into cloud-relayed engine status (from other
 * users' phones/node devices), so a debounce implemented here could only ever protect a viewer
 * with their own direct BLE link -- it was removed in favor of a recommendation that consuming
 * applications implement this themselves, where they have visibility into every data source they
 * display. This layer now just reports `engineStatusFlags` exactly as firmware sent it, same as
 * iOS's framework layer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineManagerTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        // setControlDevice (and SimulatedEngineBleDevice's own completion callback) dispatch onto
        // Dispatchers.Main -- tie it to the test's virtual-time scheduler so it advances
        // deterministically along with advanceTimeBy/runCurrent below.
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    private fun manager(scope: CoroutineScope): EngineManager = EngineManager(
        mac = "AA:BB:CC:DD:EE:FF",
        serialNumber = "12345678",
        initialEngineAdvertisingData = null,
        scope = scope,
        settings = DeviceManager.Settings(),
        dfuDisconnectedNodeCallback = {},
    )

    private fun engineStatus(
        sessionID: UInt = 1u,
        maxSequenceNumber: UInt = 1u,
        controlDeviceConnected: Boolean = false,
        controlDeviceType: CombustionProductType? = null,
        controlSerialNumber: String? = null,
    ): EngineStatus = EngineStatus(
        sessionInformation = SessionInformation(sessionID = sessionID, samplePeriod = 1u),
        samplePeriod = 1u,
        minSequenceNumber = 0u,
        maxSequenceNumber = maxSequenceNumber,
        engineBatteryStatus = EngineBatteryStatus(),
        temperatureSetPoint = SensorTemperature.NO_DATA,
        // Parsing no longer gates these on controlDeviceConnected -- firmware reports them
        // independently of the connected flag (see EngineStatus.fromRawData), so the fixture
        // shouldn't null them out here either.
        controlTemperature = if (controlDeviceType != null) SensorTemperature.NO_DATA else null,
        controlDeviceType = controlDeviceType,
        controlSerialNumber = controlSerialNumber,
        engineStatusFlags = EngineStatusFlags(controlDeviceConnected = controlDeviceConnected),
        engineFanStatus = EngineFanStatus(
            fanState = EngineFanState.FAN_ON,
            dutyCycle = 50u,
            commandedSpeed = 50u,
            measuredSpeed = 50u,
            fanOffTimeMs = 0u,
            fanOnTimeMs = 0u,
        ),
        engineControllerStatus = EngineControllerStatus(
            state = EngineControllerState.OBSERVE,
            responseCoefficient = 1.0f,
            cyclesCompleted = 0u,
            flags = EngineControllerFlags(),
            smoothedTemperatureCelsius = 25.0f,
            timeToPeakSeconds = 0u,
            driftRateCelsiusPerSecond = 0.0f,
        ),
        hopCount = HopCount.HOP1,
        knobVoltageMillivolts = 0u,
        knobAngleTenthsDegrees = 0u,
        chargingFault = EngineChargingFault.NONE,
    )

    @Test
    fun `controlDeviceConnected is reported exactly as firmware sent it, with no debounce`() =
        runTest {
            val manager = manager(backgroundScope)

            manager.observedEngineStatus(
                engineStatus(
                    maxSequenceNumber = 1u,
                    controlDeviceConnected = true,
                    controlDeviceType = CombustionProductType.PROBE,
                    controlSerialNumber = "1111",
                ),
            )
            assertTrue(manager.device.engineStatusFlags.controlDeviceConnected)

            // Firmware reports disconnected on the very next status -- reported immediately, no
            // confirmation delay at this layer.
            manager.observedEngineStatus(
                engineStatus(
                    maxSequenceNumber = 2u,
                    controlDeviceType = CombustionProductType.PROBE,
                    controlSerialNumber = "1111",
                ),
            )
            assertFalse(manager.device.engineStatusFlags.controlDeviceConnected)
        }

    @Test
    fun `controlSerialNumber and controlDeviceType are tracked independently of controlDeviceConnected`() =
        runTest {
            val manager = manager(backgroundScope)

            manager.observedEngineStatus(
                engineStatus(
                    maxSequenceNumber = 1u,
                    controlDeviceType = CombustionProductType.PROBE,
                    controlSerialNumber = "1000D564",
                ),
            )

            assertEquals("1000D564", manager.device.controlSerialNumber)
            assertEquals(CombustionProductType.PROBE, manager.device.controlDeviceType)
            assertFalse(manager.device.engineStatusFlags.controlDeviceConnected)
        }

    @Test
    fun `no status received yet reports a fresh Engine with no controller`() = runTest {
        val manager = manager(backgroundScope)

        assertNull(manager.device.controlSerialNumber)
        assertFalse(manager.device.hasReceivedStatus)
    }

    // setControlDevice -- retries via CommandCoordinator.

    @Test
    fun `setControlDevice succeeds via a simulated device response, without needing a retry`() =
        runTest {
            val manager = manager(backgroundScope)
            manager.addSimulatedEngine(
                SimulatedEngineBleDevice(scope = backgroundScope, serialNumber = "12345678"),
            )

            var result: Boolean? = null
            manager.setControlDevice(CombustionProductType.PROBE, "1111") { result = it }

            // SimulatedEngineBleDevice.sendSetControlDevice responds after a fixed 100ms delay.
            advanceTimeBy(101)
            runCurrent()

            assertEquals(true, result)
        }

    @Test
    fun `setControlDevice retries roughly every 5s and fails at the 30s overall timeout when nothing is connected`() =
        runTest {
            val manager = manager(backgroundScope)

            var result: Boolean? = null
            manager.setControlDevice(CombustionProductType.PROBE, "1111") { result = it }

            // Nothing connected (no simulated device, no arbitrator links) -- send() can never
            // register an attempt key, so this can only resolve via the overall 30s timeout, not
            // a response. Confirms it doesn't fail immediately (i.e. it's genuinely retrying/
            // waiting the full window), only once virtual time actually reaches ~30s.
            advanceTimeBy(29_000)
            runCurrent()
            assertNull(result)

            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(false, result)
        }

    @Test
    fun `setControlDevice completes early from a status update confirming the commanded value, with nothing connected to respond`() =
        runTest {
            val manager = manager(backgroundScope)

            var result: Boolean? = null
            manager.setControlDevice(CombustionProductType.PROBE, "1111") { result = it }
            runCurrent()

            // No response is possible (nothing connected) -- but a status arrives, well before the
            // 30s timeout, already showing the commanded value took effect.
            advanceTimeBy(2_000)
            manager.observedEngineStatus(
                engineStatus(
                    maxSequenceNumber = 1u,
                    controlDeviceType = CombustionProductType.PROBE,
                    controlSerialNumber = "1111",
                ),
            )
            runCurrent()

            assertEquals(true, result)
        }

    @Test
    fun `setControlDevice does not complete from a status showing a different value when the starting value was never confirmed`() =
        runTest {
            // Regression test: no status has ever been received, so the starting value is
            // unknown (Absent), not a confirmed null. A status arriving with some other value
            // ("2222", neither the unknown starting value nor the commanded "1111") must not be
            // treated as "a competing command already changed it" -- there's no confirmed prior
            // value for it to have changed *from*. See CommandCoordinator.valueConfirmation's KDoc.
            val manager = manager(backgroundScope)

            var result: Boolean? = null
            manager.setControlDevice(CombustionProductType.PROBE, "1111") { result = it }
            runCurrent()

            advanceTimeBy(2_000)
            manager.observedEngineStatus(
                engineStatus(
                    maxSequenceNumber = 1u,
                    controlDeviceType = CombustionProductType.PROBE,
                    controlSerialNumber = "2222",
                ),
            )
            runCurrent()

            assertNull(result)
        }

    @Test
    fun `setControlDevice completes early when a status shows a different value than a confirmed starting controller, instead of bouncing against a competing command`() =
        runTest {
            val manager = manager(backgroundScope)

            // Establish a confirmed starting value ("3333") via a real status before the command
            // is even issued.
            manager.observedEngineStatus(
                engineStatus(
                    maxSequenceNumber = 1u,
                    controlDeviceType = CombustionProductType.PROBE,
                    controlSerialNumber = "3333",
                ),
            )

            var result: Boolean? = null
            manager.setControlDevice(CombustionProductType.PROBE, "1111") { result = it }
            runCurrent()

            // Neither the confirmed starting value ("3333") nor the commanded "1111" -- some other
            // actor already set it to "2222". Completes as success rather than retrying against
            // that competing command until timeout.
            advanceTimeBy(2_000)
            manager.observedEngineStatus(
                engineStatus(
                    maxSequenceNumber = 2u,
                    controlDeviceType = CombustionProductType.PROBE,
                    controlSerialNumber = "2222",
                ),
            )
            runCurrent()

            assertEquals(true, result)
        }

    @Test
    fun `setControlDevice does not complete from a status that still matches the starting value`() =
        runTest {
            val manager = manager(backgroundScope)

            var result: Boolean? = null
            manager.setControlDevice(CombustionProductType.PROBE, "1111") { result = it }
            runCurrent()

            // Starting value was null (no prior controller) -- a status still showing null hasn't
            // changed anything yet, so this must not complete the command.
            advanceTimeBy(2_000)
            manager.observedEngineStatus(engineStatus(maxSequenceNumber = 1u))
            runCurrent()

            assertNull(result)
        }

    @Test
    fun `setControlDevice confirms clearing the controller from a status showing a confirmed null, even with an unknown starting value`() =
        runTest {
            // commandedValue is null when controlSerialNumber is empty (clearing the controller).
            // A status confirming controlSerialNumber == null is a legitimate Present(null)
            // observation that exactly matches the null commandedValue -- distinct from the
            // Absent case above, where no field was observed at all.
            val manager = manager(backgroundScope)

            var result: Boolean? = null
            manager.setControlDevice(CombustionProductType.PROBE, "") { result = it }
            runCurrent()

            advanceTimeBy(2_000)
            manager.observedEngineStatus(engineStatus(maxSequenceNumber = 1u))
            runCurrent()

            assertEquals(true, result)
        }

    @Test
    fun `cancelling the caller's coroutine cleans up without invoking completionHandler`() =
        runTest {
            val manager = manager(backgroundScope)

            var result: Boolean? = null
            val job = launch {
                manager.setControlDevice(CombustionProductType.PROBE, "1111") { result = it }
            }
            runCurrent()

            job.cancel()
            runCurrent()

            // Ordinary structured-concurrency cancellation -- CommandCoordinator's finally block
            // cleans up registered attempt keys, but completionHandler (downstream of the
            // cancelled coroutine) never runs. There's no separate explicit-cancel API in this
            // phase; this is the cancellation path that exists today.
            assertNull(result)
        }

    // setTemperatureSetPoint -- retries via CommandCoordinator.

    @Test
    fun `setTemperatureSetPoint succeeds via a simulated device response, without needing a retry`() =
        runTest {
            val manager = manager(backgroundScope)
            manager.addSimulatedEngine(
                SimulatedEngineBleDevice(scope = backgroundScope, serialNumber = "12345678"),
            )

            var result: Boolean? = null
            manager.setTemperatureSetPoint(SensorTemperature(60.0)) { result = it }

            // SimulatedEngineBleDevice.sendSetTemperatureSetPoint responds after a fixed 100ms delay.
            advanceTimeBy(101)
            runCurrent()

            assertEquals(true, result)
        }

    @Test
    fun `setTemperatureSetPoint completes early from a status update confirming the commanded value, with nothing connected to respond`() =
        runTest {
            val manager = manager(backgroundScope)

            var result: Boolean? = null
            manager.setTemperatureSetPoint(SensorTemperature(60.0)) { result = it }
            runCurrent()

            advanceTimeBy(2_000)
            manager.observedEngineStatus(
                engineStatus(maxSequenceNumber = 1u).copy(temperatureSetPoint = SensorTemperature(60.0)),
            )
            runCurrent()

            assertEquals(true, result)
        }

    @Test
    fun `setTemperatureSetPoint completes early when a status shows a different value, instead of bouncing against a competing command`() =
        runTest {
            val manager = manager(backgroundScope)

            var result: Boolean? = null
            manager.setTemperatureSetPoint(SensorTemperature(60.0)) { result = it }
            runCurrent()

            // Neither the starting value (NO_DATA) nor the commanded 60.0 -- some other actor
            // already set it to 55.0. Still completes as success rather than retrying against
            // that competing command until timeout.
            advanceTimeBy(2_000)
            manager.observedEngineStatus(
                engineStatus(maxSequenceNumber = 1u).copy(temperatureSetPoint = SensorTemperature(55.0)),
            )
            runCurrent()

            assertEquals(true, result)
        }

    @Test
    fun `setTemperatureSetPoint does not complete from a status that still matches the starting value`() =
        runTest {
            val manager = manager(backgroundScope)

            var result: Boolean? = null
            manager.setTemperatureSetPoint(SensorTemperature(60.0)) { result = it }
            runCurrent()

            // Starting value was NO_DATA -- a status still showing NO_DATA hasn't changed
            // anything yet, so this must not complete the command.
            advanceTimeBy(2_000)
            manager.observedEngineStatus(engineStatus(maxSequenceNumber = 1u))
            runCurrent()

            assertNull(result)
        }
}
