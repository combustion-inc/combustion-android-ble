/*
 * Project: Combustion Inc. Android Framework
 * File: NodeSetGaugeIDRequest.kt
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

package inc.combustion.framework.ble.uart.meatnet

import inc.combustion.framework.copyInUtf8SerialNumber
import inc.combustion.framework.service.GaugeID

internal class NodeSetGaugeIDRequest(
    serialNumber: String,
    private val gaugeId: GaugeID,
    requestId: UInt? = null
) : NodeRequest(
    populatePayload(serialNumber, gaugeId),
    NodeMessageType.SET_GAUGE_ID,
    requestId,
    serialNumber,
) {
    override fun toString(): String {
        return "${super.toString()} $serialNumber $gaugeId"
    }

    companion object {
        // Unlike NodeSetProbeIDRequest, the gauge's serial number is encoded as UTF-8 text (like
        // NodeSetGaugeHighLowAlarmRequest's), not as a little-endian hex-parsed UInt32 -- gauges
        // don't have the probe's numeric serial number format.
        private const val PAYLOAD_LENGTH: UByte = 11u

        /**
         * Helper function that builds up payload of request.
         */
        fun populatePayload(
            serialNumber: String,
            gaugeId: GaugeID,
        ): UByteArray {

            val payload = UByteArray(PAYLOAD_LENGTH.toInt())

            // Add serial number to payload
            payload.copyInUtf8SerialNumber(serialNumber, 0)

            // Encode ID in payload
            payload[payload.size - 1] = gaugeId.type

            return payload
        }
    }
}
