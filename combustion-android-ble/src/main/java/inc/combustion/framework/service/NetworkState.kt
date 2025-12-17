/*
 * Project: Combustion Inc. Android Example
 * File: NetworkState.kt
 * Author: https://github.com/miwright2
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

/**
 * Enumerates the current network state
 */
data class NetworkState(
    val bluetoothAvailability: BluetoothAvailability = BluetoothAvailability.AdapterOff,
    val scanningOn: Boolean = false,
    val dfuModeOn: Boolean = false
) {
    val bluetoothReady: Boolean
        get() = bluetoothAvailability is BluetoothAvailability.Available

    @Deprecated(
        message = "bluetoothOn is deprecated, use bluetoothReady or bluetoothAvailability instead.",
        level = DeprecationLevel.WARNING,
    )
    val bluetoothOn: Boolean
        get() = bluetoothAvailability !is BluetoothAvailability.AdapterOff
}

sealed interface BluetoothAvailability {
    object Available : BluetoothAvailability
    object AdapterOff : BluetoothAvailability
    object LocationOff : BluetoothAvailability   // API ≤ 30 only

    companion object {
        fun computeBluetoothAvailability(
            bluetoothAdapterEnabled: Boolean,
            locationServiceRequiredForBluetooth: Boolean,
            locationServiceEnabled: Boolean,
        ): BluetoothAvailability {
            return when {
                !bluetoothAdapterEnabled -> AdapterOff

                locationServiceRequiredForBluetooth && !locationServiceEnabled ->
                    LocationOff

                else -> Available
            }
        }
    }
}