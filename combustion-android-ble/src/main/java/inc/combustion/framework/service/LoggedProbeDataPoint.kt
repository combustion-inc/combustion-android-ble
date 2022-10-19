/*
 * Project: Combustion Inc. Android Framework
 * File: LoggedProbeDataPoint.kt
 * Author: https://github.com/miwright2
 *
 * MIT License
 *
 * Copyright (c) 2022. Combustion Inc.
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

import inc.combustion.framework.ble.ProbeStatus
import inc.combustion.framework.ble.uart.LogResponse
import java.util.*

/**
 * Data class for a logged probe data point.
 *
 * @property sessionId Session ID for the data log.
 * @property sequenceNumber Sequence number for the data log
 * @property timestamp Wall clock timestamp
 * @property temperatures Temperature measurements
 */
data class LoggedProbeDataPoint (
    val sessionId: UInt,
    val sequenceNumber: UInt,
    val timestamp: Date,
    val temperatures: ProbeTemperatures
) : Comparable<LoggedProbeDataPoint> {

    override fun compareTo(other: LoggedProbeDataPoint): Int {
        return when {
            this.sequenceNumber > other.sequenceNumber -> 1
            this.sequenceNumber < other.sequenceNumber -> -1
            else -> 0
        }
    }

    internal companion object{
        fun fromDeviceStatus(
            sessionId: UInt,
            status: ProbeStatus,
            sessionStart: Date?,
            samplePeriod: UInt
        ): LoggedProbeDataPoint {
            val timestamp = getTimestamp(sessionStart, status.maxSequenceNumber, samplePeriod)
            return LoggedProbeDataPoint(sessionId, status.maxSequenceNumber, timestamp, status.temperatures)
        }

        fun fromLogResponse(
            sessionId: UInt,
            response: LogResponse,
            sessionStart: Date?,
            samplePeriod: UInt
        ): LoggedProbeDataPoint {
            val timestamp = getTimestamp(sessionStart, response.sequenceNumber, samplePeriod)
            return LoggedProbeDataPoint(sessionId, response.sequenceNumber, timestamp, response.temperatures)
        }

        private fun getTimestamp(sessionStart: Date?, sequenceNumber: UInt, samplePeriod: UInt): Date {
            val start = (sessionStart ?: Date()).time
            return Date(start + (sequenceNumber * samplePeriod).toLong())
        }
    }
}