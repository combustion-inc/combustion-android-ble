/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeBleDeviceBase.kt
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
package inc.combustion.framework.ble.device

import inc.combustion.framework.ble.ProbeStatus
import inc.combustion.framework.ble.scanning.BaseAdvertisingData
import inc.combustion.framework.ble.scanning.CombustionAdvertisingData
import inc.combustion.framework.ble.uart.LogResponse
import inc.combustion.framework.service.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean

typealias LinkID = String

internal abstract class ProbeBleDeviceBase() {

    companion object {
        const val MESSAGE_RESPONSE_TIMEOUT_MS = 5000L

        fun makeLinkId(advertisement: CombustionAdvertisingData?): LinkID {
            return "${advertisement?.id}_${advertisement?.probeSerialNumber}"
        }
    }

    // message completion handlers
    protected val sessionInfoHandler = UartBleDevice.MessageCompletionHandler()
    protected val setColorHandler = UartBleDevice.MessageCompletionHandler()
    protected val setIdHandler = UartBleDevice.MessageCompletionHandler()
    protected val setPredictionHandler = UartBleDevice.MessageCompletionHandler()

    // mac
    abstract val mac: String

    // identifiers
    abstract val linkId: LinkID
    abstract val id: DeviceID
    abstract val probeSerialNumber: String

    // advertising
    abstract val advertisement: CombustionAdvertisingData?

    // connection state
    abstract val rssi: Int
    abstract val connectionState: DeviceConnectionState
    abstract val isConnected: Boolean
    abstract val isDisconnected: Boolean
    abstract val isInRange: Boolean
    abstract val isConnectable: Boolean

    // dfu
    abstract var isInDfuMode: Boolean

    // device information service
    abstract val deviceInfoSerialNumber: String?
    abstract val deviceInfoFirmwareVersion: FirmwareVersion?
    abstract val deviceInfoHardwareRevision: String?

    // meatnet
    abstract val hopCount: UInt
    abstract val productType: CombustionProductType

    // connection management
    abstract fun connect()
    abstract fun disconnect()

    // advertising updates
    abstract fun observeAdvertisingPackets(serialNumberFilter: String, macFilter: String, callback: (suspend (advertisement: CombustionAdvertisingData) -> Unit)? = null)

    // connection state updates
    abstract fun observeRemoteRssi(callback: (suspend (rssi: Int) -> Unit)? = null)
    abstract fun observeOutOfRange(timeout: Long, callback: (suspend () -> Unit)? = null)
    abstract fun observeConnectionState(callback: (suspend (newConnectionState: DeviceConnectionState) -> Unit)? = null)

    // device information service
    abstract suspend fun readSerialNumber()
    abstract suspend fun readFirmwareVersion()
    abstract suspend fun readHardwareRevision()

    // probe status updates
    abstract fun observeProbeStatusUpdates(callback: (suspend (status: ProbeStatus) -> Unit)? = null)

    // Probe UART Command APIs
    abstract fun sendSessionInformationRequest(callback: ((Boolean, Any?) -> Unit)? = null)
    abstract fun sendSetProbeColor(color: ProbeColor, callback: ((Boolean, Any?) -> Unit)? = null)
    abstract fun sendSetProbeID(id: ProbeID, callback: ((Boolean, Any?) -> Unit)? = null)
    abstract fun sendSetPrediction(setPointTemperatureC: Double, mode: ProbePredictionMode, callback: ((Boolean, Any?) -> Unit)? = null)
    abstract fun sendLogRequest(minSequence: UInt, maxSequence: UInt, callback: (suspend (LogResponse) -> Unit)? = null)
}