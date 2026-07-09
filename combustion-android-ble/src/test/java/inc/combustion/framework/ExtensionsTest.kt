/*
 * Project: Combustion Inc. Android Framework
 * File: ExtensionsTest.kt
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

package inc.combustion.framework

import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalUnsignedTypes::class)
class ExtensionsTest {
    @Test
    fun `utf8StringFromRange trims a single trailing null byte`() {
        val bytes = "GGG198A54".toByteArray(Charsets.UTF_8).toUByteArray() + ubyteArrayOf(0u)

        assertEquals("GGG198A54", bytes.utf8StringFromRange(bytes.indices))
    }

    @Test
    fun `utf8StringFromRange trims multiple trailing null bytes`() {
        val bytes = "AB".toByteArray(Charsets.UTF_8).toUByteArray() + ubyteArrayOf(0u, 0u, 0u)

        assertEquals("AB", bytes.utf8StringFromRange(bytes.indices))
    }

    @Test
    fun `utf8StringFromRange leaves a string that exactly fills the field unchanged`() {
        val bytes = "1234567890".toByteArray(Charsets.UTF_8).toUByteArray()

        assertEquals("1234567890", bytes.utf8StringFromRange(bytes.indices))
    }

    @Test
    fun `utf8StringFromRange only reads the requested range`() {
        val bytes = "XXGGG198A54YY".toByteArray(Charsets.UTF_8).toUByteArray()

        assertEquals("GGG198A54", bytes.utf8StringFromRange(2..10))
    }

    @Test
    fun `utf8StringFromRange uppercases by default`() {
        val bytes = "abc123".toByteArray(Charsets.UTF_8).toUByteArray() + ubyteArrayOf(0u)

        assertEquals("ABC123", bytes.utf8StringFromRange(bytes.indices))
    }

    @Test
    fun `utf8StringFromRange preserves case when uppercase is false`() {
        val bytes = "v1.2.3".toByteArray(Charsets.UTF_8).toUByteArray() + ubyteArrayOf(0u)

        assertEquals("v1.2.3", bytes.utf8StringFromRange(bytes.indices, uppercase = false))
    }
}
