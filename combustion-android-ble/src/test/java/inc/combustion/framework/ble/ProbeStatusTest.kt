/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeStatusTest.kt
 * Author: Nick Helseth <nick@sasq.io>
 *
 * MIT License
 *
 * Copyright (c) 2023. Combustion Inc.
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

import inc.combustion.framework.service.FoodSafeData
import inc.combustion.framework.service.FoodSafeStatus
import inc.combustion.framework.service.ProbeBatteryStatus
import inc.combustion.framework.service.ProbeColor
import inc.combustion.framework.service.ProbeID
import inc.combustion.framework.service.ProbeMode
import inc.combustion.framework.service.ProbePredictionMode
import inc.combustion.framework.service.ProbePredictionState
import inc.combustion.framework.service.ProbePredictionType
import inc.combustion.framework.service.ProbeVirtualSensors
import inc.combustion.framework.service.SessionInformation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeStatusTest {
    @Test
    fun `Not enough data returns null`() {
        val raw = UByteArray(13) { 0xFFu }

        assertNull(ProbeStatus.fromRawData(raw))
    }

    @Test
    fun `Not including food safe doesn't populate food safe`() {
        val raw = UByteArray(ProbeStatus.MIN_RAW_SIZE) { 0xFFu }

        assertNull(ProbeStatus.fromRawData(raw)?.foodSafeData)
        assertNull(ProbeStatus.fromRawData(raw)?.foodSafeStatus)
    }

    @Test
    fun `fromRaw minSequenceNumber`() {
        val raw = UByteArray(ProbeStatus.RAW_SIZE_INCLUDING_FOOD_SAFE) { 0xFFu }
        for (i in 0..3) {
            raw[i] = 0xAAu
        }

        val status = ProbeStatus.fromRawData(raw)

        status?.minSequenceNumber?.let {
            assertEquals(0xAAAAAAAAu, it)
        } ?: run {
            assertTrue(false)
        }
    }

    @Test
    fun `fromRaw maxSequenceNumber`() {
        val raw = UByteArray(ProbeStatus.RAW_SIZE_INCLUDING_FOOD_SAFE) { 0xFFu }
        for (i in 4..7) {
            raw[i] = 0x55u
        }

        val status = ProbeStatus.fromRawData(raw)

        status?.maxSequenceNumber?.let {
            assertEquals(0x55555555u, it)
        } ?: run {
            assertTrue(false)
        }
    }

    @Test
    fun `fromRaw temperatures`() {
        val raw = UByteArray(ProbeStatus.RAW_SIZE_INCLUDING_FOOD_SAFE) { 0xFFu }
        val rawTemperatures = ubyteArrayOf(
            0x89u, 0x00u, 0xDAu, 0x00u, 0xD7u,
            0x0Du, 0x07u, 0x33u, 0x05u, 0xD5u,
            0x18u, 0x74u, 0x1Au
        )

        rawTemperatures.forEachIndexed { index, value ->
            raw[index + 8] = value
        }

        val status = ProbeStatus.fromRawData(raw)

        assertArrayEquals(
            doubleArrayOf(-13.15, 67.2, 258.4, 160.55, 225.6, 114.5, 189.75, 22.3),
            status?.temperatures?.values?.toDoubleArray(),
            0.05
        )
    }

    @Test
    fun `fromRaw modeColorId`() {
        val raw = UByteArray(ProbeStatus.RAW_SIZE_INCLUDING_FOOD_SAFE) { 0xFFu }
        raw[21] = 0b101_110_01u

        val status = ProbeStatus.fromRawData(raw)

        assertEquals(ProbeMode.INSTANT_READ, status?.mode)
        assertEquals(ProbeColor.COLOR7, status?.color)
        assertEquals(ProbeID.ID6, status?.id)
    }

    @Test
    fun `fromRaw status`() {
        val raw = UByteArray(ProbeStatus.RAW_SIZE_INCLUDING_FOOD_SAFE) { 0xFFu }
        raw[22] = 0b10_11_101_1u

        val status = ProbeStatus.fromRawData(raw)

        assertEquals(ProbeBatteryStatus.LOW_BATTERY, status?.batteryStatus)
        assertEquals(
            ProbeVirtualSensors.VirtualCoreSensor.T6,
            status?.virtualSensors?.virtualCoreSensor
        )
        assertEquals(
            ProbeVirtualSensors.VirtualSurfaceSensor.T7,
            status?.virtualSensors?.virtualSurfaceSensor
        )
        assertEquals(
            ProbeVirtualSensors.VirtualAmbientSensor.T7,
            status?.virtualSensors?.virtualAmbientSensor
        )
    }

    @Test
    fun `fromRaw prediction status`() {
        val raw = UByteArray(ProbeStatus.RAW_SIZE_INCLUDING_FOOD_SAFE) { 0xFFu }
        raw[23] = 0b10_01_0011u
        raw[24] = 0xA9u // [8:1 of set point]
        raw[25] = 0b101010_10u // [6:1 of heat start]_[10:9 of set point]
        raw[26] = 0b1101_1001u // [4:1 of prediction value]_[10:7 of heat start]
        raw[27] = 0b1101_1011u // [12:5 of prediction value]
        raw[28] = 0b101_10101u // [3:1 of estimated core]_[17:13 of prediction value]
        raw[29] = 0xF9u // [11:4 of estimated core]

        val status = ProbeStatus.fromRawData(raw)

        assertEquals(ProbePredictionState.PREDICTING, status?.predictionStatus?.predictionState)
        assertEquals(ProbePredictionMode.TIME_TO_REMOVAL, status?.predictionStatus?.predictionMode)
        assertEquals(ProbePredictionType.RESTING, status?.predictionStatus?.predictionType)
        status?.predictionStatus?.setPointTemperature?.let {
            assertEquals(68.1, it, 0.1)
        } ?: run {
            assertTrue(false)
        }
        status?.predictionStatus?.heatStartTemperature?.let {
            assertEquals(61.8, it, 0.1)
        } ?: run {
            assertTrue(false)
        }
        status?.predictionStatus?.predictionValueSeconds?.let {
            assertEquals(89533u, it)
        } ?: run {
            assertTrue(false)
        }
        status?.predictionStatus?.estimatedCoreTemperature?.let {
            assertEquals((1997 * 0.1) - 20, it, 0.1)
        } ?: run {
            assertTrue(false)
        }
    }

    @Test
    fun `fromRaw food safe data`() {
        val foodSafeData = FoodSafeData.Integrated(
            product = FoodSafeData.Integrated.Product.DairyMilkLessThan10PercentFat,
            serving = FoodSafeData.Serving.CookAndChill,
            completionCriteria = FoodSafeData.Integrated.CompletionCriteria(
                selectedThresholdReferenceTemperature = 273.05,
                zValue = 136.5,
                referenceTemperature = 273.05,
                dValueAtRt = 229.7,
                targetLogReduction = 17.0,
            )
        )

        val rawFoodSafeData = foodSafeData.toRaw

        val raw = UByteArray(ProbeStatus.RAW_SIZE_INCLUDING_FOOD_SAFE) { 0xFFu }
        rawFoodSafeData.forEachIndexed { index, value ->
            raw[index + 30] = value
        }

        assertEquals(foodSafeData, ProbeStatus.fromRawData(raw)?.foodSafeData)
    }

    @Test
    fun `fromRaw food safe status`() {
        val raw = UByteArray(ProbeStatus.RAW_SIZE_INCLUDING_FOOD_SAFE) { 0xFFu }

        raw[40] = 0b11011_010u // [5:1 of log reduction]_[3:1 of food safe state]
        raw[41] = 0b01011_101u // [5:1 of seconds above threshold]_[8:6 of food log reduction]
        raw[42] = 0xE6u // [13:6 of seconds above threshold]
        raw[43] = 0b01110_101u // [5:1 of log sequence number]_[16:14 of seconds above threshold]
        raw[44] = 0xD6u // [13:6 of log sequence number]
        raw[45] = 0x7Fu // [21:14 of log sequence number]
        raw[46] = 0xF7u // [29:22 of log sequence number]
        raw[47] = 0b11111_101u // [32:30 of log sequence number]

        val foodSafeStatus = FoodSafeStatus(
            state = FoodSafeStatus.State.SafetyImpossible,
            logReduction = 18.7,
            secondsAboveThreshold = 48331u,
            sequenceNumber = 0xBEEFFACEu,
        )

        assertEquals(foodSafeStatus, ProbeStatus.fromRawData(raw)?.foodSafeStatus)
    }

    @Test
    fun `fromRaw overall`() {
        val raw = UByteArray(ProbeStatus.RAW_SIZE_INCLUDING_FOOD_SAFE) { 0xFFu }
        // Min sequence
        for (i in 0..3) {
            raw[i] = 0xAAu
        }

        // Max sequence
        for (i in 4..7) {
            raw[i] = 0x55u
        }

        // Temperatures
        val rawTemperatures = ubyteArrayOf(
            0x89u, 0x00u, 0xDAu, 0x00u, 0xD7u,
            0x0Du, 0x07u, 0x33u, 0x05u, 0xD5u,
            0x18u, 0x74u, 0x1Au
        )

        rawTemperatures.forEachIndexed { index, value ->
            raw[index + 8] = value
        }

        // Mode/color/ID
        raw[21] = 0b101_110_01u

        // Status
        raw[22] = 0b10_11_101_1u

        // Prediction status
        raw[23] = 0b10_01_0011u
        raw[24] = 0xA9u // [8:1 of set point]
        raw[25] = 0b101010_10u // [6:1 of heat start]_[10:9 of set point]
        raw[26] = 0b1101_1001u // [4:1 of prediction value]_[10:7 of heat start]
        raw[27] = 0b1101_1011u // [12:5 of prediction value]
        raw[28] = 0b101_10101u // [3:1 of estimated core]_[17:13 of prediction value]
        raw[29] = 0xF9u // [11:4 of estimated core]

        // Food safe data
        val foodSafeData = FoodSafeData.Integrated(
            product = FoodSafeData.Integrated.Product.DairyMilkLessThan10PercentFat,
            serving = FoodSafeData.Serving.CookAndChill,
            completionCriteria = FoodSafeData.Integrated.CompletionCriteria(
                selectedThresholdReferenceTemperature = 273.05,
                zValue = 136.5,
                referenceTemperature = 273.05,
                dValueAtRt = 229.7,
                targetLogReduction = 17.0,
            )
        )

        val rawFoodSafeData = foodSafeData.toRaw
        rawFoodSafeData.forEachIndexed { index, value ->
            raw[index + 30] = value
        }

        // Food safe status
        raw[40] = 0b11011_010u // [5:1 of log reduction]_[3:1 of food safe state]
        raw[41] = 0b01011_101u // [5:1 of seconds above threshold]_[8:6 of food log reduction]
        raw[42] = 0xE6u // [13:6 of seconds above threshold]
        raw[43] = 0b01110_101u // [5:1 of log sequence number]_[16:14 of seconds above threshold]
        raw[44] = 0xD6u // [13:6 of log sequence number]
        raw[45] = 0x7Fu // [21:14 of log sequence number]
        raw[46] = 0xF7u // [29:22 of log sequence number]
        raw[47] = 0b11111_101u // [32:30 of log sequence number]

        val status = ProbeStatus.fromRawData(raw)

        assertEquals(0xAAAAAAAAu, status?.minSequenceNumber)
        assertEquals(0x55555555u, status?.maxSequenceNumber)

        assertArrayEquals(
            doubleArrayOf(-13.15, 67.2, 258.4, 160.55, 225.6, 114.5, 189.75, 22.3),
            status?.temperatures?.values?.toDoubleArray(),
            0.05
        )

        assertEquals(ProbeMode.INSTANT_READ, status?.mode)
        assertEquals(ProbeColor.COLOR7, status?.color)
        assertEquals(ProbeID.ID6, status?.id)

        assertEquals(ProbeBatteryStatus.LOW_BATTERY, status?.batteryStatus)
        assertEquals(
            ProbeVirtualSensors.VirtualCoreSensor.T6,
            status?.virtualSensors?.virtualCoreSensor
        )
        assertEquals(
            ProbeVirtualSensors.VirtualSurfaceSensor.T7,
            status?.virtualSensors?.virtualSurfaceSensor
        )
        assertEquals(
            ProbeVirtualSensors.VirtualAmbientSensor.T7,
            status?.virtualSensors?.virtualAmbientSensor
        )

        assertEquals(ProbePredictionState.PREDICTING, status?.predictionStatus?.predictionState)
        assertEquals(ProbePredictionMode.TIME_TO_REMOVAL, status?.predictionStatus?.predictionMode)
        assertEquals(ProbePredictionType.RESTING, status?.predictionStatus?.predictionType)
        status?.predictionStatus?.setPointTemperature?.let {
            assertEquals(68.1, it, 0.1)
        } ?: run {
            assertTrue(false)
        }
        status?.predictionStatus?.heatStartTemperature?.let {
            assertEquals(61.8, it, 0.1)
        } ?: run {
            assertTrue(false)
        }
        status?.predictionStatus?.predictionValueSeconds?.let {
            assertEquals(89533u, it)
        } ?: run {
            assertTrue(false)
        }
        status?.predictionStatus?.estimatedCoreTemperature?.let {
            assertEquals((1997 * 0.1) - 20, it, 0.1)
        } ?: run {
            assertTrue(false)
        }

        assertEquals(foodSafeData, ProbeStatus.fromRawData(raw)?.foodSafeData)

        val foodSafeStatus = FoodSafeStatus(
            state = FoodSafeStatus.State.SafetyImpossible,
            logReduction = 18.7,
            secondsAboveThreshold = 48331u,
            sequenceNumber = 0xBEEFFACEu,
        )
        assertEquals(foodSafeStatus, ProbeStatus.fromRawData(raw)?.foodSafeStatus)
    }

    @Test
    fun `Parses session information appended by newer firmware`() {
        val status = ProbeStatus.fromRawData(GOLDEN_NOTIFICATION)

        assertEquals(SessionInformation(sessionID = 0xDEADBEEFu, samplePeriod = 5000u), status?.sessionInformation)
        // The fields before the session info still parse at their unchanged offsets.
        assertEquals(0x01020304u, status?.minSequenceNumber)
        assertEquals(0x05060708u, status?.maxSequenceNumber)
        assertNotNull(status?.probeHighLowAlarmStatus)
    }

    @Test
    fun `Older firmware without session information parses with null session information`() {
        val raw = GOLDEN_NOTIFICATION.copyOfRange(0, SESSION_INFO_OFFSET)

        val status = ProbeStatus.fromRawData(raw)

        assertNotNull(status)
        assertNotNull(status?.probeHighLowAlarmStatus)
        assertNull(status?.sessionInformation)
    }

    @Test
    fun `Partial session information is ignored`() {
        val raw = GOLDEN_NOTIFICATION.copyOfRange(0, GOLDEN_NOTIFICATION.size - 1)

        assertNull(ProbeStatus.fromRawData(raw)?.sessionInformation)
    }

    @Test
    fun `Session ID of zero means not supplied`() {
        val raw = GOLDEN_NOTIFICATION.copyOf()
        raw.putLittleEndianUInt32At(SESSION_INFO_OFFSET, 0u)

        assertNull(ProbeStatus.fromRawData(raw)?.sessionInformation)
    }

    @Test
    fun `Sample period of zero means not supplied`() {
        val raw = GOLDEN_NOTIFICATION.copyOf()
        raw[SESSION_INFO_OFFSET + 4] = 0u
        raw[SESSION_INFO_OFFSET + 5] = 0u

        assertNull(ProbeStatus.fromRawData(raw)?.sessionInformation)
    }

    companion object {
        private const val SESSION_INFO_OFFSET = 94

        /**
         * The 100-byte Probe Status notification from meat-dagger-firmware's
         * firmware/tests/test_probe_status_layout.cpp (GOLDEN_NOTIFICATION), with session ID
         * 0xDEADBEEF and sample period 5000 ms at [SESSION_INFO_OFFSET].
         */
        val GOLDEN_NOTIFICATION = ubyteArrayOf(
            0x04u, 0x03u, 0x02u, 0x01u, 0x08u, 0x07u, 0x06u, 0x05u,
            0x23u, 0xC1u, 0x8Au, 0x24u, 0x1Eu, 0x5Eu, 0xF5u, 0xDEu,
            0x6Au, 0x82u, 0x91u, 0xB8u, 0x1Au, 0x89u, 0xA5u, 0x53u,
            0x71u, 0x4Au, 0x03u, 0xE1u, 0xA0u, 0x55u, 0x08u, 0x20u,
            0xB0u, 0xC4u, 0x12u, 0xE0u, 0x15u, 0x0Au, 0x60u, 0x04u,
            0x09u, 0xA2u, 0x91u, 0x78u, 0x6Fu, 0x5Eu, 0x4Du, 0x04u,
            0xA5u, 0x05u, 0x05u, 0x08u, 0x0Bu, 0x08u, 0x15u, 0x08u,
            0x1Bu, 0x08u, 0x25u, 0x08u, 0x2Bu, 0x08u, 0x35u, 0x08u,
            0x3Bu, 0x08u, 0x45u, 0x08u, 0x4Bu, 0x08u, 0x55u, 0x08u,
            0x03u, 0x10u, 0x0Eu, 0x10u, 0x13u, 0x10u, 0x1Eu, 0x10u,
            0x23u, 0x10u, 0x2Eu, 0x10u, 0x33u, 0x10u, 0x3Eu, 0x10u,
            0x43u, 0x10u, 0x4Eu, 0x10u, 0x53u, 0x10u, 0xEFu, 0xBEu,
            0xADu, 0xDEu, 0x88u, 0x13u,
        )
    }
}
