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
    }

    @After
    fun tearDown() {
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
}
