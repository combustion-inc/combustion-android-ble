/*
 * Project: Combustion Inc. Android Framework
 * File: SessionStarts.kt
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

/**
 * Tells a late message of an older session apart from a new session, by when each session started.
 *
 * A device runs one session at a time -- a reboot ends one before the next starts -- so a newer
 * session always started later. Session IDs are random, but a status carries enough to estimate
 * when its session started: sequence numbers restart with each session and advance once per sample
 * period, so it started about maxSequenceNumber * samplePeriod before the status was received (the
 * same estimate [inc.combustion.framework.log.Session] uses). Relay delay only makes that estimate
 * later, so the earliest estimate seen for a session is the best one.
 *
 * An estimate is late by up to one sample period (sequence numbers only advance once per sample)
 * plus relay delay, so a session is only considered older if it started more than one sample period
 * before the current one. That leaves room for a misjudgement only if the older session lasted less
 * than about two sample periods plus relay delay -- and then a status is simply accepted, as it was
 * without this check, and the current session's next status switches back.
 *
 * Not thread safe -- callers serialize access (e.g. under their status handling mutex).
 */
internal class SessionStarts(private val maxRemembered: Int = DEFAULT_MAX_REMEMBERED) {
    companion object {
        // Sessions only change on a reboot, so the last few are plenty to recognize a late message
        // of a previous one.
        const val DEFAULT_MAX_REMEMBERED = 4
    }

    // earliest estimated start (SystemClock.elapsedRealtime() ms) of recently seen sessions, by
    // session ID, in the order they were first seen
    private val earliestStartMs = linkedMapOf<UInt, Long>()

    /**
     * Returns false if a status of session [info] with [maxSequenceNumber] is of a session that
     * started clearly before [current] -- i.e. a late status of an older session. Otherwise records
     * it and returns true.
     */
    fun acceptStatus(
        info: SessionInformation,
        maxSequenceNumber: UInt,
        current: SessionInformation?,
    ): Boolean {
        val startMs = SystemClock.elapsedRealtime() -
            maxSequenceNumber.toLong() * info.samplePeriod.toLong()
        if (startedClearlyBefore(startMs, info, current)) return false

        earliestStartMs[info.sessionID] = minOf(startMs, earliestStartMs[info.sessionID] ?: startMs)
        while (earliestStartMs.size > maxRemembered) {
            earliestStartMs.remove(earliestStartMs.keys.first())
        }
        return true
    }

    /**
     * Returns false if session info [info] -- e.g. a session info response, which carries no
     * sequence number -- is of a remembered session that started clearly before [current].
     * An unknown session is accepted.
     */
    fun acceptSessionInfo(info: SessionInformation, current: SessionInformation?): Boolean {
        val startMs = earliestStartMs[info.sessionID] ?: return true
        return !startedClearlyBefore(startMs, info, current)
    }

    private fun startedClearlyBefore(
        startMs: Long,
        info: SessionInformation,
        current: SessionInformation?,
    ): Boolean {
        if (current == null || info.sessionID == current.sessionID) return false
        val currentStartMs = earliestStartMs[current.sessionID] ?: return false
        val toleranceMs = maxOf(info.samplePeriod, current.samplePeriod).toLong()
        return startMs + toleranceMs < currentStartMs
    }
}
