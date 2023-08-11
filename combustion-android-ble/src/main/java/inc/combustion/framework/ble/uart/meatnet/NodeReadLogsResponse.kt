/*
 * Project: Combustion Inc. Android Framework
 * File: NodeReadLogsResponse.kt
 * Author: https://github.com/angrygorilla
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

package inc.combustion.framework.ble.uart.meatnet

import inc.combustion.framework.ble.getLittleEndianUInt32At
import inc.combustion.framework.ble.uart.MessageType
import inc.combustion.framework.service.PredictionLog
import inc.combustion.framework.service.ProbeTemperatures

internal class NodeReadLogsResponse(
    val serialNumber: String,
    val sequenceNumber: UInt,
    val temperatures: ProbeTemperatures,
    val predictionLog: PredictionLog,
    success: Boolean,
    requestId: UInt,
    responseId: UInt,
    payLoadLength: UByte
) : NodeResponse(
    success,
    requestId,
    responseId,
    payLoadLength,
    NodeMessageType.LOG
) {
    companion object {
        private const val MIN_PAYLOAD_LENGTH: UByte = 28u

        fun fromData(
            payload: UByteArray,
            success: Boolean,
            requestId: UInt,
            responseId: UInt,
            payloadLength: UByte
        ) : NodeReadLogsResponse? {
            if (payloadLength < MIN_PAYLOAD_LENGTH) {
                return null
            }

            val serialNumber = payload.getLittleEndianUInt32At(HEADER_SIZE.toInt()).toString(radix = 16).uppercase()
            val sequenceNumber = payload.getLittleEndianUInt32At((HEADER_SIZE + 4u).toInt())
            val rawTemperatures =
                payload.sliceArray((HEADER_SIZE + 8u).toInt() .. (HEADER_SIZE + 20u).toInt())
            val rawPredictionLog =
                payload.sliceArray((HEADER_SIZE + 21u ).toInt().. (HEADER_SIZE + 27u).toInt())

            val temperatures = ProbeTemperatures.fromRawData(rawTemperatures)
            val predictionLog = PredictionLog.fromRawData(rawPredictionLog)

            return NodeReadLogsResponse(
                serialNumber,
                sequenceNumber,
                temperatures,
                predictionLog,
                success,
                requestId,
                responseId,
                payloadLength
            )
        }
    }
}