/*
 * Project: Combustion Inc. Android Framework
 * File: DeviceScanner.kt
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
package inc.combustion.framework.ble

import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.util.Log
import androidx.lifecycle.*
import com.juul.kable.Filter
import com.juul.kable.Scanner
import inc.combustion.framework.LOG_TAG
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton class responsible for BLE scanning of devices and initial processing
 * of advertising packets.
 */
internal class LegacyDeviceScanner private constructor() {

    companion object {

        // NOTE: This duplicates what is in ProbeScanner, but this class should get deleted soon.
        val NEEDLE_SERVICE_UUID: ParcelUuid = ParcelUuid.fromString(
            "00000100-CAAB-3792-3D44-97AE51C1407A"
        )

        private var probeAllMatchesScanJob: Job? = null
        private val isProbeScanning = AtomicBoolean(false)
        private val _probeAdvertisements = MutableSharedFlow<LegacyProbeAdvertisingData>()

        val probeAdvertisements = _probeAdvertisements.asSharedFlow()

        private val probeAllMatchesScanner = Scanner {
            filters = listOf(Filter.Service(NEEDLE_SERVICE_UUID.uuid))
            scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()
        }

        val isScanningForProbes: Boolean
            get() = isProbeScanning.get()

        fun startProbeScanning(owner: LifecycleOwner) {
            if(!isProbeScanning.getAndSet(true)) {
                probeAllMatchesScanJob = owner.lifecycleScope.launch {
                    // launches the block in a new coroutine every time the LifecycleOwner is
                    // in the CREATED state or above, and cancels the block when the LifecycleOwner
                    // is destroyed
                   owner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                       Log.d(LOG_TAG, "ProbeManager Scanning Started ...")
                       // filter and collect on incoming BLE advertisements
                       probeAllMatchesScanner
                           .advertisements
                           .catch { cause ->
                               stopProbeScanning()
                               isProbeScanning.set(false)
                               Log.e(LOG_TAG, "ProbeManager Scan Error: ${cause.localizedMessage}")
                               Log.e(LOG_TAG, Log.getStackTraceString(cause))
                           }
                           .onCompletion {
                               Log.d(LOG_TAG, "ProbeManager scanning stopped ...")
                               isProbeScanning.set(false)
                           }
                           .collect { advertisement ->
                               LegacyProbeAdvertisingData.fromAdvertisement(advertisement)?.let {
                                   _probeAdvertisements.emit(it)
                               }
                           }
                   }
                }
            }
        }

        fun stopProbeScanning() {
            probeAllMatchesScanJob?.cancelChildren()
            probeAllMatchesScanJob = null
        }
    }
}