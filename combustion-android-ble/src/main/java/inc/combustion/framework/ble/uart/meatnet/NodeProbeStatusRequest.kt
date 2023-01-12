/*
 * Project: Combustion Inc. Android Example
 * File: SetPredictionRequest.kt
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

package inc.combustion.framework.ble.uart.meatnet

import inc.combustion.framework.ble.ProbeStatus
import inc.combustion.framework.ble.getLittleEndianUInt32At
import inc.combustion.framework.service.HopCount

internal class NodeProbeStatusRequest(
    private val rawData : UByteArray,
    requestId : UInt,
    payloadLength : UByte

) : NodeRequest(requestId, payloadLength) {

    companion object {
        const val PAYLOAD_LENGTH: UByte = 35u

        /**
         * Factory function that creates an instance of this object from raw message data.
         */
        fun fromRaw(data: UByteArray, requestId: UInt, payloadLength: UByte) : NodeProbeStatusRequest? {
            if(payloadLength < PAYLOAD_LENGTH) {
                return null
            }

            return NodeProbeStatusRequest(data, requestId, payloadLength)
        }
    }

    var serialNumber: UInt? = null
    var probeStatus: ProbeStatus? = null
    var hopCount: HopCount? = null

    init {
        // Store raw data in parent's storage
        data = rawData

        val sequenceByteIndex = NodeRequest.HEADER_SIZE.toInt()

        this.serialNumber = data.getLittleEndianUInt32At(sequenceByteIndex)

        // Parse Probe Status
        this.probeStatus = ProbeStatus.fromRawData(data.slice(sequenceByteIndex+4..sequenceByteIndex+34).toUByteArray())

        this.hopCount = HopCount.fromUByte(data[34])
    }

}