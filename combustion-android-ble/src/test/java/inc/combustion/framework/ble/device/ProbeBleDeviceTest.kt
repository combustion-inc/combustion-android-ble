/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeBleDeviceTest.kt
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

package inc.combustion.framework.ble.device

import android.bluetooth.BluetoothAdapter
import android.util.Log
import inc.combustion.framework.ble.getCRC16CCITT
import inc.combustion.framework.ble.scanning.ProbeAdvertisingData
import inc.combustion.framework.ble.uart.MessageType
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.OverheatingSensors
import inc.combustion.framework.service.ProbeBatteryStatus
import inc.combustion.framework.service.ProbeColor
import inc.combustion.framework.service.ProbeID
import inc.combustion.framework.service.ProbeMode
import inc.combustion.framework.service.ProbePowerMode
import inc.combustion.framework.service.ProbeTemperatures
import inc.combustion.framework.service.ProbeVirtualSensors
import inc.combustion.framework.service.ThermometerPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Verifies the exact wiring `ProbeManager.setPowerMode`'s KDoc depends on: [ProbeBleDevice]'s raw
 * UART response processing ([ProbeBleDevice] has no public hook to observe this except by
 * triggering a real response) always matches a response against a fixed null key, regardless of
 * what request ID was passed to the corresponding `send*` call. `sendSetPowerMode` is the
 * representative case -- it's the one call among the five direct-link commands that actually
 * threads a caller-supplied `reqId` through to `wait()` (the other four hardcode null internally),
 * so it's the only one where passing a non-null ID is even possible to demonstrate going wrong.
 *
 * [UartBleDevice] is mocked out entirely (it's `open` for exactly this purpose) so this can run
 * without any real Android Bluetooth/GATT stack -- `observeUartCharacteristic`'s callback is
 * captured and invoked directly with hand-built raw response bytes instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProbeBleDeviceTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    private fun advertisement(): ProbeAdvertisingData {
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
            thermometerPreferences = ThermometerPreferences.DEFAULT,
        )
    }

    /** Hand-builds a raw `SetPowerModeResponse` frame -- see `Response.fromData`'s parser. */
    private fun rawSetPowerModeResponse(success: Boolean): UByteArray {
        val messageType = MessageType.SET_POWER_MODE.value
        val payloadLength: UByte = 0u
        val successByte: UByte = if (success) 1u else 0u

        val crc = ubyteArrayOf(messageType, successByte, payloadLength).getCRC16CCITT()
        val crcLow = (crc.toUInt() and 0xFFu).toUByte()
        val crcHigh = ((crc.toUInt() shr 8) and 0xFFu).toUByte()

        return ubyteArrayOf(0xCAu, 0xFEu, crcLow, crcHigh, messageType, successByte, payloadLength)
    }

    private fun probeBleDevice(scope: CoroutineScope): Pair<ProbeBleDevice, suspend (UByteArray) -> Unit> {
        val uart = mockk<UartBleDevice>(relaxed = true)
        every { uart.scope } returns scope
        every { uart.mac } returns "AA:BB:CC:DD:EE:FF"
        every { uart.id } returns "AA:BB:CC:DD:EE:FF"

        var capturedCallback: (suspend (UByteArray) -> Unit)? = null
        coEvery { uart.observeUartCharacteristic(any()) } coAnswers {
            capturedCallback = firstArg()
        }

        val device = ProbeBleDevice(
            mac = "AA:BB:CC:DD:EE:FF",
            scope = scope,
            probeAdvertisingData = advertisement(),
            adapter = mockk<BluetoothAdapter>(relaxed = true),
            uart = uart,
        )

        // observeUartResponses() (called from ProbeBleDevice's init) launches asynchronously --
        // the caller must runCurrent() before the deliver function below is actually populated.
        val deliver: suspend (UByteArray) -> Unit = { data -> capturedCallback?.invoke(data) }
        return device to deliver
    }

    @Test
    fun `sendSetPowerMode with a null reqId completes when a response arrives, matching how ProbeManager calls it`() =
        runTest {
            val (device, deliverResponse) = probeBleDevice(backgroundScope)
            runCurrent()

            var result: Pair<Boolean, Any?>? = null
            device.sendSetPowerMode(ProbePowerMode.ALWAYS_ON, null) { success, data ->
                result = success to data
            }
            runCurrent()

            deliverResponse(rawSetPowerModeResponse(success = true))
            runCurrent()

            assertEquals(true, result?.first)
        }

    @Test
    fun `sendSetPowerMode with a non-null reqId never completes, because the response is always matched against null`() =
        runTest {
            val (device, deliverResponse) = probeBleDevice(backgroundScope)
            runCurrent()

            var result: Pair<Boolean, Any?>? = null
            device.sendSetPowerMode(ProbePowerMode.ALWAYS_ON, 42u) { success, data ->
                result = success to data
            }
            runCurrent()

            // ProbeBleDevice.processUartResponses() always calls
            // setPowerModeHandler.handled(response.success, null) -- a wait() registered under a
            // non-null key is never found, so this response is silently dropped. This is exactly
            // why ProbeManager.setPowerMode always passes reqId = null for the direct-link
            // attempt (see its KDoc) -- passing a real ID here, as a naive "give every attempt a
            // unique ID" fix would, breaks completion entirely rather than just risking a retry
            // collision.
            deliverResponse(rawSetPowerModeResponse(success = true))
            runCurrent()

            assertNull(result)
        }
}
