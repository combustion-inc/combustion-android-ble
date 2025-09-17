/*
 * Project: Combustion Inc. Android Framework
 * File: NodeSetProbeHighLowAlarmRequest.kt
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

package inc.combustion.framework.ble.uart.meatnet

import android.util.Log
import inc.combustion.framework.ble.putLittleEndianUInt32At
import inc.combustion.framework.service.ProbeHighLowAlarmStatus

internal class NodeSetProbeHighLowAlarmRequest(
    serialNumber: String,
    private val highLowAlarmStatus: ProbeHighLowAlarmStatus,
    requestId: UInt? = null,
) : NodeRequest(
    populatePayload(serialNumber, highLowAlarmStatus),
    NodeMessageType.SET_PROBE_HIGH_LOW_ALARM,
    requestId,
    serialNumber,
) {
    override fun toString(): String {
        return "${super.toString()} $serialNumber $highLowAlarmStatus"
    }

    companion object {
        private val PAYLOAD_LENGTH: UByte =
            (ProbeHighLowAlarmStatus.PROBE_HIGH_LOW_ALARMS_SIZE_BYTES + 4).toUByte()

        /**
         * Helper function that builds up payload of request.
         */
        fun populatePayload(
            serialNumber: String,
            highLowAlarmStatus: ProbeHighLowAlarmStatus,
        ): UByteArray {
            val payload = UByteArray(PAYLOAD_LENGTH.toInt())

            // Add serial number to payload
            payload.putLittleEndianUInt32At(0, serialNumber.toLong(radix = 16).toUInt())

            // Encode alarm status in payload
            val rawHighLowAlarmStatus = highLowAlarmStatus.toRawData()
            Log.v("D3V", "NodeSetProbeHighLowAlarmRequest: payload = $rawHighLowAlarmStatus")
            rawHighLowAlarmStatus.copyInto(
                destination = payload,
                destinationOffset = payload.size - rawHighLowAlarmStatus.size,
            )

            return payload
        }
    }
}