/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeTemperatureLog.kt
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
package inc.combustion.framework.log

import inc.combustion.framework.service.LoggedProbeDataPoint

/**
 * Session log for a single probe.
 *
 * @property serialNumber Probe's serial number.
 */
internal class ProbeTemperatureLog(
    serialNumber: String,
) : TemperatureLog<LoggedProbeDataPoint>(serialNumber) {
    override val dataPointsBySession: List<List<LoggedProbeDataPoint>>
        get() {
            return sessions.map { it.dataPoints }.map {
                it.filterIsInstance<LoggedProbeDataPoint>()
            }
        }

    override val dataPoints: List<LoggedProbeDataPoint>
        get() = dataPointsBySession.flatten()
}