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
package inc.combustion.framework

import inc.combustion.framework.Constants.UTF8_SERIAL_NUMBER_LENGTH
import kotlin.reflect.KClass

val Any.LOG_TAG: String
    get() {
        val tag = "Combustion.${javaClass.simpleName}"
        return if (tag.length <= 23) tag else tag.substring(0, 23)
    }

fun UByte.isBitSet(position: Int): Boolean {
    require(position in 0..7) { "Bit position must be between 0 and 7" }
    return (this.toInt() and (1 shl position)) != 0
}

fun Int.setBit(bit: Int): Int = this or (1 shl bit)

fun UByte.setBit(bit: Int): UByte {
    require(bit in 0..7) { "Bit index must be between 0 and 7" }
    return (this.toInt().setBit(bit)).toUByte()
}

fun UByte.toPercentage(): Int = this.toInt()

fun UByteArray.utf8StringFromRange(indices: IntRange): String =
    String(this.copyOf().sliceArray(indices).toByteArray(), Charsets.UTF_8).uppercase()

fun UByteArray.getUtf8SerialNumber(startIdx: Int): String =
    this.utf8StringFromRange(startIdx until (startIdx + UTF8_SERIAL_NUMBER_LENGTH))

fun UByteArray.copyInUtf8SerialNumber(serialNumber: String, startIdx: Int) {
    val serialBytes = serialNumber.encodeToByteArray().toUByteArray() // UTF-8 by default
    serialBytes.copyInto(
        destination = this,
        startIndex = 0,
        endIndex = minOf(serialBytes.size, UTF8_SERIAL_NUMBER_LENGTH),
        destinationOffset = startIdx,
    )
}

fun <X : Any, Y : X> List<X>.castToSubTypeOrNull(kClass: KClass<Y>): List<Y>? {
    return if (all { kClass.isInstance(it) }) {
        @Suppress("UNCHECKED_CAST")
        this as List<Y>
    } else null
}