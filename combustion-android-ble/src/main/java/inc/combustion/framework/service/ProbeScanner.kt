/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeScanner.kt
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

import android.bluetooth.le.ScanSettings
import android.util.Log
import androidx.lifecycle.*
import com.juul.kable.Scanner
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.ProbeManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scans for Combustion Probes outside of the Combustion Service.  Caller is responsible for
 * ensuring the Bluetooth is enabled prior to starting the scanner.  This class returns scan
 * results from the provided flow, only for Combustion probes.  This class returns a new
 * scan result for each advertisement received from a probe.
 */
class ProbeScanner private constructor() {

    companion object {
        private var job: Job? = null
        private val scanning = AtomicBoolean(false)
        private val _results = MutableSharedFlow<ProbeScanResult>()

        val scanResults = _results.asSharedFlow()

        private val probeAllMatchesScanner = Scanner {
            services = listOf(ProbeManager.NEEDLE_SERVICE_UUID.uuid)
            scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()
        }

        /**
         * true if currently scanning, false otherwise.
         */
        val isScanning: Boolean
            get() = scanning.get()

        /**
         * Suspending scan call.  The caller is responsible for managing the coroutine
         * used to run this function.  This call and start cannot be used simultaneously.
         * The scan is stopped by cancelling the associated coroutine, managed by the caller.
         */
        suspend fun scan() {
            if(!scanning.getAndSet(true)) {
                probeAllMatchesScanner
                    .advertisements
                    .catch { cause ->
                        stop()
                        scanning.set(false)
                        Log.e(LOG_TAG, "ProbeScanner Error: ${cause.localizedMessage}")
                        Log.e(LOG_TAG, Log.getStackTraceString(cause))
                    }
                    .onCompletion {
                        Log.d(LOG_TAG, "ProbeScanner Complete...")
                        scanning.set(false)
                    }
                    .collect { advertisement ->
                        ProbeScanResult.fromAdvertisement(advertisement).let {
                            it?.let {
                                _results.emit(it)
                            }
                        }
                    }
            }
        }

        /**
         * Starts canning and attached to the passed in LifecycleOwner
         * @param owner owner for the coroutine scope.
         */
        fun start(owner: LifecycleOwner) {
            if(!scanning.getAndSet(true)) {
                job = owner.lifecycleScope.launch {
                    owner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                        scan()
                    }
                }
            }
        }

        /**
         * Stops an active scan that was started using the start call.
         */
        fun stop() {
            job?.cancelChildren()
            job = null
        }
    }
}
