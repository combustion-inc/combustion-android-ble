/*
 * Project: Combustion Inc. Android Framework
 * File: LoggedGaugeDataPoint.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2025. Combustion Inc.
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

import inc.combustion.framework.ble.GaugeStatus
import inc.combustion.framework.ble.uart.meatnet.NodeReadGaugeLogsResponse
import inc.combustion.framework.service.LoggedDataPoint.Companion.getTimestamp
import java.util.Date

class LoggedGaugeDataPoint(
    override val sessionId: UInt,
    override val sequenceNumber: UInt,
    override val timestamp: Date,
    val temperature: SensorTemperature,
) : Comparable<LoggedGaugeDataPoint>, LoggedDataPoint {

    override fun compareTo(other: LoggedGaugeDataPoint): Int {
        return when {
            this.sequenceNumber > other.sequenceNumber -> 1
            this.sequenceNumber < other.sequenceNumber -> -1
            else -> 0
        }
    }

    internal companion object {
        fun fromDeviceStatus(
            status: GaugeStatus,
            sessionStart: Date?,
        ): LoggedGaugeDataPoint {

            val timestamp =
                getTimestamp(sessionStart, status.maxSequenceNumber, status.samplePeriod)
            return LoggedGaugeDataPoint(
                status.sessionInformation.sessionID,
                status.maxSequenceNumber,
                timestamp,
                status.temperature,
            )
        }

        fun fromLogResponse(
            sessionId: UInt,
            response: NodeReadGaugeLogsResponse,
            sessionStart: Date?,
            samplePeriod: UInt,
        ): LoggedGaugeDataPoint {
            val timestamp = getTimestamp(sessionStart, response.sequenceNumber, samplePeriod)
            return LoggedGaugeDataPoint(
                sessionId,
                response.sequenceNumber,
                timestamp,
                response.temperature,
            )
        }
    }
}