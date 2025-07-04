/*
 * Project: Combustion Inc. Android Framework
 * File: DeviceDiscoveredEvent.kt
 * Author: Nick Helseth <nick@combustion.inc>
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

package inc.combustion.framework.service

sealed class DeviceInProximityEvent {
    /**
     * Combustion probe discovered with serial number [serialNumber].
     */
    data class ProbeDiscovered(
        val serialNumber: String
    ) : DeviceInProximityEvent()

    /**
     * Combustion gauge discovered with serial number [serialNumber].
     */
    data class GaugeDiscovered(
        val serialNumber: String
    ) : DeviceInProximityEvent()

    /**
     * Combustion node discovered with serial number [serialNumber].
     */
    data class NodeDiscovered(
        val serialNumber: String
    ) : DeviceInProximityEvent()

    /**
     * The device cache was cleared.
     */
    data object DevicesCleared: DeviceInProximityEvent()
}

@Deprecated(
    message = "DeviceDiscoveredEvent is deprecated. Use DeviceProximityEvent instead.",
    replaceWith = ReplaceWith("DeviceProximityEvent"),
    level = DeprecationLevel.WARNING
)
@Suppress("DEPRECATION")
sealed class DeviceDiscoveredEvent {
    /**
     * Combustion probe discovered with serial number [serialNumber].
     */
    data class ProbeDiscovered(
        val serialNumber: String
    ) : DeviceDiscoveredEvent()

    /**
     * Combustion node discovered with serial number [serialNumber].
     */
    data class NodeDiscovered(
        val serialNumber: String
    ) : DeviceDiscoveredEvent()

    /**
     * The device cache was cleared.
     */
    data object DevicesCleared: DeviceDiscoveredEvent()
}