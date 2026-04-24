/*
 * Project: Combustion Inc. Android Framework
 * File: NodeSetEngineSetPointTemp.kt
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
import inc.combustion.framework.service.SensorTemperature

internal class NodeSetEngineTemperatureSetPointRequest(
    serialNumber: String,
    private val temperature: SensorTemperature,
    requestId: UInt? = null
) : NodeRequest(
    populatePayload(serialNumber, temperature),
    NodeMessageType.SET_ENGINE_TEMPERATURE_SET_POINT,
    requestId,
    serialNumber,
) {

    override fun toString(): String {
        return "${super.toString()} $serialNumber $temperature"
    }

    companion object {
        private const val PAYLOAD_LENGTH: UByte = 12u

        fun populatePayload(
            serialNumber: String,
            temperature: SensorTemperature,
        ): UByteArray {
            val payload = UByteArray(PAYLOAD_LENGTH.toInt())

            // Add serial number to payload
            payload.copyInUtf8SerialNumber(serialNumber, 0)

            val tempBytes = temperature.toRawDataEnd()
            tempBytes.copyInto(
                destination = payload,
                destinationOffset = payload.size - tempBytes.size
            )

            return payload
        }
    }
}