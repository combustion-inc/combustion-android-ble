/*
 * Project: Combustion Inc. Android Framework
 * File: NodeConfigureFoodSafeRequest.kt
 * Author: Nick Helseth <nick@sasq.io>
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

import inc.combustion.framework.ble.putLittleEndianUInt32At
import inc.combustion.framework.service.FoodSafeData

internal class NodeConfigureFoodSafeRequest(
    serialNumber: String,
    foodSafeData: FoodSafeData,
    requestId: UInt? = null,
) : NodeRequest(
    outgoingPayload = populatePayload(serialNumber = serialNumber, foodSafeData = foodSafeData),
    messageType = NodeMessageType.CONFIGURE_FOOD_SAFE,
    requestId = requestId,
    serialNumber,
) {
    companion object {
        val PAYLOAD_LENGTH: UByte = (4 + FoodSafeData.SIZE_BYTES).toUByte()

        fun populatePayload(
            serialNumber: String,
            foodSafeData: FoodSafeData,
        ) : UByteArray {
            val payload = UByteArray(PAYLOAD_LENGTH.toInt())

            payload.putLittleEndianUInt32At(0, serialNumber.toLong(radix = 16).toUInt())
            foodSafeData.toRaw.copyInto(payload, 4)

            return payload
        }
    }
}