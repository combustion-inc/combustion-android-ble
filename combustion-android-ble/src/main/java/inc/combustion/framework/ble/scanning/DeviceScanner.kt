/*
 * Project: Combustion Inc. Android Framework
 * File: DeviceScanner.kt
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
package inc.combustion.framework.ble.scanning

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal class DeviceScanner private constructor() {
    companion object {
        private const val NORDIC_DFU_SERVICE_UUID_STRING = "0000FE59-0000-1000-8000-00805F9B34FB"
        private val NORDIC_DFU_SERVICE_UUID: ParcelUuid = ParcelUuid.fromString(
            NORDIC_DFU_SERVICE_UUID_STRING
        )

        private var allMatchesScanJob: Job? = null
        private val atomicIsScanning = AtomicBoolean(false)
        private val allMatchesScanner = Scanner {
            filters = listOf(Filter.Service(NORDIC_DFU_SERVICE_UUID.uuid))
            scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()
        }

        val isScanning: Boolean
            get() = atomicIsScanning.get()

        private val mutableAdvertisements = MutableSharedFlow<CombustionAdvertisingData>(5, 10, BufferOverflow.DROP_OLDEST)
        val advertisements = mutableAdvertisements.asSharedFlow()

        fun scan(owner: LifecycleOwner) {
            if(!atomicIsScanning.getAndSet(true)) {
                allMatchesScanJob = owner.lifecycleScope.launch(Dispatchers.IO) {
                    // launches the block in a new coroutine every time the LifecycleOwner is
                    // in the CREATED state or above, and cancels the block when the LifecycleOwner
                    // is destroyed
                    owner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                        Log.d(LOG_TAG, "Scanning Started ...")
                        // filter and collect on incoming BLE advertisements
                        allMatchesScanner
                            .advertisements
                            .buffer(capacity = 5, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                            .flowOn(Dispatchers.IO)
                            .catch { cause ->
                                stop()
                                atomicIsScanning.set(false)
                                Log.e(LOG_TAG, "Scan Error: ${cause.localizedMessage}")
                                Log.e(LOG_TAG, Log.getStackTraceString(cause))
                            }
                            .onCompletion {
                                Log.d(LOG_TAG, "Scanning stopped ...")
                                atomicIsScanning.set(false)
                            }
                            .collect { advertisement ->
                                when (val advertisingData = BaseAdvertisingData.create(advertisement)) {
                                    is CombustionAdvertisingData -> mutableAdvertisements.tryEmit(advertisingData)
                                    // else, if just BaseAdvertisingData, then no need to produce to flow.
                                }
                            }
                    }
                }
            }
        }

        fun stop() {
            allMatchesScanJob?.cancelChildren()
            allMatchesScanJob = null
        }
    }
}
