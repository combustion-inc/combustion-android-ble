/*
 * Project: Combustion Inc. Android Framework
 * File: NodeSetEngineControlDeviceRequest.kt
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

import inc.combustion.framework.ble.putLittleEndianUInt32At
import inc.combustion.framework.copyInUtf8SerialNumber
import inc.combustion.framework.service.CombustionProductType

/**
 * Set Engine Control Device (0x72)
 *
 * Sends a command to set the Engine's control device. The control device is either a probe or a
 * gauge that will be used as input to control the Engine's fan speed to keep the target
 * temperature.
 *
 * Request Payload (23 bytes):
 *   Offset  0– 9 : Engine serial number (UTF-8, 10 bytes)
 *   Offset 10    : Control device type (see [CombustionProductType])
 *   Offset 11–22 : Product serial number (12 bytes)
 *     - Probe : 4-byte little-endian UInt32 at offset 11, remaining bytes zeroed
 *     - Other : UTF-8 node serial at offset 11, remaining bytes zeroed
 */
internal class NodeSetEngineControlDeviceRequest(
    serialNumber: String,
    private val controlDeviceType: CombustionProductType,
    private val controlSerialNumber: String,
    requestId: UInt? = null,
) : NodeRequest(
    populatePayload(serialNumber, controlDeviceType, controlSerialNumber),
    NodeMessageType.SET_ENGINE_CONTROL_DEVICE,
    requestId,
    serialNumber,
) {
    override fun toString(): String {
        return "${super.toString()} $serialNumber $controlDeviceType $controlSerialNumber"
    }

    companion object {
        private const val PAYLOAD_LENGTH: UByte = 23u

        private const val IDX_ENGINE_SERIAL = 0
        private const val IDX_CONTROL_DEVICE_TYPE = 10
        private const val IDX_PROBE_SERIAL = 11
        private const val IDX_NODE_SERIAL = 11

        fun populatePayload(
            serialNumber: String,
            controlDeviceType: CombustionProductType,
            controlSerialNumber: String,
        ): UByteArray {
            val payload = UByteArray(PAYLOAD_LENGTH.toInt())

            payload.copyInUtf8SerialNumber(serialNumber, IDX_ENGINE_SERIAL)
            payload[IDX_CONTROL_DEVICE_TYPE] = controlDeviceType.type

            when (controlDeviceType) {
                CombustionProductType.PROBE -> payload.putLittleEndianUInt32At(
                    IDX_PROBE_SERIAL,
                    controlSerialNumber.toLong(radix = 16).toUInt(),
                )
                else -> payload.copyInUtf8SerialNumber(controlSerialNumber, IDX_NODE_SERIAL)
            }

            return payload
        }
    }
}
