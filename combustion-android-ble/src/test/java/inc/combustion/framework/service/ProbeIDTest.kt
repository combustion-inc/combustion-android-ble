/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeIDTest.kt
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

package inc.combustion.framework.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ProbeIDTest {

    @Test
    fun `fromRaw maps 0-7 to ID1-ID8`() {
        ProbeID.entries.forEachIndexed { index, id ->
            assertEquals(id, ProbeID.fromRaw(index.toUInt()))
        }
    }

    @Test
    fun `fromRaw falls back to ID1 for values above 7`() {
        assertEquals(ProbeID.ID1, ProbeID.fromRaw(0x08u))
        assertEquals(ProbeID.ID1, ProbeID.fromRaw(0xFFu))
    }

    @Test
    fun `fromRaw falls back to ID1 for values above 0xFF rather than wrapping`() {
        assertEquals(ProbeID.ID1, ProbeID.fromRaw(0x101u))
    }
}
