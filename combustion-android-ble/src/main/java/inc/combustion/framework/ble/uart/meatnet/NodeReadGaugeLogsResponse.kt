/*
 * Project: Combustion Inc. Android Framework
 * File: NodeReadGaugeLogsResponse.kt
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

import inc.combustion.framework.Constants.UTF8_SERIAL_NUMBER_LENGTH
import inc.combustion.framework.ble.getLittleEndianUInt32At
import inc.combustion.framework.ble.uart.LogResponse
import inc.combustion.framework.getUtf8SerialNumber
import inc.combustion.framework.isBitSet
import inc.combustion.framework.service.SensorTemperature

internal class NodeReadGaugeLogsResponse(
    override val serialNumber: String,
    override val sequenceNumber: UInt,
    override val temperature: SensorTemperature,
    val isSensorPresent: Boolean,
    success: Boolean,
    requestId: UInt,
    responseId: UInt,
    payLoadLength: UByte,
) : NodeResponse(
    success,
    requestId,
    responseId,
    payLoadLength,
    NodeMessageType.GAUGE_LOG,
), TargetedNodeResponse, LogResponse {
    override fun toString(): String {
        return "${super.toString()} $success $serialNumber $sequenceNumber"
    }

    companion object {
        private const val MIN_PAYLOAD_LENGTH: UByte = 17u
        private const val SEQUENCE_NUMBER_LENGTH = 4
        private const val RAW_TEMP_LENGTH = 2
        private const val SENSOR_PRESENT_LENGTH = 1
        private val SERIAL_NUMBER_IDX = HEADER_SIZE.toInt()
        private val SEQ_NUMBER_IDX = SERIAL_NUMBER_IDX + UTF8_SERIAL_NUMBER_LENGTH
        private val RAW_TEMP_IDX = SEQ_NUMBER_IDX + SEQUENCE_NUMBER_LENGTH
        private val SENSOR_PRESENT_IDX = RAW_TEMP_IDX + RAW_TEMP_LENGTH


        fun fromData(
            payload: UByteArray,
            success: Boolean,
            requestId: UInt,
            responseId: UInt,
            payloadLength: UByte,
        ): NodeReadGaugeLogsResponse? {
            if (payloadLength < MIN_PAYLOAD_LENGTH) {
                return null
            }

            val serialNumber = payload.getUtf8SerialNumber(SERIAL_NUMBER_IDX)
            val sequenceNumber = payload.getLittleEndianUInt32At(SEQ_NUMBER_IDX)
            val temperature = SensorTemperature.fromRawDataStart(
                payload.sliceArray(RAW_TEMP_IDX until (RAW_TEMP_IDX + RAW_TEMP_LENGTH))
            )
            val isSensorPresent =
                payload.sliceArray(
                    SENSOR_PRESENT_IDX until (SENSOR_PRESENT_IDX + SENSOR_PRESENT_LENGTH)
                )[0].isBitSet(0)

            return NodeReadGaugeLogsResponse(
                serialNumber,
                sequenceNumber,
                temperature,
                isSensorPresent,
                success,
                requestId,
                responseId,
                payloadLength,
            )
        }
    }
}