/*
 * Project: Combustion Inc. Android Framework
 * File: CommandCoordinator.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2026. Combustion Inc.
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

import android.util.Log
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.CommandCoordinator.Companion.valueConfirmation
import inc.combustion.framework.ble.device.DeviceID
import inc.combustion.framework.ble.uart.MessageType
import inc.combustion.framework.ble.uart.meatnet.NodeMessage
import inc.combustion.framework.ble.uart.meatnet.NodeMessageType
import inc.combustion.framework.service.utils.ConcurrentSnapshotMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Outcome of a coordinated command. See [CommandCoordinator].
 */
internal enum class CommandResult {
    SUCCESS,
    FAILURE,
}

/**
 * Result of reading a field out of a status update, for [CommandCoordinator.valueConfirmation].
 *
 * A plain `T?` return can't distinguish "this status doesn't carry the field at all" (e.g. an
 * advertising-only status, or the wrong [SpecializedDeviceStatus] subtype) from "this status does
 * carry the field, and its actual value is null" (e.g. [EngineStatus.controlSerialNumber] when no
 * control device is set) -- both collapse to the same `null`. [Absent] and [Present] keep those
 * two cases distinct, since [CommandCoordinator.valueConfirmation] treats them differently: an
 * absent field never confirms, but a present-and-null value can legitimately equal a
 * `commandedValue` of `null`.
 */
internal sealed class ExtractedValue<out T> {
    data class Present<T>(val value: T) : ExtractedValue<T>()
    data object Absent : ExtractedValue<Nothing>()
}

/**
 * Convenience for building an `extractValue` lambda (for [CommandCoordinator.valueConfirmation]):
 * casts [this] to [S], returning [ExtractedValue.Absent] if it isn't one, otherwise
 * [ExtractedValue.Present] wrapping [select]'s result.
 */
internal inline fun <reified S : SpecializedDeviceStatus, T> SpecializedDeviceStatus.extractedAs(
    select: (S) -> T,
): ExtractedValue<T> =
    (this as? S)?.let { ExtractedValue.Present(select(it)) } ?: ExtractedValue.Absent

/**
 * Convenience for building a [CommandCoordinator.valueConfirmation] `startingValue` out of a plain
 * nullable field, for the common case where `null` can only ever mean "never observed" and never a
 * legitimate confirmed value -- see [CommandCoordinator.valueConfirmation]'s KDoc. Not appropriate
 * for a field (like [EngineStatus.controlSerialNumber]) where a confirmed `null` is possible; build
 * an [ExtractedValue] for those directly instead, gated on whatever signals a real observation
 * (e.g. `Engine.hasReceivedStatus`).
 */
internal fun <T> T?.toExtractedValue(): ExtractedValue<T> =
    this?.let { ExtractedValue.Present(it) } ?: ExtractedValue.Absent

/**
 * Identifies a single transport attempt for a command response.
 *
 * A command sent via [CommandCoordinator.sendRoutedCommand] can be retried over a different
 * transport than its first attempt (e.g. direct BLE, then MeatNet on a later retry). Each attempt
 * registers the key(s) that should complete it if a matching response arrives, so a response for
 * *any* prior attempt -- not just the most recent one -- can still complete the command.
 */
internal sealed class CommandAttemptKey {
    /** A response expected directly from the device at [deviceId]. */
    data class Direct(val messageType: MessageType, val deviceId: DeviceID) : CommandAttemptKey()

    /**
     * A response expected from a MeatNet node, matched by the request ID it was sent with.
     *
     * [messageType] is typed as the open [NodeMessage] interface, not the closed
     * [NodeMessageType] enum, so this also covers generic/vendor node requests
     * ([WiFiNodesManager.sendNodeRequestRequiringWiFi]), whose message ID isn't one of the
     * framework's own known [NodeMessageType] values.
     */
    data class Node(val messageType: NodeMessage, val requestId: UInt) : CommandAttemptKey()
}

