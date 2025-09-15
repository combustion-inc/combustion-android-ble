/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeArtCapable.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2025. Combustion Inc.
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

package inc.combustion.framework.ble.device

import inc.combustion.framework.ble.ProbeStatus
import inc.combustion.framework.ble.scanning.ProbeAdvertisingData
import inc.combustion.framework.ble.uart.ProbeLogResponse
import inc.combustion.framework.service.FoodSafeData
import inc.combustion.framework.service.ProbeColor
import inc.combustion.framework.service.ProbeHighLowAlarmStatus
import inc.combustion.framework.service.ProbeID
import inc.combustion.framework.service.ProbePowerMode
import inc.combustion.framework.service.ProbePredictionMode

typealias LinkID = String

internal interface UartCapableProbe : UartCapableSpecializedDevice {

    companion object {
        const val PROBE_MESSAGE_RESPONSE_TIMEOUT_MS = 5000L
        const val MEATNET_MESSAGE_RESPONSE_TIMEOUT_MS = 30000L

        fun makeLinkId(advertisement: ProbeAdvertisingData?): LinkID {
            return "${advertisement?.id}_${advertisement?.serialNumber}"
        }
    }

    val linkId: LinkID

    // advertising
    val advertisement: ProbeAdvertisingData?

    // probe status updates
    fun observeProbeStatusUpdates(hopCount: UInt?, callback: (suspend (status: ProbeStatus, hopCount: UInt?) -> Unit)? = null)

    // Probe UART Command APIs
    fun sendSessionInformationRequest(reqId: UInt? = null, callback: ((Boolean, Any?) -> Unit)? = null)
    fun sendSetProbeColor(color: ProbeColor, callback: ((Boolean, Any?) -> Unit)? = null)
    fun sendSetProbeID(id: ProbeID, callback: ((Boolean, Any?) -> Unit)? = null)
    fun sendSetPrediction(setPointTemperatureC: Double, mode: ProbePredictionMode, reqId: UInt? = null, callback: ((Boolean, Any?) -> Unit)? = null)
    fun sendConfigureFoodSafe(foodSafeData: FoodSafeData, reqId: UInt? = null, callback: ((Boolean, Any?) -> Unit)? = null)
    fun sendResetFoodSafe(reqId: UInt? = null, callback: ((Boolean, Any?) -> Unit)? = null)
    fun sendLogRequest(minSequence: UInt, maxSequence: UInt, callback: (suspend (ProbeLogResponse) -> Unit)? = null)
    fun sendSetPowerMode(powerMode: ProbePowerMode, reqId: UInt? = null, callback: ((Boolean, Any?) -> Unit)?)
    fun sendResetProbe(reqId: UInt? = null, callback: ((Boolean, Any?) -> Unit)?)
    fun sendSetProbeHighLowAlarmStatus(highLowAlarmStatus: ProbeHighLowAlarmStatus, reqId: UInt? = null, callback: ((Boolean, Any?) -> Unit)?)
}