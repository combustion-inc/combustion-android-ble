/*
 * Project: Combustion Inc. Android Framework
 * File: DeviceConnectionState.kt
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
 * Enumerates device connection state.
 */
enum class DeviceConnectionState {
    /**
     * Device is out of range.  Advertising packets not currently being received.
     */
    OUT_OF_RANGE,
    /**
     * Advertising packets are being received.  Device is in range.  Device is connectable.
     */
    ADVERTISING_CONNECTABLE,
    /**
     * Advertising packets are being received.  Device is in range.  Device is NOT connectable.
     */
    ADVERTISING_NOT_CONNECTABLE,
    /**
     * Actively attempting to establish a BLE connection to a device.
     */
    CONNECTING,
    /**
     * Device is currently connected.  Device is in range.
     */
    CONNECTED,
    /**
     * Actively disconnecting a previously established device connection.
     */
    DISCONNECTING,
    /**
     * Device is currently disconnected.  Have not yet received an advertising packet
     * or determined that the device is out of range.
     */
    DISCONNECTED,
    /**
     * Connected to a repeater device, but not able to find a route to the destination endpoint.
     */
    NO_ROUTE;

    override fun toString(): String {
        return when(this) {
            OUT_OF_RANGE -> "Out of Range"
            ADVERTISING_CONNECTABLE -> "Connectable"
            ADVERTISING_NOT_CONNECTABLE -> "Not Connectable"
            CONNECTING -> "Connecting"
            CONNECTED -> "Connected"
            DISCONNECTING -> "Disconnecting"
            DISCONNECTED -> "Disconnected"
            NO_ROUTE -> "No Route"
        }
    }
}