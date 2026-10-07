/*
 * Project: Combustion Inc. Android Framework
 * File: InstantReadArbitrator.kt
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

/**
 * Decides which link's Instant Read data to use for a probe. One instance per probe is shared by
 * advertising packets and status notifications, so both follow the same rule:
 *
 * 1. Data from a direct link to the probe is always used -- it's the freshest, with no relay
 *    delay -- and locks out relayed data for [LOCK_TIMEOUT_MS].
 * 2. Once no data has been used for [LOCK_TIMEOUT_MS], any link takes over.
 * 3. Otherwise relayed data is used if it comes from the current link, or from a link with a
 *    strictly lower hop count (which then becomes the current link). Other links at an equal or
 *    worse hop count are ignored, so readings come from one node at a time rather than
 *    interleaving nodes with different relay delays, which could briefly show an older reading
 *    after a newer one.
 *
 * Combines the iOS framework's rule (direct link always wins, 1 s lockout) with this framework's
 * previous preference for staying with one link among equally good ones.
 *
 * @param clock milliseconds since an arbitrary fixed point (monotonic); overridable for tests.
 */
internal class InstantReadArbitrator(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    companion object {
        const val LOCK_TIMEOUT_MS = 1000L
    }

    private var currentLink: Any? = null

    // null for a direct link
    private var currentHopCount: UInt? = null
    private var lastUsedAtMs: Long? = null

    /**
     * Returns whether to use Instant Read data that arrived on [link], recording [link] as the
     * current link if so.
     *
     * @param link identifies the link the data arrived on.
     * @param hopCount null for a direct link to the probe, otherwise the relay's hop count.
     */
    @Synchronized
    fun shouldUpdate(link: Any, hopCount: UInt?): Boolean {
        val now = clock()
        val lastUsed = lastUsedAtMs
        val locked = (lastUsed != null) && (now - lastUsed < LOCK_TIMEOUT_MS)
        val current = currentHopCount

        val shouldUpdate = when {
            hopCount == null -> true
            !locked -> true
            link == currentLink -> true
            current == null -> false
            else -> hopCount < current
        }

        if (shouldUpdate) {
            currentLink = link
            currentHopCount = hopCount
            lastUsedAtMs = now
        }
        return shouldUpdate
    }
}
