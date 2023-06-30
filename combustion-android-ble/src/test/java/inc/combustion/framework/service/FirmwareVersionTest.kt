/*
 * Project: Combustion Inc. Android Framework
 * File: FirmwareVersionTest.kt
 * Author:
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

package inc.combustion.framework.service

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FirmwareVersionTest {
    @Test
    fun `Version Equality No Commit`() {
        assertEquals(FirmwareVersion(1, 2, 3, null), FirmwareVersion(1, 2, 3, null))

        assertTrue(FirmwareVersion(0, 0, 0, null) < FirmwareVersion(0, 0, 1, null))
        assertTrue(FirmwareVersion(0, 0, 1, null) < FirmwareVersion(0, 1, 0, null))
        assertTrue(FirmwareVersion(0, 1, 0, null) < FirmwareVersion(1, 0, 0, null))
    }

    @Test
    fun `Version Equality With Commit`() {
        assertEquals(FirmwareVersion(1, 2, 3, 0), FirmwareVersion(1, 2, 3, 0))

        // Non-null should be greater than null.
        assertTrue(FirmwareVersion(1, 2, 3, null) < FirmwareVersion(1, 2, 3, 0))
        assertTrue(FirmwareVersion(1, 2, 3, 0) < FirmwareVersion(1, 2, 3, 1))
    }

    @Test
    fun `Version Equality Ignores SHA`() {
        assertEquals(FirmwareVersion(1, 2, 3, 4, null), FirmwareVersion(1, 2, 3, 4, null))
        assertEquals(FirmwareVersion(1, 2, 3, 4, "hello"), FirmwareVersion(1, 2, 3, 4, null))
        assertEquals(FirmwareVersion(1, 2, 3, 4, "hello"), FirmwareVersion(1, 2, 3, 4, "goodbye"))
    }

    @Test
    fun `fromString Simple`() {
        val v = FirmwareVersion.fromString("v1.2.3")

        assertEquals(1, v.major)
        assertEquals(2, v.minor)
        assertEquals(3, v.patch)
        assertEquals(null, v.commit)
        assertEquals(null, v.sha)
    }

    @Test
    fun `fromString With Commit and SHA`() {
        val v = FirmwareVersion.fromString("v1.2.3-4-g123abc")

        assertEquals(1, v.major)
        assertEquals(2, v.minor)
        assertEquals(3, v.patch)
        assertEquals(4, v.commit)
        assertEquals("g123abc", v.sha)
    }

    @Test
    fun `fromString With _devBoard`() {
        val v = FirmwareVersion.fromString("v1.2.3_devBoard")

        assertEquals(1, v.major)
        assertEquals(2, v.minor)
        assertEquals(3, v.patch)
        assertEquals(null, v.commit)
        assertEquals(null, v.sha)
    }

    @Test
    fun `fromString With Commit and SHA and `() {
        val v = FirmwareVersion.fromString("v1.2.3-4-g123abc_InstantReadData4Hz_Release")

        assertEquals(1, v.major)
        assertEquals(2, v.minor)
        assertEquals(3, v.patch)
        assertEquals(4, v.commit)
        assertEquals("g123abc", v.sha)
    }
}
