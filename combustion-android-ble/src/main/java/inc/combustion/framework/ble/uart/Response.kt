/*
 * Project: Combustion Inc. Android Framework
 * File: Response.kt
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
package inc.combustion.framework.ble.uart

import inc.combustion.framework.ble.getCRC16CCITT
import inc.combustion.framework.ble.getLittleEndianUShortAt

/**
 * Base class for UART response message
 *
 * @property success successfully processed request when true. otherwise,
 *  error handling request.
 */
internal open class Response(
    val success: Boolean,
    val payLoadLength: UInt
) {
    companion object {
        const val HEADER_SIZE: UByte = 7u

        /**
         * Factory method for constructing response from byte array.
         *
         * @param data data received over BLE from UART service.
         * @return Instance of the response or null on data error.
         */
        fun fromData(data: UByteArray): List<Response> {
            val responses = mutableListOf<Response>()

            var numberBytesRead = 0

            while(numberBytesRead < data.size) {
                val bytesToDecode = data.copyOfRange(numberBytesRead, data.size)
                val response = responseFromData(bytesToDecode)
                if (response != null) {
                    responses.add(response)
                    numberBytesRead += (response.payLoadLength + HEADER_SIZE).toInt()
                }
                else {
                    // Found invalid response, break out of while loop
                    break
                }
            }

            return responses
        }

        private fun responseFromData(data: UByteArray) : Response? {

            // Validate Sync Bytes { 0xCA, 0xFE }
            if((data[0].toUInt() != 0xCAu) or (data[1].toUInt() != 0xFEu)) {
                return null
            }

            // Message type
            val messageType = MessageType.fromUByte(data[4]) ?: return null

            // Success/Fail
            val success = data[5] > 0u

            // Payload Length
            val length = data[6]

            // Validate CRC--omit the first 4 elements (sync bytes and CRC), add 3 to payload length
            // for message type, success, and payload length. Note that we slice data here because
            // it potentially contains more than one packet.
            val calculatedCrc: UShort = data.drop(4).slice(0 until length.toInt() + 3).toUByteArray().getCRC16CCITT()
            val sentCrc: UShort = data.getLittleEndianUShortAt(2)

            if (sentCrc != calculatedCrc) {
                return null
            }

            return when(messageType) {
                MessageType.PROBE_LOG -> ProbeLogResponse.fromData(data, success, length.toUInt())
                MessageType.SET_PROBE_COLOR -> createGenericResponse(success,  SetColorResponse.PAYLOAD_LENGTH, length.toUInt(), ::SetColorResponse)
                MessageType.SET_PROBE_ID -> createGenericResponse(success, SetIDResponse.PAYLOAD_LENGTH, length.toUInt(), ::SetIDResponse)
                MessageType.READ_SESSION_INFO -> SessionInfoResponse.fromData(data, success, length.toUInt())
                MessageType.SET_PREDICTION -> createGenericResponse(success, SetPredictionResponse.PAYLOAD_LENGTH, length.toUInt(), ::SetPredictionResponse)
                MessageType.CONFIGURE_FOOD_SAFE -> createGenericResponse(success, ConfigureFoodSafeResponse.PAYLOAD_LENGTH, length.toUInt(), ::ConfigureFoodSafeResponse)
                MessageType.RESET_FOOD_SAFE -> createGenericResponse(success, ResetFoodSafeResponse.PAYLOAD_LENGTH, length.toUInt(), ::ResetFoodSafeResponse)
                MessageType.SET_POWER_MODE -> createGenericResponse(success, SetPowerModeResponse.PAYLOAD_LENGTH, length.toUInt(), ::SetPowerModeResponse)
                MessageType.RESET_PROBE -> createGenericResponse(success, ResetProbeResponse.PAYLOAD_LENGTH, length.toUInt(), ::ResetProbeResponse)
            }
        }

        private fun <T> createGenericResponse(success: Boolean, minPayloadLength: UInt, payloadLength: UInt, factory: (Boolean, UInt) -> T) : T? {
            return if (minPayloadLength >= payloadLength)
                factory(success, payloadLength)
            else
                null
        }
    }
}