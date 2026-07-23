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

import android.os.SystemClock
import android.util.Log
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngineManagerTest {

    private var elapsedRealtime = 0L

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        elapsedRealtime = 0L
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { elapsedRealtime }
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        unmockkStatic(SystemClock::class)
    }

    private fun advanceTimeBy(millis: Long) {
        elapsedRealtime += millis
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
        controlTemperature = if (controlDeviceConnected) SensorTemperature.NO_DATA else null,
        controlDeviceType = controlDeviceType.takeIf { controlDeviceConnected },
        controlSerialNumber = controlSerialNumber.takeIf { controlDeviceConnected },
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
    fun `control device disconnect is not confirmed before the delay elapses`() = runTest {
        val manager = manager(backgroundScope)

        manager.observedEngineStatus(
            engineStatus(
                maxSequenceNumber = 1u,
                controlDeviceConnected = true,
                controlDeviceType = CombustionProductType.PROBE,
                controlSerialNumber = "1111",
            ),
        )
        assertEquals("1111", manager.device.controlSerialNumber)

        // Firmware reports disconnected, but not for long enough yet to confirm.
        manager.observedEngineStatus(engineStatus(maxSequenceNumber = 2u))
        advanceTimeBy(29_999L)
        manager.observedEngineStatus(engineStatus(maxSequenceNumber = 3u))

        assertEquals("1111", manager.device.controlSerialNumber)
        assertTrue(manager.device.engineStatusFlags.controlDeviceConnected)
    }

    @Test
    fun `control device disconnect is confirmed once the delay elapses`() = runTest {
        val manager = manager(backgroundScope)

        manager.observedEngineStatus(
            engineStatus(
                maxSequenceNumber = 1u,
                controlDeviceConnected = true,
                controlDeviceType = CombustionProductType.PROBE,
                controlSerialNumber = "1111",
            ),
        )
        manager.observedEngineStatus(engineStatus(maxSequenceNumber = 2u))

        advanceTimeBy(30_000L)
        manager.observedEngineStatus(engineStatus(maxSequenceNumber = 3u))

        assertNull(manager.device.controlSerialNumber)
        assertNull(manager.device.controlDeviceType)
        assertFalse(manager.device.engineStatusFlags.controlDeviceConnected)
    }

    @Test
    fun `reconnecting cancels the pending disconnect and starts a fresh window`() = runTest {
        val manager = manager(backgroundScope)

        manager.observedEngineStatus(
            engineStatus(
                maxSequenceNumber = 1u,
                controlDeviceConnected = true,
                controlDeviceType = CombustionProductType.PROBE,
                controlSerialNumber = "1111",
            ),
        )
        manager.observedEngineStatus(engineStatus(maxSequenceNumber = 2u))
        advanceTimeBy(10_000L)

        // A different control device takes over mid-window -- adopted immediately.
        manager.observedEngineStatus(
            engineStatus(
                maxSequenceNumber = 3u,
                controlDeviceConnected = true,
                controlDeviceType = CombustionProductType.PROBE,
                controlSerialNumber = "2222",
            ),
        )
        assertEquals("2222", manager.device.controlSerialNumber)

        // It disconnects again -- the old timer must not still be running underneath.
        manager.observedEngineStatus(engineStatus(maxSequenceNumber = 4u))
        advanceTimeBy(10_000L)
        manager.observedEngineStatus(engineStatus(maxSequenceNumber = 5u))

        assertEquals("2222", manager.device.controlSerialNumber)

        advanceTimeBy(20_000L)
        manager.observedEngineStatus(engineStatus(maxSequenceNumber = 6u))

        assertNull(manager.device.controlSerialNumber)
    }

    @Test
    fun `cooking session change clears the control device immediately`() = runTest {
        val manager = manager(backgroundScope)

        manager.observedEngineStatus(
            engineStatus(
                sessionID = 1u,
                maxSequenceNumber = 1u,
                controlDeviceConnected = true,
                controlDeviceType = CombustionProductType.PROBE,
                controlSerialNumber = "1111",
            ),
        )
        manager.observedEngineStatus(engineStatus(sessionID = 1u, maxSequenceNumber = 2u))
        advanceTimeBy(5_000L)

        // New cooking session starts before the disconnect delay elapses. Sequence number must
        // still increase for the arbitrator to accept it (see NodeHybridDataLinkArbitrator --
        // its own "session changed" fast path compares two already-stale snapshots and never
        // actually fires on a real transition, so acceptance falls through to the sequence-number
        // check regardless of the session change).
        manager.observedEngineStatus(engineStatus(sessionID = 2u, maxSequenceNumber = 3u))

        assertNull(manager.device.controlSerialNumber)
        assertFalse(manager.device.engineStatusFlags.controlDeviceConnected)
    }

    @Test
    fun `disconnected status with no prior controller does not start a pending window`() =
        runTest {
            val manager = manager(backgroundScope)

            manager.observedEngineStatus(engineStatus(maxSequenceNumber = 1u))

            assertNull(manager.device.controlSerialNumber)
            assertFalse(manager.device.engineStatusFlags.controlDeviceConnected)
        }
}
