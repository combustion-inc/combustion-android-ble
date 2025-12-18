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
    val scanningRequested: Boolean = false,
    val bluetoothAvailability: BluetoothAvailability = BluetoothAvailability.AdapterOff,
    val scanningPurpose: ScanningPurpose = ScanningPurpose.Idle,
) {
    val bluetoothReady: Boolean
        get() = bluetoothAvailability is BluetoothAvailability.Available
    
    @Deprecated(
        message = "Bluetooth Adapter is enabled but bluetooth is not necessarily ready. Rather use bluetoothReady or bluetoothAdapterEnabled",
        level = DeprecationLevel.WARNING,
    )
    val bluetoothOn: Boolean
        get() = bluetoothAdapterEnabled

    val bluetoothAdapterEnabled: Boolean
        get() = bluetoothAvailability !is BluetoothAvailability.AdapterOff

    /**
     * API ≤ 30 only
     */
    val locationServiceEnabled: Boolean
        get() = bluetoothAvailability !is BluetoothAvailability.LocationOff

    @Deprecated(
        message = "scanningOn is deprecated, use scanningMode instead.",
        level = DeprecationLevel.WARNING,
    )
    val scanningOn: Boolean // Scanning for devices
        get() = (scanningPurpose == ScanningPurpose.DeviceDiscovery) && bluetoothReady && scanningRequested

    val dfuModeOn: Boolean
        get() = scanningPurpose == ScanningPurpose.Dfu
}

sealed interface BluetoothAvailability {
    data object Available : BluetoothAvailability
    data object AdapterOff : BluetoothAvailability
    data object LocationOff : BluetoothAvailability   // API ≤ 30 only

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

sealed interface ScanningPurpose {

    /** No interpretation of advertisements */
    data object Idle : ScanningPurpose

    /** Interpret advertisements and discover devices */
    data object DeviceDiscovery : ScanningPurpose

    /** DFU is active; advertisements are used only for DFU */
    data object Dfu : ScanningPurpose
}