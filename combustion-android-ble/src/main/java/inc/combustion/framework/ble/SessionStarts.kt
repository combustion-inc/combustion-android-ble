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
 * before the current one. That can still misjudge a session that lasted less than about two sample
 * periods plus relay delay -- e.g. after a quick double reset, a short session only heard late over
 * a slow route can make the newer session look older. So statuses that keep being rejected while
 * nothing is heard of the current session are accepted after [STUCK_SAMPLE_PERIODS] sample
 * periods: a late message is a one-off, a steady stream means that session is live.
 *
 * Not thread safe -- callers serialize access (e.g. under their status handling mutex).
 */
internal class SessionStarts(private val maxRemembered: Int = DEFAULT_MAX_REMEMBERED) {
    companion object {
        // Sessions only change on a reboot, so the last few are plenty to recognize a late message
        // of a previous one.
        const val DEFAULT_MAX_REMEMBERED = 4

        // how long, in sample periods, statuses must keep being rejected, with nothing heard of
        // the current session, before they're accepted anyway
        const val STUCK_SAMPLE_PERIODS = 3

        // A status without session info can only be compared with the current session's own
        // estimates -- both drift with the device clock, and sequence numbers may advance a sample
        // apart -- so it must be clearly older: by two sample periods, plus a fraction of how long
        // the current session has run (far more than any clock drift).
        private const val WITHOUT_SESSION_INFO_TOLERANCE_SAMPLE_PERIODS = 2
        private const val WITHOUT_SESSION_INFO_TOLERANCE_AGE_DIVISOR = 100

        private fun estimateStartMs(nowMs: Long, maxSequenceNumber: UInt, samplePeriod: UInt) =
            nowMs - maxSequenceNumber.toLong() * samplePeriod.toLong()
    }

    private class Start(
        // earliest estimated start (SystemClock.elapsedRealtime() ms)
        var startMs: Long,
        // false if only known from session info without a status, i.e. startMs is an upper bound
        var fromStatus: Boolean,
        // when a status of the session was last accepted, if ever
        var lastStatusMs: Long? = null,
    )

    // statuses being rejected since firstMs, most recently at lastMs
    private class Rejection(val firstMs: Long, var lastMs: Long)

    // recently seen sessions, by session ID, in the order they were first seen
    private val starts = linkedMapOf<UInt, Start>()

    // sessions whose statuses are being rejected, by session ID, in the order first rejected
    private val rejections = linkedMapOf<UInt, Rejection>()

    // statuses without session info being rejected
    private var withoutSessionInfoRejection: Rejection? = null

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
        val nowMs = SystemClock.elapsedRealtime()
        val startMs = estimateStartMs(nowMs, maxSequenceNumber, info.samplePeriod)
        if (startedClearlyBefore(startMs, info, current)) {
            val stuckMs = stuckMs(info.samplePeriod, current)
            val rejection = rejections[info.sessionID]
            if (!isStuck(rejection, stuckMs, current, nowMs)) {
                rejections[info.sessionID] = rejected(rejection, stuckMs, nowMs)
                while (rejections.size > maxRemembered) {
                    rejections.remove(rejections.keys.first())
                }
                return false
            }
        }
        rejections.remove(info.sessionID)

