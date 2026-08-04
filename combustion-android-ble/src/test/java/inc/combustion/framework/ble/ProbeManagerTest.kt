/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeManagerTest.kt
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
import inc.combustion.framework.ble.device.DeviceInformationBleDevice
import inc.combustion.framework.ble.device.ProbeBleDevice
import inc.combustion.framework.ble.device.SimulatedProbeBleDevice
import inc.combustion.framework.ble.scanning.ProbeAdvertisingData
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.DeviceManager
import inc.combustion.framework.service.FoodSafeData
import inc.combustion.framework.service.HighLowAlarmStatus
import inc.combustion.framework.service.OverheatingSensors
import inc.combustion.framework.service.PredictionStatus
import inc.combustion.framework.service.ProbeBatteryStatus
import inc.combustion.framework.service.ProbeColor
import inc.combustion.framework.service.ProbeHighLowAlarmStatus
import inc.combustion.framework.service.ProbeID
import inc.combustion.framework.service.ProbeMode
import inc.combustion.framework.service.ProbePowerMode
import inc.combustion.framework.service.ProbePredictionMode
import inc.combustion.framework.service.ProbeTemperatures
import inc.combustion.framework.service.ProbeVirtualSensors
import inc.combustion.framework.service.ThermometerPreferences
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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

