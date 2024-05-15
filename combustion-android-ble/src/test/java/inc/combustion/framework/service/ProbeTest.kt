/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeTest.kt
 * Author:
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

package inc.combustion.framework.service

import org.junit.Assert.*

import org.junit.Test

class ProbeTest {
    @Test
    fun `Probe is not overheating when no sensors are overheating` () {
        val probe = Probe(
            baseDevice = Device(mac = "12:34:56:78:90:11"),
            overheatingSensors = listOf(),
        )

        assertFalse(probe.isOverheating)
    }

    @Test
    fun `Probe is overheating when sensors are overheating` () {
        val probe = Probe(
            baseDevice = Device(mac = "12:34:56:78:90:11"),
            overheatingSensors = listOf(1, 3, 5),
        )

        assertTrue(probe.isOverheating)
    }

    @Test
    fun `Probe isPredicting is correct`() {
        val probe = Probe(
            baseDevice = Device(mac = "12:34:56:78:90:11"),
        )

        assertFalse(probe.copy(predictionMode = null).isPredicting)
        assertFalse(probe.copy(predictionMode = ProbePredictionMode.NONE).isPredicting)
        assertFalse(probe.copy(predictionMode = ProbePredictionMode.RESERVED).isPredicting)
        assertTrue(probe.copy(predictionMode = ProbePredictionMode.TIME_TO_REMOVAL).isPredicting)
        assertTrue(probe.copy(predictionMode = ProbePredictionMode.REMOVAL_AND_RESTING).isPredicting)
    }
}