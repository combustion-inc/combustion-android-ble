/*
 * Project: Combustion Inc. Android Framework
 * File: DefaultLinearizationTimerImpl.kt
 * Author: Nick Helseth <nick@combustion.inc>
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

package inc.combustion.framework.service.utils

import android.os.SystemClock
import java.util.Timer
import java.util.TimerTask

class DefaultLinearizationTimerImpl: PredictionManager.LinearizationTimer {
    private var timer: Timer? = null
    private var linearizationStartTime: Long = 0

    override fun start(callback: () -> Unit, intervalMs: Long) {
        timer = Timer()
        linearizationStartTime = SystemClock.elapsedRealtime()

        // Start the linearization timer
        timer?.schedule( object : TimerTask() {
            override fun run() {
                callback()
            }
        }, intervalMs, intervalMs)
    }

    override fun stop() {
        timer?.cancel()
        timer = null
    }

    override val isActive: Boolean
        get() = timer != null

    override val timeSinceStartMs: Long
        get() = SystemClock.elapsedRealtime() - linearizationStartTime
}

