/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeDiscoveredEvent.kt
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

/**
 * Enumerates the asynchronous events that can be collected while the device is
 * scanning and producing events to the discovered probes flow.
 *
 * @see DeviceManager.discoveredProbesFlow
 *
 * TODO: This should probably have a more accurate name.
 */
sealed class ProbeDiscoveredEvent {
    /**
     * Combustion device discovered
     *
     * @property serialNumber serial number of the discovered device
     */
    data class ProbeDiscovered(
        val serialNumber: String
    ) : ProbeDiscoveredEvent()

    /**
     * Combustion device with serial number [serialNumber] was removed.
     */
    data class ProbeRemoved(
        val serialNumber: String
    ) : ProbeDiscoveredEvent()

    /**
     * The device cache was cleared.
     */
    object DevicesCleared: ProbeDiscoveredEvent()
}
