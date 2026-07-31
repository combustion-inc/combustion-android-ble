/*
 * Project: Combustion Inc. Android Framework
 * File: EngineStatusTest.kt
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

import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.EngineBatteryLevel
import inc.combustion.framework.service.EngineBatteryState
import inc.combustion.framework.service.EngineChargingFault
import inc.combustion.framework.service.EngineControllerFlags
import inc.combustion.framework.service.EngineControllerState
import inc.combustion.framework.service.EngineFanState
import inc.combustion.framework.service.HopCount
import inc.combustion.framework.service.SensorTemperature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineStatusTest {

    /**
     * Builds a full, valid [EngineStatus.RAW_SIZE]-byte payload with deterministic values in
     * every field, so individual tests can assert against known expectations without needing to
     * hand-roll the whole packet each time.
     *
     * @param controlDeviceConnected sets bit1 of the engine status flags byte.
     * @param populateControlDeviceBytes when true, populates the control-device type/serial
     * ranges per [controlDeviceType]/[controlSerialNumber]; when false, those ranges are left
     * zero. Defaults to [controlDeviceConnected] to model ordinary firmware behavior, but can be
     * set independently to model the case parsing must also support: firmware continuing to
     * report the last-known controller identity while [controlDeviceConnected] is transiently
     * false (see EngineStatus.fromRawData's control-device-type comment, and
     * EngineManager's disconnect-confirmation debounce, which depends on this).
     */
    private fun buildRawStatus(
        controlDeviceConnected: Boolean = true,
        populateControlDeviceBytes: Boolean = controlDeviceConnected,
        controlDeviceType: CombustionProductType = CombustionProductType.PROBE,
        controlSerialNumber: String = "1000DA4C",
    ): UByteArray {
        val raw = UByteArray(EngineStatus.RAW_SIZE)

        // Session ID (0..3) + sample period (4..5)
        raw.putLittleEndianUInt32At(0, 0xDEADBEEFu)
        raw.putLittleEndianUInt16At(4, 5000u)

        // Min/max sequence number
        raw.putLittleEndianUInt32At(6, 100u)
        raw.putLittleEndianUInt32At(10, 200u)

        // Battery status: level, state, voltage tenths
        raw[14] = EngineBatteryLevel.LOW_BATTERY.type
        raw[15] = EngineBatteryState.CHARGING.type
        raw[16] = 120u // 12.0V

        // Temperature set point (17..18) -- 100.0C via the "start" raw format
        SensorTemperature(100.0).toRawDataStart().copyInto(raw, 17)

        // Control temperature (19..20) -- 50.0C, only meaningful when a controller is connected
        SensorTemperature(50.0).toRawDataStart().copyInto(raw, 19)

        // Engine status flags (34): appMode + lidOpen, plus controlDeviceConnected per param
        var flags = 0b0000_0101 // appMode (bit0) + lidOpen (bit2)
        if (controlDeviceConnected) flags = flags or 0b0000_0010 // controlDeviceConnected (bit1)
        raw[34] = flags.toUByte()

        // Control device type (21) + control serial number (22..33) -- populated independently of
        // controlDeviceConnected per populateControlDeviceBytes (see its KDoc above); left
        // all-zero (i.e. "not set") otherwise.
        if (populateControlDeviceBytes) {
            raw[21] = controlDeviceType.type
            when (controlDeviceType) {
                CombustionProductType.PROBE ->
                    raw.putLittleEndianUInt32At(
                        22,
                        controlSerialNumber.toLong(radix = 16).toUInt(),
                    )

                else -> {
                    val serialBytes = controlSerialNumber.encodeToByteArray().toUByteArray()
                    serialBytes.copyInto(raw, destinationOffset = 22)
                }
            }
        }

        // Fan status (35..46)
        raw[35] = EngineFanState.FAN_ON.type
        raw[36] = 80u // duty cycle
        raw[37] = 75u // commanded speed
        raw[38] = 70u // measured speed
        raw.putLittleEndianUInt32At(39, 1_000u) // fan off time ms
        raw.putLittleEndianUInt32At(43, 2_000u) // fan on time ms

        // Controller status (47..54)
        raw[47] = EngineControllerState.OBSERVE.type
        raw[48] = 250u // response coefficient -> 0.5
        raw[49] = 3u // cycles completed
        raw[50] = 0b0000_0001u // reachedSetpoint
        raw.putLittleEndianUInt16At(51, 1500u) // smoothed temperature -> 150.0C
        raw[53] = 60u // time to peak seconds
        raw[54] = 0xFFu // drift rate: -1 / 1000.0

        // Hop count (55): top two bits = HOP2 (0b01)
        raw[55] = 0b0100_0000u

        // Knob voltage/angle (56..59)
        raw.putLittleEndianUInt16At(56, 3300u) // 3.3V
        raw.putLittleEndianUInt16At(58, 1800u) // 180.0 degrees

        // Charging fault (60): bit 0 = fault present, bits 1-7 = fault code.
        // OVER_TEMP.type (0x01) shifted left by 1, with the "fault present" bit set.
        raw[60] = ((EngineChargingFault.OVER_TEMP.type.toUInt() shl 1) or 1u).toUByte()

        return raw
    }

    private fun UByteArray.putLittleEndianUInt16At(index: Int, value: UInt) {
        this[index] = (value and 0x00FFu).toUByte()
        this[index + 1] = ((value and 0xFF00u) shr 8).toUByte()
    }

    @Test
    fun `fromRawData with not enough data returns null`() {
        val raw = UByteArray(EngineStatus.RAW_SIZE - 1) { 0xFFu }

        assertNull(EngineStatus.fromRawData(raw))
    }

    @Test
    fun `fromRawData parses session info and sequence numbers`() {
        val status = EngineStatus.fromRawData(buildRawStatus())

        assertEquals(0xDEADBEEFu, status?.sessionInformation?.sessionID)
        assertEquals(5000u, status?.sessionInformation?.samplePeriod)
        assertEquals(5000u, status?.samplePeriod)
        assertEquals(100u, status?.minSequenceNumber)
        assertEquals(200u, status?.maxSequenceNumber)
    }

    @Test
    fun `fromRawData parses battery status`() {
        val status = EngineStatus.fromRawData(buildRawStatus())

        assertEquals(EngineBatteryLevel.LOW_BATTERY, status?.engineBatteryStatus?.batteryLevel)
        assertEquals(EngineBatteryState.CHARGING, status?.engineBatteryStatus?.batteryState)
        assertEquals(12.0f, status?.engineBatteryStatus?.batteryVoltageVolts)
    }

    @Test
    fun `fromRawData parses temperature set point`() {
        val status = EngineStatus.fromRawData(buildRawStatus())

        status?.temperatureSetPoint?.value?.let {
            assertEquals(100.0, it, 0.05)
        } ?: run {
            assert(false)
        }
    }

    @Test
    fun `fromRawData parses engine status flags`() {
        val status = EngineStatus.fromRawData(buildRawStatus(controlDeviceConnected = true))

        assertEquals(true, status?.engineStatusFlags?.appMode)
        assertEquals(true, status?.engineStatusFlags?.controlDeviceConnected)
        assertEquals(true, status?.engineStatusFlags?.lidOpen)
        assertEquals(false, status?.engineStatusFlags?.fixedSpeed)
    }

    @Test
    fun `fromRawData leaves control device fields null when no controller connected`() {
        val status = EngineStatus.fromRawData(buildRawStatus(controlDeviceConnected = false))

        assertNull(status?.controlDeviceType)
        assertNull(status?.controlSerialNumber)
        assertNull(status?.controlTemperature)
    }

    @Test
    fun `fromRawData retains control device identity while disconnected, so a transient drop can be debounced`() {
        // Regression test: parsing must not gate controlDeviceType/controlSerialNumber on
        // controlDeviceConnected. A disconnect-confirmation debounce implemented by a consuming
        // application relies on these surviving a momentary controlDeviceConnected=false so it
        // can keep attributing the engine to its last controller until the disconnect is
        // confirmed. Re-gating this on the flag (as a well-intentioned "ignore stale bytes" fix
        // might do) would silently break that without failing any EngineManager-level test, since
        // those construct EngineStatus directly rather than through fromRawData.
        val status = EngineStatus.fromRawData(
            buildRawStatus(
                controlDeviceConnected = false,
                populateControlDeviceBytes = true,
                controlDeviceType = CombustionProductType.PROBE,
                controlSerialNumber = "1000DA4C",
            )
        )

        assertEquals(false, status?.engineStatusFlags?.controlDeviceConnected)
        assertEquals(CombustionProductType.PROBE, status?.controlDeviceType)
        assertEquals("1000DA4C", status?.controlSerialNumber)
    }

    @Test
    fun `fromRawData parses a probe control device's serial number as hex`() {
        val status = EngineStatus.fromRawData(
            buildRawStatus(
                controlDeviceConnected = true,
                controlDeviceType = CombustionProductType.PROBE,
                controlSerialNumber = "1000DA4C",
            )
        )

        assertEquals(CombustionProductType.PROBE, status?.controlDeviceType)
        assertEquals("1000DA4C", status?.controlSerialNumber)
        status?.controlTemperature?.value?.let {
            assertEquals(50.0, it, 0.05)
        } ?: run {
            assert(false)
        }
    }

    @Test
    fun `fromRawData parses a node control device's serial number as UTF-8`() {
        val status = EngineStatus.fromRawData(
            buildRawStatus(
                controlDeviceConnected = true,
                controlDeviceType = CombustionProductType.GAUGE,
                controlSerialNumber = "AB12CD34EF",
            )
        )

        assertEquals(CombustionProductType.GAUGE, status?.controlDeviceType)
        assertEquals("AB12CD34EF", status?.controlSerialNumber)
        status?.controlTemperature?.value?.let {
            assertEquals(50.0, it, 0.05)
        } ?: run {
            assert(false)
        }
    }

    @Test
    fun `fromRawData parses fan status`() {
        val status = EngineStatus.fromRawData(buildRawStatus())

        assertEquals(EngineFanState.FAN_ON, status?.engineFanStatus?.fanState)
        assertEquals(80u.toUByte(), status?.engineFanStatus?.dutyCycle)
        assertEquals(75u.toUByte(), status?.engineFanStatus?.commandedSpeed)
        assertEquals(70u.toUByte(), status?.engineFanStatus?.measuredSpeed)
        assertEquals(1_000u, status?.engineFanStatus?.fanOffTimeMs)
        assertEquals(2_000u, status?.engineFanStatus?.fanOnTimeMs)
    }

    @Test
    fun `fromRawData parses controller status`() {
        val status = EngineStatus.fromRawData(buildRawStatus())

        assertEquals(EngineControllerState.OBSERVE, status?.engineControllerStatus?.state)
        assertEquals(0.5f, status?.engineControllerStatus?.responseCoefficient)
        assertEquals(3u.toUByte(), status?.engineControllerStatus?.cyclesCompleted)
        assertEquals(
            EngineControllerFlags(reachedSetpoint = true, maintenanceMode = false),
            status?.engineControllerStatus?.flags,
        )
        assertEquals(150.0f, status?.engineControllerStatus?.smoothedTemperatureCelsius)
        assertEquals(60u.toUByte(), status?.engineControllerStatus?.timeToPeakSeconds)
        status?.engineControllerStatus?.driftRateCelsiusPerSecond?.let {
            assertEquals(-0.001f, it, 0.0001f)
        } ?: run {
            assert(false)
        }
    }

    @Test
    fun `fromRawData parses hop count, knob readings, and charging fault`() {
        val status = EngineStatus.fromRawData(buildRawStatus())

        assertEquals(HopCount.HOP2, status?.hopCount)
        assertEquals(3300u, status?.knobVoltageMillivolts)
        assertEquals(1800u, status?.knobAngleTenthsDegrees)
        assertEquals(EngineChargingFault.OVER_TEMP, status?.chargingFault)
    }
}
