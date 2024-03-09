/*
 * Project: Combustion Inc. Android Framework
 * File: ProximityDevice.kt
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

package inc.combustion.framework.ble.device

import inc.combustion.framework.service.Ewma
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Class specifically set up to produce smoothed RSSI values for a given probe.
 */
internal class ProximityDevice {
    companion object {
        private const val MIN_RSSI = -128.0
    }

    // holds the current smoothed RSSI value for this probe
    private var _probeSmoothedRssi = MutableStateFlow<Double?>(null)

    // the flow that is consumed to get smoothed RSSI updates
    val probeSmoothedRssiFlow = _probeSmoothedRssi.asStateFlow()

    fun handleRssiUpdate(rssi: Int) {
        // Update smoothed rssi flow
        _probeSmoothedRssi.value = getSmoothedRssi(rssi)
    }

    private val rssiEwma = Ewma(span = 6u)

    private fun getSmoothedRssi(rssi: Int): Double? {
        // Ignore unreasonable values
        if (rssi > 0) {
            return rssiEwma.value
        }

        if (rssi <= MIN_RSSI) {
            rssiEwma.reset()
        } else {
            rssiEwma.put(rssi.toDouble())
        }

        return rssiEwma.value
    }
}