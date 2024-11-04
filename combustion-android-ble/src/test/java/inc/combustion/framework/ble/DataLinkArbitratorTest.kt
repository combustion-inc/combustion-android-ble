/*
 * Project: Combustion Inc. Android Framework
 * File: DataLinkArbitratorTest.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2024. Combustion Inc.
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

import inc.combustion.framework.service.DeviceManager
import inc.combustion.framework.service.ProbeMode
import io.mockk.every
import io.mockk.mockk
import junitparams.JUnitParamsRunner
import junitparams.Parameters
import junitparams.naming.TestCaseName
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(JUnitParamsRunner::class)
internal class DataLinkArbitratorTest {

    private val probStatus: ProbeStatus = mockk(relaxed = true)
    private val settings: DeviceManager.Settings = mockk(relaxed = true)
    private val instantReadIdleMonitor: IdleMonitor = mockk(relaxed = true)

    private fun getTested(currentHopCount: UInt? = null): DataLinkArbitrator {
        return DataLinkArbitrator(
            settings = settings,
            instantReadIdleMonitor = instantReadIdleMonitor,
            currentHopCount = currentHopCount,
        )
    }

    @Test
    @Parameters(method = "shouldUpdateDataFromProbeStatusForInstantReadModeParams")
    @TestCaseName("given currentHopCount {0} and isIdle is {1} when get shouldUpdateDataFromProbeStatus for instant read with hopCount {2} then return {3}")
    fun `given currentHopCount and idle state when get shouldUpdateDataFromProbeStatus for instant read with a hopCount then verify correct return value`(
        givenCurrentHopCount: UInt?,
        givenIsIdle: Boolean,
        givenHopCount: UInt?,
        expectedValue: Boolean,
    ) {
        // given
        val tested = getTested(currentHopCount = givenCurrentHopCount)
        every { instantReadIdleMonitor.isIdle(1000L) } returns givenIsIdle
        every { probStatus.mode } returns ProbeMode.INSTANT_READ

        // when / then
        assertEquals(
            expectedValue,
            tested.shouldUpdateDataFromProbeStatus(
                status = probStatus,
                sessionInfo = null,
                hopCount = givenHopCount
            )
        )
    }

    fun shouldUpdateDataFromProbeStatusForInstantReadModeParams() = arrayOf(
        // when hopCount is null then always true
        arrayOf(null as? UInt?, false, null as? UInt?, true),
        arrayOf(0u, false, null as? UInt?, true),
        arrayOf(1u, false, null as? UInt?, true),
        arrayOf(2u, false, null as? UInt?, true),

        // when not idle then hopCount should be <= currentHopCount
        arrayOf(null as? UInt?, false, 0u, false),
        arrayOf(1u, false, 0u, true),
        arrayOf(1u, false, 1u, true),
        arrayOf(1u, false, 2u, false),

        // when idle then always return true
        arrayOf(null as? UInt?, true, 0u, true),
        arrayOf(1u, true, 2u, true),
    )
}