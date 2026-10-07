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

import inc.combustion.framework.ble.device.ProbeBleDevice
import inc.combustion.framework.ble.device.ProbeBleDeviceBase
import inc.combustion.framework.ble.device.RepeatedProbeBleDevice
import inc.combustion.framework.service.DeviceManager
import inc.combustion.framework.service.ProbeMode
import inc.combustion.framework.service.SessionInformation
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class ProbeDataLinkArbitratorTest {

    private val settings: DeviceManager.Settings = mockk(relaxed = true)

    private var nowMs = 100_000L

    private fun getTested(): ProbeDataLinkArbitrator {
        return ProbeDataLinkArbitrator(
            settings = settings,
            instantReadArbitrator = InstantReadArbitrator(clock = { nowMs }),
        )
    }

    // Instant Read status arbitration -- the rule itself is covered by InstantReadArbitratorTest;
    // these check that statuses are mapped onto it correctly: a direct link by its type (its hop
    // count is 0, like a node one hop from the probe), a relayed one by the status's hop count.

    private val instantReadStatus: ProbeStatus = mockk<ProbeStatus>(relaxed = true).also {
        every { it.mode } returns ProbeMode.INSTANT_READ
    }
    private val directLink: ProbeBleDevice = mockk(relaxed = true)
    private fun repeatedLink(): RepeatedProbeBleDevice = mockk<RepeatedProbeBleDevice>(relaxed = true).also {
        every { it.hopCount } returns 0u
    }

    private fun ProbeDataLinkArbitrator.shouldUpdateInstantRead(link: ProbeBleDeviceBase, hopCount: UInt?) =
        shouldUpdateDataFromStatus(instantReadStatus, sessionInfo = null, link = link, hopCount = hopCount)

    @Test
    fun `instant read -- a direct link wins over a node one hop away, though both report hop count 0`() {
        val tested = getTested()
        val node = repeatedLink()

        assertTrue(tested.shouldUpdateInstantRead(node, hopCount = 0u))
        assertTrue(tested.shouldUpdateInstantRead(directLink, hopCount = 0u))
        // locked out by the direct link
        assertFalse(tested.shouldUpdateInstantRead(node, hopCount = 0u))
    }

    @Test
    fun `instant read -- a relayed status uses the hop count reported with it`() {
        val tested = getTested()
        val farNode = repeatedLink()
        val nearNode = repeatedLink()

        assertTrue(tested.shouldUpdateInstantRead(farNode, hopCount = 2u))
        // nearNode's own hop count is 0, but the status says 3: worse, so ignored
        assertFalse(tested.shouldUpdateInstantRead(nearNode, hopCount = 3u))
        assertTrue(tested.shouldUpdateInstantRead(nearNode, hopCount = 1u))
    }

    @Test
    fun `instant read -- after the lock timeout any link takes over`() {
        val tested = getTested()
        val node = repeatedLink()

        assertTrue(tested.shouldUpdateInstantRead(directLink, hopCount = null))
        nowMs += InstantReadArbitrator.LOCK_TIMEOUT_MS

        assertTrue(tested.shouldUpdateInstantRead(node, hopCount = 1u))
    }

    // shouldUpdateDataFromStatusForNormalMode -- called directly here (bypassing
    // shouldUpdateDataFromStatus's mode-based routing, which isn't relevant to this logic).

    private fun normalModeStatus(maxSequenceNumber: UInt): ProbeStatus {
        val status: ProbeStatus = mockk(relaxed = true)
        every { status.maxSequenceNumber } returns maxSequenceNumber
        return status
    }

    @Test
    fun `normal mode -- first status is always accepted regardless of sequence number`() {
        val tested = getTested()
        assertTrue(
            tested.shouldUpdateDataFromStatusForNormalMode(
                normalModeStatus(maxSequenceNumber = 0u),
                SessionInformation(sessionID = 1u, samplePeriod = 1u),
            ),
        )
    }

    @Test
    fun `normal mode -- same session with a higher sequence number is accepted`() {
        val tested = getTested()
        val session = SessionInformation(sessionID = 1u, samplePeriod = 1u)
        tested.shouldUpdateDataFromStatusForNormalMode(normalModeStatus(1u), session)

        assertTrue(tested.shouldUpdateDataFromStatusForNormalMode(normalModeStatus(2u), session))
    }

    @Test
    fun `normal mode -- same session with an equal sequence number is rejected as a duplicate`() {
        val tested = getTested()
        val session = SessionInformation(sessionID = 1u, samplePeriod = 1u)
        tested.shouldUpdateDataFromStatusForNormalMode(normalModeStatus(5u), session)

        assertFalse(tested.shouldUpdateDataFromStatusForNormalMode(normalModeStatus(5u), session))
    }

    @Test
    fun `normal mode -- same session with a lower sequence number is rejected as stale`() {
        val tested = getTested()
        val session = SessionInformation(sessionID = 1u, samplePeriod = 1u)
        tested.shouldUpdateDataFromStatusForNormalMode(normalModeStatus(5u), session)

        assertFalse(tested.shouldUpdateDataFromStatusForNormalMode(normalModeStatus(3u), session))
    }

    @Test
    fun `normal mode -- a rejected stale status does not lower the bar for a later still-stale status`() {
        // Regression test: currentStatus must only advance on acceptance. It was previously
        // overwritten unconditionally, so a rejected status could make a later, still-stale status
        // look like an advance relative to it.
        val tested = getTested()
        val session = SessionInformation(sessionID = 1u, samplePeriod = 1u)
        tested.shouldUpdateDataFromStatusForNormalMode(normalModeStatus(5u), session)

        assertFalse(tested.shouldUpdateDataFromStatusForNormalMode(normalModeStatus(3u), session))
        assertFalse(tested.shouldUpdateDataFromStatusForNormalMode(normalModeStatus(4u), session))
    }

    @Test
    fun `normal mode -- a session change is always accepted even with a lower sequence number`() {
        val tested = getTested()
        val firstSession = SessionInformation(sessionID = 1u, samplePeriod = 1u)
        val secondSession = SessionInformation(sessionID = 2u, samplePeriod = 1u)
        tested.shouldUpdateDataFromStatusForNormalMode(normalModeStatus(5u), firstSession)

        assertTrue(
            tested.shouldUpdateDataFromStatusForNormalMode(normalModeStatus(0u), secondSession),
        )
    }
}
