/*
 * Project: Combustion Inc. Android Example
 * File: NodeProbeStatusRequest.kt
 * Author: https://github.com/jmaha
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

package inc.combustion.framework.ble.uart.meatnet

import android.util.Log
import inc.combustion.framework.ble.ProbeStatus
import inc.combustion.framework.ble.getLittleEndianUInt32At
import inc.combustion.framework.service.HopCount
import inc.combustion.framework.service.OverheatingSensors

internal class NodeProbeStatusRequest(
    requestId: UInt,
    payloadLength: UByte,
    serialNumber: String,
    val hopCount: HopCount,
    val probeStatus: ProbeStatus,
) : NodeRequest(requestId, payloadLength, NodeMessageType.PROBE_STATUS, serialNumber) {

    companion object {
        private const val SERIAL_NUMBER_LENGTH = 4
        private const val NETWORK_INFORMATION_LENGTH = 1

        private const val MINIMUM_PAYLOAD_LENGTH =
            SERIAL_NUMBER_LENGTH + ProbeStatus.MIN_RAW_SIZE + NETWORK_INFORMATION_LENGTH

        private val PAYLOAD_START_INDEX = HEADER_SIZE.toInt()
        private val SERIAL_NUMBER_INDEX = PAYLOAD_START_INDEX
        private val PROBE_STATUS_INDEX = SERIAL_NUMBER_INDEX + SERIAL_NUMBER_LENGTH

        /**
         * Factory function that creates an instance of this object from raw message data.
         */
        fun fromRaw(
            data: UByteArray,
            requestId: UInt,
            payloadLength: UByte,
        ): NodeProbeStatusRequest? {
            if (payloadLength < MINIMUM_PAYLOAD_LENGTH.toUInt()) {
                return null
            }

            val serialNumber: UInt = data.getLittleEndianUInt32At(SERIAL_NUMBER_INDEX)

            val probeStatusIndexEnd =
                PROBE_STATUS_INDEX + if (payloadLength.toInt() == MINIMUM_PAYLOAD_LENGTH) {
                    ProbeStatus.MIN_RAW_SIZE
                } else {
                    ProbeStatus.RAW_SIZE_INCLUDING_FOOD_SAFE
                }

            val probeStatusRange = PROBE_STATUS_INDEX until data.size

            // The overheating flags are stored after the hop count for the node messages
            // so the range is updated to take that into account
            val overheatRangeStart = ProbeStatus.RAW_SIZE_INCLUDING_FOOD_SAFE + 1
            val overheatRange =
                overheatRangeStart until overheatRangeStart + OverheatingSensors.SIZE_BYTES

            Log.v(
                "D3V",
                "NODE ProbeStatus.fromRawData, data.size = ${data.size}, requestLength = ${payloadLength + HEADER_SIZE}, probeStatusRange.last = ${probeStatusRange.last}"
            )
            val probeStatus: ProbeStatus =
                ProbeStatus.fromRawData(data.slice(probeStatusRange).toUByteArray(), overheatRange)
                    ?: return null

            val hopCount: HopCount = HopCount.fromUByte(data[probeStatusIndexEnd])

            return NodeProbeStatusRequest(
                requestId,
                payloadLength,
                Integer.toHexString(serialNumber.toInt()).uppercase(),
                hopCount,
                probeStatus,
            )
        }
    }
}