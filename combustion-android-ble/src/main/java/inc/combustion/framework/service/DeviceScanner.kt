/*
 * Project: Combustion Inc. Android Framework
 * File: DeviceScanner.kt
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

package inc.combustion.framework.service

import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.juul.kable.Filter
import com.juul.kable.Scanner
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.AdvertisingData
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal class DeviceScanner private constructor() {
    companion object {
        private var allMatchesScanJob: Job? = null
        private val _isScanning = AtomicBoolean(false)
        private val _advertisements = MutableSharedFlow<AdvertisingData>()

        // TODO: This is the wrong UUID.
        private const val NORDIC_DFU_SERVICE_UUID_STRING = "0000180A-0000-1000-8000-0080BEEFBEEF"
        private val NORDIC_DFU_SERVICE_UUID: ParcelUuid = ParcelUuid.fromString(
            NORDIC_DFU_SERVICE_UUID_STRING
        )

        val advertisements = _advertisements.asSharedFlow()

        private val allMatchesScanner = Scanner {
            filters = listOf(Filter.Service(NORDIC_DFU_SERVICE_UUID.uuid))
            scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()
        }

        val isScanning: Boolean
            get() = _isScanning.get()

        fun startScanning(owner: LifecycleOwner) {
            if(!_isScanning.getAndSet(true)) {
                allMatchesScanJob = owner.lifecycleScope.launch {
                    // launches the block in a new coroutine every time the LifecycleOwner is
                    // in the CREATED state or above, and cancels the block when the LifecycleOwner
                    // is destroyed
                    owner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                        Log.d(LOG_TAG, "Scanning Started ...")
                        // filter and collect on incoming BLE advertisements
                        allMatchesScanner
                            .advertisements
                            .catch { cause ->
                                stopScanning()
                                _isScanning.set(false)
                                Log.e(LOG_TAG, "Scan Error: ${cause.localizedMessage}")
                                Log.e(LOG_TAG, Log.getStackTraceString(cause))
                            }
                            .onCompletion {
                                Log.d(LOG_TAG, "Scanning stopped ...")
                                _isScanning.set(false)
                            }
                            .collect { advertisement ->
                                // This would maybe be an abstract function that lets subclasses
                                // parse advertisement data and emit into flow as they see fit
                                AdvertisingData.fromAdvertisement(advertisement)?.let {
                                    _advertisements.emit(it)
                                }
                            }
                    }
                }
            }
        }

        fun stopScanning() {
            allMatchesScanJob?.cancelChildren()
            allMatchesScanJob = null
        }
    }
}
