/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeIdManager.kt
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
import inc.combustion.framework.service.ProbeID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private const val LOG_TAG = "ProbeIdManager"

internal class ProbeIdManager(
    private val setProbeID: (serialNumber: String, probeId: ProbeID, completionHandler: (Boolean) -> Unit) -> Unit,
    private val scope: CoroutineScope,
    private val knownProbeIdAssignedToDevice: ConcurrentHashMap<ProbeID, String> = ConcurrentHashMap<ProbeID, String>(),
) {
    private val probeIdObservations = ConcurrentHashMap<String, Job>()

    private fun findLowestAvailableProbeId(exceptConflictId: ProbeID): ProbeID? {
        return ProbeID.entries.firstOrNull {
            (knownProbeIdAssignedToDevice[it] == null) && (it != exceptConflictId)
        }
    }

    private fun removeProbeIdAssignmentsToDevice(serialNumber: String, except: ProbeID?) {
        return ProbeID.entries.forEach {
            if ((it != except) && (knownProbeIdAssignedToDevice[it] == serialNumber)) {
                knownProbeIdAssignedToDevice.remove(it)
            }
        }
    }

    private fun resolveProbeIdConflict(
        newDeviceSerial: String,
        currentDeviceSerial: String,
        conflictId: ProbeID,
    ) {
        val newNumeric = newDeviceSerial.toLongOrNull(16) ?: return
        val currentNumeric = currentDeviceSerial.toLongOrNull(16) ?: return
        val lowestAvailableProbeId = findLowestAvailableProbeId(conflictId)

        if (lowestAvailableProbeId == null) {
            Log.v(
                LOG_TAG,
                "resolveProbeIdConflict: no available probeId found -- cancel resolving probeId conflict on $conflictId",
            )
            return
        }

        val (lowerProbeId, higherProbeId) = if (lowestAvailableProbeId < conflictId) {
            Pair(lowestAvailableProbeId, conflictId)
        } else {
            Pair(conflictId, lowestAvailableProbeId)
        }

        // assign highest serialNumber to lowest ProbeId
        val (newDeviceProbeId, currentDeviceProbeId) = if (newNumeric > currentNumeric) {
            Pair(lowerProbeId, higherProbeId)
        } else {
            Pair(higherProbeId, lowerProbeId)
        }

        knownProbeIdAssignedToDevice[newDeviceProbeId] = newDeviceSerial
        knownProbeIdAssignedToDevice[currentDeviceProbeId] = currentDeviceSerial
        Log.v(
            LOG_TAG,
            "resolveProbeIdConflict: assign $newDeviceProbeId to $newDeviceSerial and $currentDeviceProbeId to $currentDeviceSerial",
        )
        setProbeID(newDeviceSerial, newDeviceProbeId) { success ->
            if (!success) {
                // revert change
                if (knownProbeIdAssignedToDevice[newDeviceProbeId] == newDeviceSerial) {
                    knownProbeIdAssignedToDevice.remove(newDeviceProbeId)
                }
                Log.v(
                    LOG_TAG,
                    "resolveProbeIdConflict: assign $newDeviceProbeId to $newDeviceSerial failed",
                )
            }
        }
        setProbeID(currentDeviceSerial, currentDeviceProbeId) { success ->
            if (!success) {
                // revert change
                if (knownProbeIdAssignedToDevice[currentDeviceProbeId] == currentDeviceSerial) {
                    knownProbeIdAssignedToDevice.remove(currentDeviceProbeId)
                }
                Log.v(
                    LOG_TAG,
                    "resolveProbeIdConflict: assign $currentDeviceProbeId to $currentDeviceSerial failed",
                )
            }
        }
    }

    private fun avoidProbeIdConflict(futureConflictDeviceSerial: String, conflictId: ProbeID) {
        val lowestAvailableProbeId = findLowestAvailableProbeId(conflictId)
        // assign to lowest available probeId
        if ((lowestAvailableProbeId != null) && (lowestAvailableProbeId != conflictId)) {
            Log.v(
                LOG_TAG,
                "avoidProbeIdConflict: assign $lowestAvailableProbeId to $futureConflictDeviceSerial"
            )
            knownProbeIdAssignedToDevice[lowestAvailableProbeId] = futureConflictDeviceSerial
            setProbeID(futureConflictDeviceSerial, lowestAvailableProbeId) { success ->
                if (!success) {
                    // revert change
                    if (knownProbeIdAssignedToDevice[lowestAvailableProbeId] == futureConflictDeviceSerial) {
                        knownProbeIdAssignedToDevice.remove(lowestAvailableProbeId)
                    }
                    Log.v(
                        LOG_TAG,
                        "avoidProbeIdConflict: assign $lowestAvailableProbeId to $futureConflictDeviceSerial failed"
                    )
                }
            }
        } else {
            Log.v(
                LOG_TAG,
                "avoidProbeIdConflict: No available ProbeId found to avoid future probeId conflict on $conflictId"
            )
        }
    }

    fun checkAndAvoidProbeIdConflictOnSetProbeId(serialNumber: String, probeId: ProbeID) {
        removeProbeIdAssignmentsToDevice(serialNumber, except = probeId)
        val currentDeviceAssigned = knownProbeIdAssignedToDevice[probeId]
        knownProbeIdAssignedToDevice[probeId] = serialNumber
        if ((currentDeviceAssigned != null) && (currentDeviceAssigned != serialNumber)) {
            Log.v(
                LOG_TAG,
                "setProbeID: assign $probeId to $serialNumber but currently assigned to $currentDeviceAssigned -- resolving"
            )
            avoidProbeIdConflict(currentDeviceAssigned, probeId)
        }
    }

    fun addDevice(probeSerialNumber: String, manager: ProbeManager) {
        probeIdObservations[probeSerialNumber] = scope.launch {
            manager.deviceFlow
                .filter {
                    !it.isPlaceholder()
                }.map { it.id }
                .distinctUntilChanged()
                .collect { probeId ->
                    Log.v(
                        LOG_TAG,
                        "Detect probeId $probeId is assigned to $probeSerialNumber"
                    )
                    removeProbeIdAssignmentsToDevice(probeSerialNumber, except = probeId)
                    val currentDeviceForProbeId = knownProbeIdAssignedToDevice[probeId]
                    if ((currentDeviceForProbeId != null) && (currentDeviceForProbeId != probeSerialNumber)) {
                        Log.v(
                            LOG_TAG,
                            "Conflict on probeId $probeId: $probeSerialNumber and $currentDeviceForProbeId",
                        )
                        resolveProbeIdConflict(
                            newDeviceSerial = probeSerialNumber,
                            currentDeviceSerial = currentDeviceForProbeId,
                            conflictId = probeId,
                        )
                    } else {
                        knownProbeIdAssignedToDevice[probeId] = probeSerialNumber
                    }
                }
        }
    }

    fun removeDevice(serialNumber: String) {
        Log.v(LOG_TAG, "removeDevice $serialNumber")
        probeIdObservations.remove(serialNumber)?.cancel()
        removeProbeIdAssignmentsToDevice(serialNumber, except = null)
    }

    fun clear() {
        Log.v(LOG_TAG, "clear")
        probeIdObservations.values.toList().forEach { it.cancel() }
        probeIdObservations.clear()
        knownProbeIdAssignedToDevice.clear()
    }
}