@OptIn(ExperimentalCoroutinesApi::class)
class ProbeManagerTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0

        // setPowerMode/setProbeHighLowAlarmStatus dispatch their completionHandler onto
        // Dispatchers.Main -- tie it to the test's virtual-time scheduler.
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    private fun advertisement(
        thermometerPreferences: ThermometerPreferences? = ThermometerPreferences.DEFAULT,
    ): ProbeAdvertisingData {
        val probeTemperatures = ProbeTemperatures.withRandomData()
        return ProbeAdvertisingData(
            mac = "AA:BB:CC:DD:EE:FF",
            name = "Probe",
            rssi = -50,
            productType = CombustionProductType.PROBE,
            isConnectable = true,
            serialNumber = "12345678",
            probeTemperatures = probeTemperatures,
            probeID = ProbeID.ID1,
            color = ProbeColor.COLOR1,
            mode = ProbeMode.NORMAL,
            batteryStatus = ProbeBatteryStatus.OK,
            virtualSensors = ProbeVirtualSensors.DEFAULT,
            overheatingSensors = OverheatingSensors.fromTemperatures(probeTemperatures),
            hopCount = 0u,
            thermometerPreferences = thermometerPreferences,
        )
    }

    private fun status(
        maxSequenceNumber: UInt = 1u,
        predictionStatus: PredictionStatus = PredictionStatus.withRandomData(),
        foodSafeData: FoodSafeData? = null,
        probeHighLowAlarmStatus: ProbeHighLowAlarmStatus? = null,
    ): ProbeStatus {
        val probeTemperatures = ProbeTemperatures.withRandomData()
        return ProbeStatus(
            minSequenceNumber = 0u,
            maxSequenceNumber = maxSequenceNumber,
            temperatures = probeTemperatures,
            id = ProbeID.ID1,
            color = ProbeColor.COLOR1,
            mode = ProbeMode.NORMAL,
            batteryStatus = ProbeBatteryStatus.OK,
            virtualSensors = ProbeVirtualSensors.DEFAULT,
            predictionStatus = predictionStatus,
            foodSafeData = foodSafeData,
            foodSafeStatus = null,
            overheatingSensors = OverheatingSensors.fromTemperatures(probeTemperatures),
            thermometerPrefs = ThermometerPreferences.DEFAULT,
            probeHighLowAlarmStatus = probeHighLowAlarmStatus,
        )
    }

    // Delivers a real ProbeStatus through the same path production code uses
    // (ProbeBleDeviceBase.observeProbeStatusUpdates -> ProbeManager.handleProbeStatus), which is
    // what actually calls CommandCoordinator.confirmCommandStatus -- addSimulatedProbe's status
    // wiring is a separate, simpler path that does NOT call confirmCommandStatus, so it can't be
    // used to test isConfirmed/valueConfirmation behavior. Mirrors the existing
    // `hasReceivedStatus becomes true only once a real status message is handled` test's pattern.
    private fun probeManagerWithMockedProbe(
        scope: CoroutineScope,
        commandCoordinator: CommandCoordinator =
            CommandCoordinator(requestTimeoutMs = 10_000, retryIntervalMs = 5_000),
    ): Pair<ProbeManager, suspend (ProbeStatus) -> Unit> {
        val manager = ProbeManager(
            serialNumber = "12345678",
            scope = scope,
            settings = DeviceManager.Settings(),
            dfuDisconnectedNodeCallback = {},
            commandCoordinator = commandCoordinator,
        )

        val probe = mockk<ProbeBleDevice>(relaxed = true)
        val baseDevice = mockk<DeviceInformationBleDevice>(relaxed = true)
        val statusCallback = slot<suspend (ProbeStatus, UInt?) -> Unit>()
        every { probe.observeProbeStatusUpdates(any(), capture(statusCallback)) } returns Unit

        manager.addProbe(probe, baseDevice, advertisement())

        val deliverStatus: suspend (ProbeStatus) -> Unit = { status -> statusCallback.captured(status, null) }
        return manager to deliverStatus
    }

    @Test
    fun `hasReceivedStatus stays false after an advertisement carrying thermometer preferences`() =
        runTest {
            val manager = ProbeManager(
                serialNumber = "12345678",
                scope = backgroundScope,
                settings = DeviceManager.Settings(),
                dfuDisconnectedNodeCallback = {},
            )

            val probe = mockk<ProbeBleDevice>(relaxed = true)
            val baseDevice = mockk<DeviceInformationBleDevice>(relaxed = true)

            // Advertisement carries a non-null thermometerPreferences, the same shape that
            // previously leaked into Probe.thermometerPrefs and flipped hasReceivedStatus early.
            manager.addProbe(probe, baseDevice, advertisement())

            assertFalse(manager.device.hasReceivedStatus)
        }

    @Test
    fun `hasReceivedStatus becomes true only once a real status message is handled`() = runTest {
        val manager = ProbeManager(
            serialNumber = "12345678",
            scope = backgroundScope,
            settings = DeviceManager.Settings(),
            dfuDisconnectedNodeCallback = {},
        )

        val probe = mockk<ProbeBleDevice>(relaxed = true)
        val baseDevice = mockk<DeviceInformationBleDevice>(relaxed = true)
        val statusCallback = slot<suspend (ProbeStatus, UInt?) -> Unit>()
        every { probe.observeProbeStatusUpdates(any(), capture(statusCallback)) } returns Unit

        manager.addProbe(probe, baseDevice, advertisement())
        assertFalse(manager.device.hasReceivedStatus)

        statusCallback.captured(status(), null)

        assertTrue(manager.device.hasReceivedStatus)
    }

    // setPowerMode / setProbeHighLowAlarmStatus -- retries via CommandCoordinator.

    @Test
    fun `setPowerMode succeeds via a simulated device response`() = runTest {
        val manager = ProbeManager(
            serialNumber = "12345678",
            scope = backgroundScope,
            settings = DeviceManager.Settings(),
            dfuDisconnectedNodeCallback = {},
        )
        manager.addSimulatedProbe(
            SimulatedProbeBleDevice(scope = backgroundScope, serialNumber = "12345678"),
        )

        var result: Boolean? = null
        manager.setPowerMode(ProbePowerMode.ALWAYS_ON) { result = it }
        runCurrent()

        assertEquals(true, result)
    }

    @Test
    fun `setProbeHighLowAlarmStatus succeeds via a simulated device response`() = runTest {
        val manager = ProbeManager(
            serialNumber = "12345678",
            scope = backgroundScope,
            settings = DeviceManager.Settings(),
            dfuDisconnectedNodeCallback = {},
        )
        manager.addSimulatedProbe(
            SimulatedProbeBleDevice(scope = backgroundScope, serialNumber = "12345678"),
        )

        var result: Boolean? = null
        manager.setProbeHighLowAlarmStatus(ProbeHighLowAlarmStatus()) {
            result = it
        }
        runCurrent()

        assertEquals(true, result)
    }

    // setProbeColor / setProbeID / setPrediction -- retries via CommandCoordinator, once their
    // blocking reqId-matching bug (see ProbeManager.setPowerMode's KDoc) was fixed.

    @Test
    fun `setProbeColor succeeds via a simulated device response`() = runTest {
        val manager = ProbeManager(
            serialNumber = "12345678",
            scope = backgroundScope,
            settings = DeviceManager.Settings(),
            dfuDisconnectedNodeCallback = {},
        )
        manager.addSimulatedProbe(
            SimulatedProbeBleDevice(scope = backgroundScope, serialNumber = "12345678"),
        )

        var result: Boolean? = null
        manager.setProbeColor(ProbeColor.COLOR2) { result = it }
        runCurrent()

        assertEquals(true, result)
    }

    @Test
    fun `setProbeID succeeds via a simulated device response`() = runTest {
        val manager = ProbeManager(
            serialNumber = "12345678",
            scope = backgroundScope,
            settings = DeviceManager.Settings(),
            dfuDisconnectedNodeCallback = {},
        )
        manager.addSimulatedProbe(
            SimulatedProbeBleDevice(scope = backgroundScope, serialNumber = "12345678"),
        )

        var result: Boolean? = null
        manager.setProbeID(ProbeID.ID2) { result = it }
        runCurrent()

        assertEquals(true, result)
    }

    @Test
    fun `setPrediction succeeds via a simulated device response`() = runTest {
        val manager = ProbeManager(
            serialNumber = "12345678",
            scope = backgroundScope,
            settings = DeviceManager.Settings(),
            dfuDisconnectedNodeCallback = {},
        )
        manager.addSimulatedProbe(
            SimulatedProbeBleDevice(scope = backgroundScope, serialNumber = "12345678"),
        )

        var result: Boolean? = null
        manager.setPrediction(60.0, ProbePredictionMode.TIME_TO_REMOVAL) { result = it }
        runCurrent()

        assertEquals(true, result)
    }

    @Test
    fun `configureFoodSafe succeeds via a simulated device response`() = runTest {
        val manager = ProbeManager(
            serialNumber = "12345678",
            scope = backgroundScope,
            settings = DeviceManager.Settings(),
            dfuDisconnectedNodeCallback = {},
        )
        manager.addSimulatedProbe(
            SimulatedProbeBleDevice(scope = backgroundScope, serialNumber = "12345678"),
        )

        var result: Boolean? = null
        manager.configureFoodSafe(
            FoodSafeData.Simplified(
                product = FoodSafeData.Simplified.Product.BeefCuts,
                serving = FoodSafeData.Serving.Immediately,
            ),
        ) { result = it }
        runCurrent()

        assertEquals(true, result)
    }

    // setPrediction confirmation via a status update. Regression coverage for a bug found in
    // review: startingPrediction is built as `predictionMode to setPointTemperatureCelsius`, and
    // a Pair is never itself null -- gating confirmation on "the Pair is null" (rather than its
    // components) never actually detects an unconfirmed starting value. See
    // ProbeManager.setPrediction and CommandCoordinator.valueConfirmation's KDocs.

    @Test
    fun `setPrediction does not confirm from an unrelated status when the starting prediction was never confirmed`() =
        runTest {
            val (manager, deliverStatus) = probeManagerWithMockedProbe(backgroundScope)

            var result: Boolean? = null
            manager.setPrediction(60.0, ProbePredictionMode.TIME_TO_REMOVAL) { result = it }
            runCurrent()

            // Neither the (never-confirmed) starting prediction nor the commanded one -- must not
            // be mistaken for "a competing command already changed it."
            deliverStatus(
                status(
                    predictionStatus = PredictionStatus.withRandomData().copy(
                        predictionMode = ProbePredictionMode.REMOVAL_AND_RESTING,
                        setPointTemperature = 45.0,
                    ),
                ),
            )
            runCurrent()

            assertNull(result)
        }

    @Test
    fun `setPrediction confirms an exact match even when the starting prediction was never confirmed`() =
        runTest {
            val (manager, deliverStatus) = probeManagerWithMockedProbe(backgroundScope)

            var result: Boolean? = null
            manager.setPrediction(60.0, ProbePredictionMode.TIME_TO_REMOVAL) { result = it }
            runCurrent()

            deliverStatus(
                status(
                    predictionStatus = PredictionStatus.withRandomData().copy(
                        predictionMode = ProbePredictionMode.TIME_TO_REMOVAL,
                        setPointTemperature = 60.0,
                    ),
                ),
            )
            runCurrent()

            assertEquals(true, result)
        }

    @Test
    fun `setPrediction confirms when a status shows a value different from a confirmed starting prediction, instead of bouncing against a competing command`() =
        runTest {
            val (manager, deliverStatus) = probeManagerWithMockedProbe(backgroundScope)

            // Establish a confirmed starting prediction first.
            deliverStatus(
                status(
                    maxSequenceNumber = 1u,
                    predictionStatus = PredictionStatus.withRandomData().copy(
                        predictionMode = ProbePredictionMode.REMOVAL_AND_RESTING,
                        setPointTemperature = 40.0,
                    ),
                ),
            )
            runCurrent()

            var result: Boolean? = null
            manager.setPrediction(60.0, ProbePredictionMode.TIME_TO_REMOVAL) { result = it }
            runCurrent()

            // Neither the confirmed starting prediction (REMOVAL_AND_RESTING/40.0) nor the
            // commanded one (TIME_TO_REMOVAL/60.0) -- some other actor already set NONE/50.0.
            deliverStatus(
                status(
                    maxSequenceNumber = 2u,
                    predictionStatus = PredictionStatus.withRandomData().copy(
                        predictionMode = ProbePredictionMode.NONE,
                        setPointTemperature = 50.0,
                    ),
                ),
            )
            runCurrent()

            assertEquals(true, result)
        }

    // configureFoodSafe / setProbeHighLowAlarmStatus confirmation via a status update. Regression
    // coverage for the structural-absence bug found in review: ProbeStatus.foodSafeData and
    // .probeHighLowAlarmStatus are null both when the field genuinely was never included in any
    // status format the device sends, and when a specific packet just didn't carry those trailing
    // bytes (older firmware/truncated relay, see ProbeStatus.fromRawData) -- there's no legitimate
    // "confirmed empty" case for either field, so a null observation must always be treated as
    // absent, never as a confirmed value equal to (or different from) the previous one.

    @Test
    fun `configureFoodSafe does not confirm from a status whose packet omits food safe data, even after a previously confirmed value`() =
        runTest {
            val (manager, deliverStatus) = probeManagerWithMockedProbe(backgroundScope)
            val startingData = FoodSafeData.Simplified(
                product = FoodSafeData.Simplified.Product.BeefCuts,
                serving = FoodSafeData.Serving.Immediately,
            )
            val commandedData = FoodSafeData.Simplified(
                product = FoodSafeData.Simplified.Product.PorkCuts,
                serving = FoodSafeData.Serving.Immediately,
            )

            // Establish a confirmed starting value.
            deliverStatus(status(maxSequenceNumber = 1u, foodSafeData = startingData))
            runCurrent()

            var result: Boolean? = null
            manager.configureFoodSafe(commandedData) { result = it }
            runCurrent()

            // A later status arrives from a route/firmware that doesn't include the food-safe
            // bytes at all -- must not be mistaken for "the value changed to something else."
            deliverStatus(status(maxSequenceNumber = 2u, foodSafeData = null))
            runCurrent()

            assertNull(result)
        }

    @Test
    fun `configureFoodSafe confirms from a status showing the exact commanded food safe data`() =
        runTest {
            val (manager, deliverStatus) = probeManagerWithMockedProbe(backgroundScope)
            val commandedData = FoodSafeData.Simplified(
                product = FoodSafeData.Simplified.Product.BeefCuts,
                serving = FoodSafeData.Serving.Immediately,
            )

            var result: Boolean? = null
            manager.configureFoodSafe(commandedData) { result = it }
            runCurrent()

            deliverStatus(status(maxSequenceNumber = 1u, foodSafeData = commandedData))
            runCurrent()

            assertEquals(true, result)
        }

    @Test
    fun `setProbeHighLowAlarmStatus does not confirm from a status whose packet omits alarm data, even after a previously confirmed value`() =
        runTest {
            val (manager, deliverStatus) = probeManagerWithMockedProbe(backgroundScope)
            val startingStatus = ProbeHighLowAlarmStatus(
                t1 = HighLowAlarmStatus(
                    highStatus = HighLowAlarmStatus.AlarmStatus(set = true),
                    lowStatus = HighLowAlarmStatus.AlarmStatus(),
                ),
            )
            val commandedStatus = ProbeHighLowAlarmStatus()

            deliverStatus(status(maxSequenceNumber = 1u, probeHighLowAlarmStatus = startingStatus))
            runCurrent()

            var result: Boolean? = null
            manager.setProbeHighLowAlarmStatus(commandedStatus) { result = it }
            runCurrent()

            deliverStatus(status(maxSequenceNumber = 2u, probeHighLowAlarmStatus = null))
            runCurrent()

            assertNull(result)
        }

    @Test
    fun `setProbeHighLowAlarmStatus confirms from a status showing the exact commanded alarm status`() =
        runTest {
            val (manager, deliverStatus) = probeManagerWithMockedProbe(backgroundScope)
            val commandedStatus = ProbeHighLowAlarmStatus()

            var result: Boolean? = null
            manager.setProbeHighLowAlarmStatus(commandedStatus) { result = it }
            runCurrent()

            deliverStatus(status(maxSequenceNumber = 1u, probeHighLowAlarmStatus = commandedStatus))
            runCurrent()

            assertEquals(true, result)
        }

    @Test
    fun `setPowerMode serializes two concurrent calls to the same probe rather than racing them`() =
        runTest {
            val manager = ProbeManager(
                serialNumber = "12345678",
                scope = backgroundScope,
                settings = DeviceManager.Settings(),
                dfuDisconnectedNodeCallback = {},
                commandCoordinator = CommandCoordinator(requestTimeoutMs = 10_000, retryIntervalMs = 5_000),
            )

            val probe = mockk<ProbeBleDevice>(relaxed = true)
            every { probe.isConnected } returns true
            every { probe.id } returns "probe-1"
            val baseDevice = mockk<DeviceInformationBleDevice>(relaxed = true)

            // Captures each attempt's callback instead of resolving it immediately, so the test
            // can control exactly when each in-flight command "responds".
            val callbacks = mutableListOf<(Boolean, Any?) -> Unit>()
            every { probe.sendSetPowerMode(any(), any(), any()) } answers {
                thirdArg<((Boolean, Any?) -> Unit)?>()?.let { callbacks.add(it) }
            }

            manager.addProbe(probe, baseDevice, advertisement())

            var result1: Boolean? = null
            var result2: Boolean? = null
            manager.setPowerMode(ProbePowerMode.ALWAYS_ON) { result1 = it }
            manager.setPowerMode(ProbePowerMode.NORMAL) { result2 = it }
            runCurrent()

            // Without the mutex, both calls' send() would race and register the same
            // CommandAttemptKey.Direct(SET_POWER_MODE, "probe-1") back to back. With it, the
            // second call's sendRoutedCommand doesn't even start until the first releases the
            // mutex -- so only one send should have gone out so far.
            assertEquals(1, callbacks.size)
            assertNull(result1)
            assertNull(result2)

            // Resolve the first command -- this should release the mutex and let the second
            // command's send() finally run.
            callbacks[0](true, null)
            runCurrent()

            assertEquals(true, result1)
            assertEquals(2, callbacks.size)
            assertNull(result2)

            callbacks[1](true, null)
            runCurrent()

            assertEquals(true, result2)
        }
}
