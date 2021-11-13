/*
 * Project: Combustion Inc. Android Framework
 * File: Extensions.kt
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
package inc.combustion.framework.ble

/**
 * Convert to UInt at the specified index.
 *
 * @param index index into buffer
 * @return UInt at index.
 */
internal fun UByteArray.getLittleEndianUIntAt(index: Int) : UInt {
    return ((this[index+3].toUInt() and 0xFFu) shl 24) or
            ((this[index+2].toUInt() and 0xFFu) shl 16) or
            ((this[index+1].toUInt() and 0xFFu) shl 8) or
            (this[index].toUInt() and 0xFFu)
}

/**
 * Stores a UInt at the specified index.
 *
 * @param index index into buffer
 * @param value value to put into buffer at index.
 */
internal fun UByteArray.putLittleEndianUIntAt(index: Int, value: UInt) {
    this[index] = (value and 0x000000FFu).toUByte()
    this[index + 1] = ((value and 0x0000FF00u) shr 8).toUByte()
    this[index + 2] = ((value and 0x00FF0000u) shr 16).toUByte()
    this[index + 3] = ((value and 0xFF000000u) shr 24).toUByte()
}
