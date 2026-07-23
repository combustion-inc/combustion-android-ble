/*
 * Project: Combustion Inc. Android Framework
 * File: NodeHybridDataLinkArbitratorTest.kt
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

import inc.combustion.framework.service.SessionInformation
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises [NodeHybridDataLinkArbitrator.shouldUpdateDataFromStatusForNormalMode], the
 * arbitration logic shared by Engine and Gauge (Probe has its own, separate implementation in
 * [ProbeDataLinkArbitrator], covered by [ProbeDataLinkArbitratorTest]). Uses
 * [EngineDataLinkArbitrator] as a concrete instance since the base class adds no behavior of its
 * own to test against.
 */
internal class NodeHybridDataLinkArbitratorTest {

    private fun getTested(): EngineDataLinkArbitrator = EngineDataLinkArbitrator()

    private fun status(maxSequenceNumber: UInt): SpecializedDeviceStatus {
        val status: SpecializedDeviceStatus = mockk(relaxed = true)
        every { status.maxSequenceNumber } returns maxSequenceNumber
        return status
    }

    @Test
    fun `first status is always accepted regardless of sequence number`() {
        val tested = getTested()
        assertTrue(
            tested.shouldUpdateDataFromStatusForNormalMode(
                status(maxSequenceNumber = 0u),
                SessionInformation(sessionID = 1u, samplePeriod = 1u),
            ),
        )
    }

    @Test
    fun `same session with a higher sequence number is accepted`() {
        val tested = getTested()
        val session = SessionInformation(sessionID = 1u, samplePeriod = 1u)
        tested.shouldUpdateDataFromStatusForNormalMode(status(1u), session)

        assertTrue(tested.shouldUpdateDataFromStatusForNormalMode(status(2u), session))
    }

    @Test
    fun `same session with an equal sequence number is rejected as a duplicate`() {
        val tested = getTested()
        val session = SessionInformation(sessionID = 1u, samplePeriod = 1u)
        tested.shouldUpdateDataFromStatusForNormalMode(status(5u), session)

        assertFalse(tested.shouldUpdateDataFromStatusForNormalMode(status(5u), session))
    }

    @Test
    fun `same session with a lower sequence number is rejected as stale`() {
        val tested = getTested()
        val session = SessionInformation(sessionID = 1u, samplePeriod = 1u)
        tested.shouldUpdateDataFromStatusForNormalMode(status(5u), session)

        assertFalse(tested.shouldUpdateDataFromStatusForNormalMode(status(3u), session))
    }

    @Test
    fun `a rejected stale status does not lower the bar for a later still-stale status`() {
        // Regression test: currentStatus must only advance on acceptance. It was previously
        // overwritten unconditionally, so a rejected status could make a later, still-stale status
        // look like an advance relative to it (e.g. accepted: 5, rejected: 3 -- without the fix,
        // a subsequent 4 would then wrongly look newer than the rejected 3).
        val tested = getTested()
        val session = SessionInformation(sessionID = 1u, samplePeriod = 1u)
        tested.shouldUpdateDataFromStatusForNormalMode(status(5u), session)

        assertFalse(tested.shouldUpdateDataFromStatusForNormalMode(status(3u), session))
        assertFalse(tested.shouldUpdateDataFromStatusForNormalMode(status(4u), session))
    }

    @Test
    fun `a session change is always accepted even with a lower sequence number`() {
        val tested = getTested()
        val firstSession = SessionInformation(sessionID = 1u, samplePeriod = 1u)
        val secondSession = SessionInformation(sessionID = 2u, samplePeriod = 1u)
        tested.shouldUpdateDataFromStatusForNormalMode(status(5u), firstSession)

        assertTrue(tested.shouldUpdateDataFromStatusForNormalMode(status(0u), secondSession))
    }
}