/**
 * Coordinates a single BLE command's lifetime: retry, completion, and cleanup.
 *
 * Each of [EngineManager], [GaugeManager], [ProbeManager], and [WiFiNodesManager] owns its own
 * instance and calls [sendRoutedCommand] from its command functions
 * (`EngineManager.setControlDevice`/`setTemperatureSetPoint`,
 * `GaugeManager.setHighLowAlarmStatus`, `ProbeManager.setPowerMode`/`setProbeHighLowAlarmStatus`/
 * `setProbeColor`/`setProbeID`/`setPrediction`/`configureFoodSafe`,
 * `WiFiNodesManager.sendNodeRequestRequiringWiFi`).
 * The first four managers call [confirmCommandStatus] from their status-handling path, so most of
 * their commands can complete early from a status update alone. [WiFiNodesManager]'s generic
 * requests are the one exception -- they have no corresponding status field at all, so they
 * always pass `isConfirmed = { false }` and rely purely on a matching response.
 *
 * Not every command that's structurally portable is worth porting: `ProbeManager.resetFoodSafe`/
 * `resetProbe` were deliberately left as single-shot sends (see their own KDocs) because a
 * spurious retry after a lost ACK -- not a real failure -- could re-execute a command whose
 * effects aren't purely idempotent, at a real (if usually small) cost.
 *
 * `ProbeManager`'s direct-link (non-MeatNet) commands are a special case worth knowing about
 * before touching them: [ProbeBleDevice]'s raw UART response processing always matches a response
 * against a fixed null key, unlike Engine/Gauge/MeatNet's real per-attempt request IDs -- see
 * `ProbeManager.setPowerMode`'s KDoc and `ProbeManager.PROBE_DIRECT_RETRY_INTERVAL_MS` for the
 * consequences that has for both key construction and retry timing.
 *
 * Unlike iOS's `CommandCoordinator` (a manually timer-polled state machine, needed because GCD has
 * no structured concurrency), this relies on plain coroutine suspension: [sendRoutedCommand] is a
 * single suspend function whose retry loop and timeout are expressed directly with
 * `withTimeout`/`withTimeoutOrNull`, and ordinary coroutine cancellation (the caller's coroutine
 * being cancelled) cleans up registered attempt keys via `finally`, with no separate cancel-handle
 * API needed.
 */