        val start = starts[info.sessionID]
        if (start == null) {
            addStart(info.sessionID, Start(startMs, fromStatus = true, lastStatusMs = nowMs))
        } else {
            start.statusAccepted(startMs, nowMs)
        }
        return true
    }

    /**
     * Returns true if a status without session info, with [maxSequenceNumber], is of a session
     * that started clearly before [current] -- e.g. a late status of the previous session relayed
     * by an older MeatNet node that strips session info. Assumes the same sample period as
     * [current]. Only judged once [current]'s start has been estimated from a status of it.
     */
    fun isStatusWithoutSessionInfoOfOlderSession(
        maxSequenceNumber: UInt,
        current: SessionInformation?,
    ): Boolean {
        val currentStart = current?.let { starts[it.sessionID] }?.takeIf { it.fromStatus }
            ?: return false
        val nowMs = SystemClock.elapsedRealtime()
        val startMs = estimateStartMs(nowMs, maxSequenceNumber, current.samplePeriod)
        val toleranceMs =
            WITHOUT_SESSION_INFO_TOLERANCE_SAMPLE_PERIODS * current.samplePeriod.toLong() +
                (nowMs - currentStart.startMs) / WITHOUT_SESSION_INFO_TOLERANCE_AGE_DIVISOR
        val stuckMs = stuckMs(current.samplePeriod, current)
        val rejection = withoutSessionInfoRejection
        if (startMs + toleranceMs >= currentStart.startMs ||
            isStuck(rejection, stuckMs, current, nowMs)
        ) {
            withoutSessionInfoRejection = null
            return false
        }
        withoutSessionInfoRejection = rejected(rejection, stuckMs, nowMs)
        return true
    }

    /**
     * Records that a status without session info, with [maxSequenceNumber], was accepted into
     * [current]: the current session is still being heard, and its start can be estimated from it.
     * Should it actually be of an older session, that estimate is only earlier, which can't make
     * another session look older.
     */
    fun statusWithoutSessionInfoAccepted(maxSequenceNumber: UInt, current: SessionInformation?) {
        val start = current?.let { starts[it.sessionID] } ?: return
        val nowMs = SystemClock.elapsedRealtime()
        start.statusAccepted(estimateStartMs(nowMs, maxSequenceNumber, current.samplePeriod), nowMs)
    }

    /**
     * Returns false if session info [info] -- e.g. a session info response, which carries no
     * sequence number -- is of a remembered session that started clearly before [current].
     * An unknown session is accepted.
     */
    fun acceptSessionInfo(info: SessionInformation, current: SessionInformation?): Boolean {
        val start = starts[info.sessionID] ?: return true
        return !startedClearlyBefore(start.startMs, info, current)
    }

    /**
     * Records that session info [info] without a status (e.g. a session info response) became the
     * current session. If its start isn't known from a status yet, it started no later than now,
     * which makes a late status of an older session recognizable. That's later than the real
     * start, so a newer session could look older if the device rebooted again right away -- but
     * then a status of the current session soon gives a better estimate, and statuses that keep
     * being rejected are accepted anyway (see [STUCK_SAMPLE_PERIODS]).
     */
    fun switchedBySessionInfo(info: SessionInformation) {
        if (info.sessionID !in starts) {
            addStart(info.sessionID, Start(SystemClock.elapsedRealtime(), fromStatus = false))
        }
    }

    private fun Start.statusAccepted(estimatedStartMs: Long, nowMs: Long) {
        startMs = minOf(estimatedStartMs, startMs)
        fromStatus = true
        lastStatusMs = nowMs
    }

    private fun addStart(sessionID: UInt, start: Start) {
        starts[sessionID] = start
        while (starts.size > maxRemembered) {
            starts.remove(starts.keys.first())
        }
    }

    private fun startedClearlyBefore(
        startMs: Long,
        info: SessionInformation,
        current: SessionInformation?,
    ): Boolean {
        if (current == null || info.sessionID == current.sessionID) return false
        val currentStartMs = starts[current.sessionID]?.startMs ?: return false
        val toleranceMs = maxOf(info.samplePeriod, current.samplePeriod).toLong()
        return startMs + toleranceMs < currentStartMs
    }

    private fun stuckMs(samplePeriod: UInt, current: SessionInformation?): Long =
        STUCK_SAMPLE_PERIODS * maxOf(samplePeriod, current?.samplePeriod ?: 0u).toLong()

    // after a gap longer than stuckMs, a rejection is a new, separate late message rather than
    // part of a steady stream
    private fun Rejection.isOngoing(stuckMs: Long, nowMs: Long) = nowMs - lastMs <= stuckMs

    private fun rejected(rejection: Rejection?, stuckMs: Long, nowMs: Long): Rejection =
        rejection?.takeIf { it.isOngoing(stuckMs, nowMs) }?.apply { lastMs = nowMs }
            ?: Rejection(firstMs = nowMs, lastMs = nowMs)

    /**
     * True if statuses have kept being rejected since [rejection] began, without a gap, for
     * [stuckMs], while no status of [current] was accepted.
     */
    private fun isStuck(
        rejection: Rejection?,
        stuckMs: Long,
        current: SessionInformation?,
        nowMs: Long,
    ): Boolean {
        if (rejection == null || !rejection.isOngoing(stuckMs, nowMs)) return false
        val lastCurrentMs = current?.let { starts[it.sessionID] }?.lastStatusMs
        return nowMs - rejection.firstMs >= stuckMs &&
            (lastCurrentMs == null || nowMs - lastCurrentMs >= stuckMs)
    }
}
