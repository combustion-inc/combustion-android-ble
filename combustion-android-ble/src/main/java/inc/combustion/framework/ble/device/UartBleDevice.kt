/*
 * Project: Combustion Inc. Android Framework
 * File: UartBleDevice.kt
 * Author: http://github.com/miwright2
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
@file:OptIn(ExperimentalUuidApi::class, ExperimentalApi::class)

package inc.combustion.framework.ble.device

import android.bluetooth.BluetoothAdapter
import android.util.Log
import com.juul.kable.ExperimentalApi
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.scanning.DeviceAdvertisingData
import inc.combustion.framework.service.DebugSettings
import inc.combustion.framework.service.utils.ConcurrentSnapshotMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal open class UartBleDevice(
    mac: String,
    advertisement: DeviceAdvertisingData,
    scope: CoroutineScope,
    adapter: BluetoothAdapter
) : DeviceInformationBleDevice(mac, advertisement, scope, adapter) {

    /**
     * Tracks pending completions for one message type, keyed by request ID (`null` representing
     * "no specific request ID"). Unlike a single-slot handler, more than one request ID can be
     * pending at once -- needed so a future retry (which may re-send with a fresh request ID
     * before an earlier attempt's timeout has elapsed) doesn't have to cancel/discard the earlier
     * attempt just to register the new one. A `null`-keyed wait still behaves as a single slot,
     * since a map key can only ever hold one entry -- matching the original handler's behavior for
     * request-ID-less waits, where only one could ever be pending at a time.
     *
     * One deliberate behavior change from the previous single-slot handler: previously, calling
     * [wait] again for a *different* request ID while one was already pending would silently
     * cancel and discard the earlier wait (without notifying its caller) and take over. Now, a
     * different request ID is simply tracked alongside the existing one -- both remain pending
     * independently, each completed or timed out on its own. Calling [wait] again for the *same*
     * request ID (including `null`) while it's already pending is still rejected immediately with
     * `callback(false, null)`, same as before.
     *
     * A second, easier-to-miss behavior change: the previous handler treated a `null`-registered
     * wait as a wildcard -- if it wasn't currently waiting on a specific request ID, [handled]
     * would match (and complete) *any* incoming `reqId`, null or not. The keyed [pending] map has
     * no such wildcard: `Key(null)` only ever matches [handled] being called with `reqId = null`.
     * This is safe today because every `send*` caller that can reach a request-ID-bearing message
     * type (MeatNet/`NodeRequest`-backed sends) always passes a real, non-null ID -- but it's a
     * live trap for a future caller that doesn't: `NodeRequest` substitutes its own random ID onto
     * the wire when constructed with `requestId = null` (see `NodeRequest.requestId`), so
     * `wait(reqId = null, ...)` paired with such a send would register `Key(null)` while the
     * actual response comes back carrying that random, non-null ID -- the two can never match, and
     * the wait would silently run out its own timeout instead of ever completing, indistinguishable
     * from a genuinely dropped response. If you need a `send*`/`wait` pair to remain correct
     * whether or not its caller passes a real ID, build the outgoing request first and pass back
     * its *resolved* `requestId` to [wait] -- see `NodeBleDevice.sendNodeRequest` for the pattern.
     */
    class MessageCompletionHandler {
        // ConcurrentSnapshotMap requires non-null keys (it's backed by ConcurrentHashMap), so the
        // nullable reqId is wrapped in a small non-null key type rather than represented with a
        // sentinel value that could theoretically collide with a real request ID.
        private data class Key(val reqId: UInt?)

        private class Entry(val callback: ((Boolean, Any?) -> Unit)?) {
            var job: Job? = null
        }

        private val pending = ConcurrentSnapshotMap<Key, Entry>()

        // Reflects the most recently registered request ID for callers that inspect this after
        // wait() -- not a reliable "the" pending request ID once more than one can be pending at
        // once, but no existing caller relies on it for anything beyond that.
        var requestId: UInt? = null
            private set

        val isWaiting: Boolean
            get() = !pending.isEmpty()

        // Returns true if a pending wait for this reqId was found (and, if it had a callback,
        // the callback was invoked).
        fun handled(result: Boolean, data: Any?, reqId: UInt? = null): Boolean {
            // Remove before invoking the callback (not after) so a re-entrant wait() call from
            // within the callback -- e.g. an immediate retry -- isn't rejected by a stale entry
            // that's about to be cleaned up anyway.
            val entry = pending.remove(Key(reqId))
            if (entry == null) {
                // Worth flagging: a non-null reqId with no matching wait can mean a response
                // simply arrived after its own wait already timed out (harmless, if slow) -- but
                // it's also exactly what a Key(null)/random-wire-id mismatch looks like (see this
                // class's KDoc), which otherwise fails silently as an indistinguishable timeout.
                if (reqId != null) {
                    Log.w(LOG_TAG, "MessageCompletionHandler.handled: no pending wait for reqId $reqId")
                }
                return false
            }
            entry.job?.cancel()
            entry.callback?.let { it(result, data) }
            return true
        }

        fun wait(
            scope: CoroutineScope,
            duration: Long,
            reqId: UInt? = null,
            callback: ((Boolean, Any?) -> Unit)?,
        ) {
            val key = Key(reqId)
            val entry = Entry(callback)
            // Atomically check-and-insert: if a wait for this exact request ID (including null)
            // is already pending, reject the new one -- matching the original handler's rejection
            // behavior for a repeated wait on the same key.
            if (pending.putIfAbsent(key, entry) != null) {
                callback?.let { it(false, null) }
                return
            }

            requestId = reqId
            entry.job = scope.launch {
                delay(duration)
                if (pending.remove(key, entry)) {
                    withContext(Dispatchers.Main) {
                        callback?.let { it(false, null) }
                    }
                }
            }
        }

        fun cancel() {
            pending.snapshotEntries().forEach { (key, entry) ->
                if (pending.remove(key, entry)) {
                    entry.job?.cancel()
                }
            }
            requestId = null
        }
    }

    companion object {
        val UART_TX_CHARACTERISTIC = characteristicOf(
            service = Uuid.parse("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
            characteristic = Uuid.parse("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
        )
        val UART_RX_CHARACTERISTIC = characteristicOf(
            service = Uuid.parse("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
            characteristic = Uuid.parse("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
        )
    }

    var isInDfuMode: Boolean = false

    fun writeUartCharacteristic(data: ByteArray) {
        if (isConnected.get()) {
            scope.launch(Dispatchers.IO) {
                try {
                    peripheral.write(UART_RX_CHARACTERISTIC, data, WriteType.WithoutResponse)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "UART-TX: Unable to write to RX characteristic.", e)
                }
            }
        }
    }

    suspend fun observeUartCharacteristic(callback: (suspend (data: UByteArray) -> Unit)?) {
        peripheral.observe(UART_TX_CHARACTERISTIC)
            .onCompletion {
                if (DebugSettings.DEBUG_LOG_BLE_UART_IO) {
                    Log.d(LOG_TAG, "UART-TX Monitor Done")
                }
            }
            .catch { exception ->
                Log.i(LOG_TAG, "UART-TX Monitor Catch: $exception")
            }
            .collect { data ->
                if (DebugSettings.DEBUG_LOG_BLE_UART_IO) {
                    val packet = data.toUByteArray().joinToString("") { ubyte ->
                        ubyte.toString(16).padStart(2, '0').uppercase()
                    }
                    Log.d(LOG_TAG, "UART-RX: $packet")
                }

                callback?.let {
                    it(data.toUByteArray())
                }
            }
    }
}