internal class CommandCoordinator(
    private val requestTimeoutMs: Long = REQUEST_TIMEOUT_MS,
    private val retryIntervalMs: Long = RETRY_INTERVAL_MS,
) {
    companion object {
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val RETRY_INTERVAL_MS = 5_000L

        /**
         * Builds an `isConfirmed` predicate (for [sendRoutedCommand]) that completes the command
         * as SUCCESS as soon as either:
         *  1. the observed value equals [commandedValue] -- the command's own effect was
         *     confirmed, or
         *  2. the observed value differs from [startingValue] at all, even if it doesn't match
         *     [commandedValue] -- something else already changed it, most plausibly a competing
         *     command from another device targeting the same value. Retrying our own command
         *     against that outcome would just fight it, potentially bouncing indefinitely between
         *     two competing commands each re-asserting their own value. Treating "changed at all"
         *     as good enough to stop retrying avoids that.
         *
         * Returns false (not confirmed) if [extractValue] reports [ExtractedValue.Absent] for a
         * given status (e.g. an advertising-only status missing full status fields) -- an absent
         * value should never be treated as a coincidental match for either condition. This is
         * distinct from [ExtractedValue.Present] wrapping a `null` value, which is a legitimate
         * observation (see [ExtractedValue]'s KDoc) and is compared normally.
         *
         * [startingValue] is itself an [ExtractedValue] for the same reason: [ExtractedValue.Absent]
         * -- e.g. a field that's only ever populated from a full status and the device hasn't sent
         * one yet -- means there's no known prior value, so branch 2 is skipped and only an exact
         * match against [commandedValue] confirms; the anti-bouncing rationale for "changed at all"
         * only holds when there was a known prior value to change from, and treating an *unknown*
         * starting value as if it were a known `null` would let the first status that happens to
         * carry any value at all spuriously satisfy `observed != startingValue`. A confirmed
         * [ExtractedValue.Present] -- including one wrapping `null`, e.g.
         * [EngineStatus.controlSerialNumber] confirmed absent -- is a known prior value, so branch 2
         * applies normally. Most callers can build this with [toExtractedValue].
         *
         * @param startingValue The value observed before this command was sent, or
         * [ExtractedValue.Absent] if no confirmed observation exists yet.
         * @param commandedValue The value this command is trying to set.
         * @param extractValue Reads the relevant value out of a device status -- typically built
         * with [extractedAs] -- or [ExtractedValue.Absent] if this particular status doesn't carry
         * it at all.
         */
        fun <T> valueConfirmation(
            startingValue: ExtractedValue<T>,
            commandedValue: T,
            extractValue: (SpecializedDeviceStatus) -> ExtractedValue<T>,
        ): (SpecializedDeviceStatus) -> Boolean = confirmed@{ status ->
            val observed = when (val extracted = extractValue(status)) {
                is ExtractedValue.Absent -> return@confirmed false
                is ExtractedValue.Present -> extracted.value
            }
            when (startingValue) {
                is ExtractedValue.Absent -> observed == commandedValue
                is ExtractedValue.Present -> observed == commandedValue || observed != startingValue.value
            }
        }
    }

    private class PendingCommand(
        val deferred: CompletableDeferred<CommandResult>,
        val targetSerialNumber: String,
        val isConfirmed: (SpecializedDeviceStatus) -> Boolean,
        var attemptKeys: Set<CommandAttemptKey>,
        var attemptCount: Int = 0,
    )

    // Keyed by attempt key, not by request "slot" -- multiple concurrent, distinct commands per
    // device are possible, and a single command accumulates one entry per key across retries that
    // switch transport. The same PendingCommand instance may be the value for several keys at
    // once. Only ever contains entries for commands whose `send` has returned at least one key --
    // see allPendingCommands for why that's not sufficient on its own.
    private val pendingByAttemptKey = ConcurrentSnapshotMap<CommandAttemptKey, PendingCommand>()

    // Every currently in-flight sendRoutedCommand call, independent of whether it currently has
    // any registered attempt keys. Needed because confirmCommandStatus must be able to complete a
    // command purely from an observed status even when `send` has never registered a key at all
    // (e.g. nothing is currently connected/reachable) -- pendingByAttemptKey alone can't answer
    // "what's pending for this serial number" in that case, since it would have no entry for the
    // command at all. Uses PendingCommand's reference identity as the key (plain class, no
    // structural equals/hashCode) -- this is a concurrent set, the Unit values are unused.
    private val allPendingCommands = ConcurrentSnapshotMap<PendingCommand, Unit>()

    /**
     * Sends a command over a route chosen -- and re-chosen on every retry -- by [send], completing
     * when a matching response arrives (see [completeAttempt]), a device status confirms the
     * requested change (see [confirmCommandStatus]), the overall timeout elapses, or the calling
     * coroutine is cancelled.
     *
     * @param targetSerialNumber Device the command targets; used to route [confirmCommandStatus]
     * confirmations to the right pending command.
     * @param send Selects the current best transport, performs the write, and returns the attempt
     * key(s) that should complete this attempt. Called once immediately, then again on every retry
     * while [retriesEnabled] and no completion has arrived; passed the 1-based number of this
     * attempt (1 for the initial send, 2 for the first retry, etc.) for logging/diagnostics.
     * @param isConfirmed Predicate over a device status update that, if true, completes this
     * command as [CommandResult.SUCCESS] even if no response packet ever arrives. For "set field
     * X to value Y" commands, prefer building this with [valueConfirmation] rather than a plain
     * equality check against the commanded value alone, so a competing command that already
     * changed the value to something else doesn't cause indefinite retry-bouncing between the two.
     * @param retriesEnabled Whether [send] should be retried every [retryIntervalMs] until
     * [requestTimeoutMs] elapses; when false, [send] is called once and the command waits out the
     * full timeout for either completion source.
     */
    suspend fun sendRoutedCommand(
        targetSerialNumber: String,
        send: (attemptNumber: Int) -> Set<CommandAttemptKey>,
        isConfirmed: (SpecializedDeviceStatus) -> Boolean,
        retriesEnabled: Boolean = true,
    ): CommandResult {
        val pendingCommand = PendingCommand(
            deferred = CompletableDeferred(),
            targetSerialNumber = targetSerialNumber,
            isConfirmed = isConfirmed,
            attemptKeys = emptySet(),
        )
        allPendingCommands[pendingCommand] = Unit

        fun sendAttempt(): Set<CommandAttemptKey> {
            pendingCommand.attemptCount++
            Log.d(
                LOG_TAG,
                "CommandCoordinator: attempt ${pendingCommand.attemptCount} to $targetSerialNumber",
            )
            return send(pendingCommand.attemptCount)
        }

        return try {
            withTimeout(requestTimeoutMs) {
                registerAttempt(pendingCommand, sendAttempt())
                if (!retriesEnabled) {
                    // Nothing left to retry -- a single wait for the remainder of the outer
                    // timeout, rather than polling in retryIntervalMs slices for no reason.
                    return@withTimeout pendingCommand.deferred.await()
                }
                while (true) {
                    val result =
                        withTimeoutOrNull(retryIntervalMs) { pendingCommand.deferred.await() }
                    if (result != null) {
                        return@withTimeout result
                    }
                    registerAttempt(pendingCommand, sendAttempt())
                }
                @Suppress("UNREACHABLE_CODE")
                CommandResult.FAILURE
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(LOG_TAG, "sendRoutedCommand TimeoutCancellationException", e)
            CommandResult.FAILURE
        } finally {
            unregister(pendingCommand)
        }
    }

    /**
     * Completes whichever pending command has [key] among its registered attempt keys, if any.
     *
     * @return true if a pending command was found and completed, false if [key] matched nothing
     * (e.g. the command already completed via another route, or timed out).
     */
    fun completeAttempt(key: CommandAttemptKey, success: Boolean): Boolean {
        val pendingCommand = pendingByAttemptKey[key] ?: return false
        pendingCommand.deferred.complete(
            if (success) CommandResult.SUCCESS else CommandResult.FAILURE,
        )
        return true
    }

    /**
     * Completes any pending command targeting [serialNumber] whose confirmation predicate matches
     * [status]. Intended to be called from each manager's status-handling path so a command can
     * complete from a status broadcast even if its response packet is lost.
     */
    fun confirmCommandStatus(serialNumber: String, status: SpecializedDeviceStatus) {
        allPendingCommands.snapshotKeys()
            .asSequence()
            .filter { it.targetSerialNumber == serialNumber }
            .filter { it.isConfirmed(status) }
            .forEach { it.deferred.complete(CommandResult.SUCCESS) }
    }

    /**
     * Purges attempt keys tied to [deviceId] (i.e. [CommandAttemptKey.Direct] keys for that
     * device) so a pending command can still complete via a later retry over a different route,
     * rather than being stuck waiting on a response that can never arrive on a now-disconnected
     * link.
     */
    fun handleDeviceDisconnected(deviceId: DeviceID) {
        pendingByAttemptKey.removeIf { (key, _) ->
            key is CommandAttemptKey.Direct && key.deviceId == deviceId
        }
    }

    private fun registerAttempt(pendingCommand: PendingCommand, newKeys: Set<CommandAttemptKey>) {
        if (newKeys.isEmpty()) return
        pendingCommand.attemptKeys += newKeys
        newKeys.forEach { key -> pendingByAttemptKey[key] = pendingCommand }
    }

    private fun unregister(pendingCommand: PendingCommand) {
        pendingCommand.attemptKeys.forEach { key ->
            pendingByAttemptKey.remove(
                key,
                pendingCommand
            )
        }
        allPendingCommands.remove(pendingCommand)
    }
}
