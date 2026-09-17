/*
 * Project: Combustion Inc. Android Framework
 * File: DeviceIdManager.kt
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
import inc.combustion.framework.service.SpecializedDevice
import inc.combustion.framework.service.utils.StateFlowMutableMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Watches a set of devices' assigned [T] (e.g. [inc.combustion.framework.service.ProbeID] or
 * [inc.combustion.framework.service.GaugeID]) and auto-resolves collisions -- two devices
 * reporting the same ID -- by reassigning one of them, deterministically, without the two devices'
 * managers needing to coordinate directly.
 *
 * Shared by every product type that has this 1-8-ish ID concept; only two things are type-specific
 * and passed in per instance:
 *  - [extractId]: how to read a device's *known* ID out of its status. Returning null here (device
 *    not yet reporting one, or -- for a type like `GaugeID` whose device can be assigned an ID
 *    beyond what the enum models -- reporting one this manager doesn't know about) means the
 *    device doesn't participate in conflict tracking; that is deliberate, not a bug, since only
 *    devices this manager can actually control an outcome for should. If the device previously
 *    had a known id and then starts reporting null (e.g. reassigned to an unmodeled id while
 *    still connected), [addDevice] releases that old assignment rather than leaving it stale.
 *  - [serialNumberWinsOver]: the tie-break for "which of the two colliding devices keeps a low
 *    slot." Returns null if the two serial numbers can't be ranked against each other at all, in
 *    which case the conflict is left unresolved rather than guessing.
 */
internal class DeviceIdManager<T, D : SpecializedDevice>(
    private val logTag: String,
    private val allIds: List<T>,
    private val extractId: (D) -> T?,
    private val serialNumberWinsOver: (candidate: String, other: String) -> Boolean?,
    private val setId: (serialNumber: String, id: T, completionHandler: (Boolean) -> Unit) -> Unit,
    private val scope: CoroutineScope,
    private val knownIdAssignedToDevice: StateFlowMutableMap<T, String> = StateFlowMutableMap(),
) {
    private val idObservations = ConcurrentHashMap<String, Job>()

    val availableIds: Flow<List<T>> =
        knownIdAssignedToDevice.stateFlow.map { map -> allIds.filterNot { it in map } }

    private fun findLowestAvailableId(exceptConflictId: T): T? {
        return allIds.firstOrNull { (knownIdAssignedToDevice[it] == null) && (it != exceptConflictId) }
    }

    private fun removeIdAssignmentsToDevice(serialNumber: String, except: T?): Boolean {
        return knownIdAssignedToDevice.removeIf { it.key != except && it.value == serialNumber }
    }

    private fun resolveIdConflict(
        newDeviceSerial: String,
        currentDeviceSerial: String,
        conflictId: T,
    ) {
        val newDeviceWins = serialNumberWinsOver(newDeviceSerial, currentDeviceSerial)
        if (newDeviceWins == null) {
            Log.v(
                logTag,
                "resolveIdConflict: unable to rank $newDeviceSerial vs $currentDeviceSerial -- cancel resolving conflict on $conflictId",
            )
            return
        }

        val lowestAvailableId = findLowestAvailableId(conflictId)
        if (lowestAvailableId == null) {
            Log.v(
                logTag,
                "resolveIdConflict: no available id found -- cancel resolving conflict on $conflictId",
            )
            return
        }

        val (lowerId, higherId) = if (allIds.indexOf(lowestAvailableId) < allIds.indexOf(conflictId)) {
            Pair(lowestAvailableId, conflictId)
        } else {
            Pair(conflictId, lowestAvailableId)
        }

        // assign the losing (non-winning) serial number to the lowest id
        val (newDeviceId, currentDeviceId) = if (newDeviceWins) {
            Pair(lowerId, higherId)
        } else {
            Pair(higherId, lowerId)
        }

        knownIdAssignedToDevice[newDeviceId] = newDeviceSerial
        knownIdAssignedToDevice[currentDeviceId] = currentDeviceSerial
        Log.v(
            logTag,
            "resolveIdConflict: assign $newDeviceId to $newDeviceSerial and $currentDeviceId to $currentDeviceSerial",
        )
        setId(newDeviceSerial, newDeviceId) { success ->
            if (!success) {
                // revert change
                if (knownIdAssignedToDevice[newDeviceId] == newDeviceSerial) {
                    knownIdAssignedToDevice.remove(newDeviceId)
                }
                Log.v(logTag, "resolveIdConflict: assign $newDeviceId to $newDeviceSerial failed")
            }
        }
        setId(currentDeviceSerial, currentDeviceId) { success ->
            if (!success) {
                // revert change
                if (knownIdAssignedToDevice[currentDeviceId] == currentDeviceSerial) {
                    knownIdAssignedToDevice.remove(currentDeviceId)
                }
                Log.v(
                    logTag,
                    "resolveIdConflict: assign $currentDeviceId to $currentDeviceSerial failed",
                )
            }
        }
    }

    fun hasIdConflict(serialNumber: String, id: T): Boolean {
        val currentDeviceForId = knownIdAssignedToDevice[id]
        return (currentDeviceForId != null) && (currentDeviceForId != serialNumber)
    }

    fun addDevice(serialNumber: String, deviceFlow: Flow<D>) {
        idObservations.remove(serialNumber)?.cancel()
        idObservations[serialNumber] = scope.launch {
            deviceFlow
                .filter { !it.isPlaceholder() }
                .map { extractId(it) }
                .distinctUntilChanged()
                .collect { id ->
                    if (id == null) {
                        // The device no longer reports a known id -- e.g. it was reassigned to
                        // one this manager doesn't model (see extractId's KDoc) -- so release
                        // whatever slot it previously held instead of leaving a stale assignment
                        // that nothing would otherwise ever clear.
                        if (removeIdAssignmentsToDevice(serialNumber, except = null)) {
                            Log.v(logTag, "id no longer known for $serialNumber -- released its assignment")
                        }
                        return@collect
                    }

                    Log.v(logTag, "Detect id $id is assigned to $serialNumber")
                    removeIdAssignmentsToDevice(serialNumber, except = id)
                    val currentDeviceForId = knownIdAssignedToDevice[id]
                    if ((currentDeviceForId != null) && (currentDeviceForId != serialNumber)) {
                        Log.v(logTag, "Conflict on id $id: $serialNumber and $currentDeviceForId")
                        resolveIdConflict(
                            newDeviceSerial = serialNumber,
                            currentDeviceSerial = currentDeviceForId,
                            conflictId = id,
                        )
                    } else {
                        knownIdAssignedToDevice[id] = serialNumber
                    }
                }
        }
    }

    fun removeDevice(serialNumber: String) {
        Log.v(logTag, "removeDevice $serialNumber")
        idObservations.remove(serialNumber)?.cancel()
        removeIdAssignmentsToDevice(serialNumber, except = null)
    }

    fun clear() {
        Log.v(logTag, "clear")
        idObservations.values.toList().forEach { it.cancel() }
        idObservations.clear()
        knownIdAssignedToDevice.clear()
    }
}
