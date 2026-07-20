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
import inc.combustion.framework.ble.scanning.ProbeAdvertisingData
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.DeviceManager
import inc.combustion.framework.service.OverheatingSensors
import inc.combustion.framework.service.PredictionStatus
import inc.combustion.framework.service.ProbeBatteryStatus
import inc.combustion.framework.service.ProbeColor
import inc.combustion.framework.service.ProbeID
import inc.combustion.framework.service.ProbeMode
import inc.combustion.framework.service.ProbeTemperatures
import inc.combustion.framework.service.ProbeVirtualSensors
import inc.combustion.framework.service.ThermometerPreferences
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
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
    }

    @After
    fun tearDown() {
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

    private fun status(): ProbeStatus {
        val probeTemperatures = ProbeTemperatures.withRandomData()
        return ProbeStatus(
            minSequenceNumber = 0u,
            maxSequenceNumber = 1u,
            temperatures = probeTemperatures,
            id = ProbeID.ID1,
            color = ProbeColor.COLOR1,
            mode = ProbeMode.NORMAL,
            batteryStatus = ProbeBatteryStatus.OK,
            virtualSensors = ProbeVirtualSensors.DEFAULT,
            predictionStatus = PredictionStatus.withRandomData(),
            foodSafeData = null,
            foodSafeStatus = null,
            overheatingSensors = OverheatingSensors.fromTemperatures(probeTemperatures),
            thermometerPrefs = ThermometerPreferences.DEFAULT,
            probeHighLowAlarmStatus = null,
        )
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
}
