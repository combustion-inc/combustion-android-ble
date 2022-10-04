/*
 * Project: Combustion Inc. Android Framework
 * File: SessionInfoResponse.kt
 * Author: https://github.com/jjohnstz
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

import inc.combustion.framework.ble.getLittleEndianUInt16At
import inc.combustion.framework.ble.getLittleEndianUInt32At
import inc.combustion.framework.service.SessionInformation

internal class SessionInfoResponse(
    val sessionInformation: SessionInformation,
    success: Boolean,
    payLoadLength: UInt
) : Response(success, payLoadLength) {

    companion object {
        private const val PAYLOAD_LENGTH: UInt = 6u

        fun fromData(data: UByteArray, success: Boolean, payloadLength: UInt): SessionInfoResponse? {
            if(payloadLength < PAYLOAD_LENGTH) {
                return null
            }

            val sessionID: UInt = data.getLittleEndianUInt32At(HEADER_SIZE.toInt())
            val samplePeriod: UInt = data.getLittleEndianUInt16At(HEADER_SIZE.toInt() + 4)

            val sessionInfo = SessionInformation(sessionID, samplePeriod)

            return SessionInfoResponse(sessionInfo, success, payloadLength)
        }
    }
}

