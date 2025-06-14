/*
 * Project: Combustion Inc. Android Framework
 * File: MessageType.kt
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

/**
 * Enumerates message types in Combustion's UART protocol.
 *
 * @property value byte value for message type.
 */
internal enum class MessageType(val value: UByte) {
    SET_PROBE_ID(0x01u),
    SET_PROBE_COLOR(0x02u),
    READ_SESSION_INFO(0x03u),
    PROBE_LOG(0x04u),
    SET_PREDICTION(0x05u),
    CONFIGURE_FOOD_SAFE(0x07u),
    RESET_FOOD_SAFE(0x08u),
    SET_POWER_MODE(0x09u),
    RESET_PROBE(0x0Au),
    ;

    companion object {
        fun fromUByte(value: UByte) = values().firstOrNull { it.value == value }
    }
}