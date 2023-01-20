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

internal inline fun NOT_IMPLEMENTED(reason: String = ""): Nothing = TODO(reason)

/**
 * Calculates the 16 bit * Cyclic Redundancy Check (CRC-CCIIT 0xFFFF) with an initial value of
 * 0xFFFF and the polynomial 0x1021.
 *
 * 1 + x + x^5 + x^12 + x^16 is irreducible polynomial.
 *
 * Adapted from: https://introcs.cs.princeton.edu/java/61data/CRC16CCITT.java
 */
internal fun UByteArray.getCRC16CCITT() : UShort {
    var crc: UShort = 0xFFFFu // initial value
    val polynomial: UShort = 0x1021u // 0001 0000 0010 0001  (0, 5, 12)

    for (b in this) {
        for (i in 0..7) {
            val bit = (b   shr (7 - i) and 1u) == 1u.toUShort()
            val c15 = (crc shr (15)    and 1u) == 1u.toUShort()
            crc = crc shl 1
            if (c15 xor bit) crc = crc xor polynomial
        }
    }

    return crc and 0xFFFFu
}


/**
 * Convert to UShort at the specified index.
 *
 * @param index index into buffer
 * @return UShort at index.
 */
internal fun UByteArray.getLittleEndianUShortAt(index: Int) : UShort {
    return ((this[index+1].toUShort() and 0xFFu) shl 8) or
            (this[index].toUShort() and 0xFFu)
}

/**
 * Stores a UShort at the specified index.
 *
 * @param index index into buffer
 * @param value value to put into buffer at index.
 */
internal fun UByteArray.putLittleEndianUShortAt(index: Int, value: UShort) {
    this[index] = (value and 0x00FFu).toUByte()
    this[index + 1] = ((value and 0xFF00u) shr 8).toUByte()
}

/**
 * Convert to UInt at the specified index.
 *
 * @param index index into buffer
 * @return UInt at index.
 */
internal fun UByteArray.getLittleEndianUInt16At(index: Int) : UInt {
    return ((this[index+1].toUInt() and 0xFFu) shl 8) or
            (this[index].toUInt() and 0xFFu)
}


/**
 * Convert to UInt32 at the specified index.
 *
 * @param index index into buffer
 * @return UInt at index.
 */
internal fun UByteArray.getLittleEndianUInt32At(index: Int) : UInt {
    return ((this[index+3].toUInt() and 0xFFu) shl 24) or
            ((this[index+2].toUInt() and 0xFFu) shl 16) or
            ((this[index+1].toUInt() and 0xFFu) shl 8) or
            (this[index].toUInt() and 0xFFu)
}

/**
 * Stores a UInt32 at the specified index.
 *
 * @param index index into buffer
 * @param value value to put into buffer at index.
 */
internal fun UByteArray.putLittleEndianUInt32At(index: Int, value: UInt) {
    this[index] = (value and 0x000000FFu).toUByte()
    this[index + 1] = ((value and 0x0000FF00u) shr 8).toUByte()
    this[index + 2] = ((value and 0x00FF0000u) shr 16).toUByte()
    this[index + 3] = ((value and 0xFF000000u) shr 24).toUByte()
}

infix fun UByte.shl(shift: Int) = ((this.toInt() shl shift) and (0x0000FFFF)).toUShort()
infix fun UByte.shr(shift: Int) = ((this.toInt() shr shift) and (0x0000FFFF)).toUShort()

infix fun UShort.shl(shift: Int) = ((this.toInt() shl shift) and (0x0000FFFF)).toUShort()
infix fun UShort.shr(shift: Int) = ((this.toInt() shr shift) and (0x0000FFFF)).toUShort()
