/*
 * Project: Combustion Inc. Android Example
 * File: ProbeBleDeviceBase.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2023. Combustion Inc.
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

import inc.combustion.framework.ble.uart.LogResponse
import inc.combustion.framework.service.Probe
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal interface IProbeBleDeviceBase {
    suspend fun emit(probe: Probe)
    suspend fun emit(probeStatus: ProbeStatus)
    suspend fun emit(logResponse: LogResponse)

    val probeFlow: SharedFlow<Probe>
    val probeStatusFlow: SharedFlow<ProbeStatus>
    val logResponseFlow: SharedFlow<LogResponse>

    val probe: Probe
}

internal class ProbeBleDeviceBase() : IProbeBleDeviceBase {
    private val _probeFlow = MutableSharedFlow<Probe>(
        replay = 0, extraBufferCapacity = 10, BufferOverflow.DROP_OLDEST)

    private val _probeStatusFlow = MutableSharedFlow<ProbeStatus>(
        replay = 0, extraBufferCapacity = 10, BufferOverflow.DROP_OLDEST)

    private val _logResponseFlow = MutableSharedFlow<LogResponse>(
        replay = 0, extraBufferCapacity = 50, BufferOverflow.SUSPEND)

    private var _probe: Probe = Probe.create(mac = "")

    override suspend fun emit(probe: Probe) = _probeFlow.emit(probe)
    override suspend fun emit(probeStatus: ProbeStatus) = _probeStatusFlow.emit(probeStatus)
    override suspend fun emit(logResponse: LogResponse) = _logResponseFlow.emit(logResponse)

    override val probeFlow = _probeFlow.asSharedFlow()
    override val probeStatusFlow = _probeStatusFlow.asSharedFlow()
    override val logResponseFlow = _logResponseFlow.asSharedFlow()
    override val probe: Probe get() = update()

    private fun update(): Probe {
        TODO()
    }
}