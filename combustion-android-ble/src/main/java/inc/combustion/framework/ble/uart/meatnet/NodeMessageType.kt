/*
 * Project: Combustion Inc. Android Framework
 * File: NodeMessageType.kt
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


interface NodeMessage {
    val value: UByte
    companion object {
        fun fromUByte(value: UByte): NodeMessage? = null
    }
}
/**
 * Enumerates MeatNet Node message types in Combustion's UART protocol.
 *
 * @property value byte value for message type.
 */
internal enum class NodeMessageType(override val value: UByte) : NodeMessage {

    SET_ID(0x1u),
    SET_COLOR(0x2u),
    SESSION_INFO(0x3u),
    LOG(0x4u),
    SET_PREDICTION(0x5u),
    READ_OVER_TEMPERATURE(0x6u),
    CONFIGURE_FOOD_SAFE(0x7u),
    RESET_FOOD_SAFE(0x8u),

    GET_FEATURE_FLAGS(0x30u),

    CONNECTED(0x40u),
    DISCONNECTED(0x41u),
    READ_NODE_LIST(0x42u),
    READ_NETWORK_TOPOLOGY(0X43u),
    PROBE_SESSION_CHANGED(0X44u),
    PROBE_STATUS(0X45u),
    PROBE_FIRMWARE_REVISION(0X46u),
    PROBE_HARDWARE_REVISION(0X47u),
    PROBE_MODEL_INFORMATION(0X48u),
    HEARTBEAT(0X49u);

    companion object {
        fun fromUByte(value: UByte) = values().firstOrNull { it.value == value }
    }
}