/*
 * Project: Combustion Inc. Android Framework
 * File: SessionStartsTest.kt
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

import android.os.SystemClock
import inc.combustion.framework.service.SessionInformation
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionStartsTest {

    private var nowMs = 10_000_000L

    private val session1 = SessionInformation(sessionID = 1u, samplePeriod = 5000u)
    private val session2 = SessionInformation(sessionID = 2u, samplePeriod = 5000u)
    private val session3 = SessionInformation(sessionID = 3u, samplePeriod = 5000u)

    @Before
    fun setup() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { nowMs }
    }

    @After
    fun tearDown() {
        unmockkStatic(SystemClock::class)
    }

    /** Session 1 ran for 100 samples, then a reboot started session 2, now 2 samples in. */
    private fun SessionStarts.afterReset() {
        assertTrue(acceptStatus(session1, maxSequenceNumber = 100u, current = null))
        nowMs += 15_000
        assertTrue(acceptStatus(session2, maxSequenceNumber = 2u, current = session1))
    }

    @Test
    fun `the first status is accepted`() {
        assertTrue(SessionStarts().acceptStatus(session1, maxSequenceNumber = 100u, current = null))
    }

    @Test
    fun `a status of a new session is accepted`() {
        SessionStarts().afterReset()
    }

    @Test
    fun `a late status of the previous session is rejected`() {
        val sessionStarts = SessionStarts()
        sessionStarts.afterReset()

        assertFalse(sessionStarts.acceptStatus(session1, maxSequenceNumber = 101u, current = session2))
    }

    @Test
    fun `a late status of an older session never seen before is rejected`() {
        val sessionStarts = SessionStarts()
        assertTrue(sessionStarts.acceptStatus(session2, maxSequenceNumber = 2u, current = null))

        assertFalse(sessionStarts.acceptStatus(session1, maxSequenceNumber = 100u, current = session2))
    }

    @Test
    fun `a new session is accepted even when its sequence number is above the old one's`() {
        val sessionStarts = SessionStarts()
        assertTrue(sessionStarts.acceptStatus(session1, maxSequenceNumber = 10u, current = null))

        nowMs += 3_600_000
        assertTrue(sessionStarts.acceptStatus(session2, maxSequenceNumber = 100u, current = session1))
    }

    @Test
    fun `a session that started within one sample period of the current one isn't rejected`() {
        val sessionStarts = SessionStarts()
        assertTrue(sessionStarts.acceptStatus(session2, maxSequenceNumber = 2u, current = null))

        // estimated to have started 4.999 s before session 2: too close to tell
        nowMs -= 4_999
        assertTrue(sessionStarts.acceptStatus(session1, maxSequenceNumber = 2u, current = session2))
    }

    @Test
    fun `the earliest estimate of the current session is kept`() {
        val sessionStarts = SessionStarts()
        assertTrue(sessionStarts.acceptStatus(session1, maxSequenceNumber = 100u, current = null))
        // the same session relayed 60 s late makes a later estimate, which mustn't move its start
        nowMs += 60_000
        assertTrue(sessionStarts.acceptStatus(session1, maxSequenceNumber = 100u, current = session1))

        // started 20 s after session 1's earliest estimate, but 40 s before its late one
        assertTrue(sessionStarts.acceptStatus(session2, maxSequenceNumber = 108u, current = session1))
    }

    @Test
    fun `sequence numbers stuck at 0 never reject a session`() {
        // e.g. a gauge without a sensor: every estimate is just when the status was received
        val sessionStarts = SessionStarts()
        assertTrue(sessionStarts.acceptStatus(session1, maxSequenceNumber = 0u, current = null))
        nowMs += 20_000
        assertTrue(sessionStarts.acceptStatus(session2, maxSequenceNumber = 0u, current = session1))

        // a late status of the previous session can't be told apart, so it's accepted as before
        nowMs += 1_000
        assertTrue(sessionStarts.acceptStatus(session1, maxSequenceNumber = 0u, current = session2))
    }

    @Test
    fun `session info of a remembered older session is rejected`() {
        val sessionStarts = SessionStarts()
        sessionStarts.afterReset()

        assertFalse(sessionStarts.acceptSessionInfo(session1, current = session2))
        assertTrue(sessionStarts.acceptSessionInfo(session2, current = session2))
    }

    @Test
    fun `session info of an unknown session is accepted`() {
        val sessionStarts = SessionStarts()
        sessionStarts.afterReset()

        assertTrue(sessionStarts.acceptSessionInfo(session3, current = session2))
    }

    @Test
    fun `only the most recently seen sessions are remembered`() {
        val sessionStarts = SessionStarts(maxRemembered = 2)
        sessionStarts.afterReset()
        nowMs += 15_000
        assertTrue(sessionStarts.acceptStatus(session3, maxSequenceNumber = 2u, current = session2))

        // session 1 was forgotten, so its session info can no longer be recognized as older
        assertTrue(sessionStarts.acceptSessionInfo(session1, current = session3))
        assertFalse(sessionStarts.acceptSessionInfo(session2, current = session3))
    }
}
