/*
 * Project: Combustion Inc. Android Framework
 * File: ShareSelectedTest.kt
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

package inc.combustion.framework.service.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DeviceManager's per-Node flows rely on [shareSelected] on Dispatchers.Unconfined passing a value
 * on before its setter returns: a linking read's completion handler reads the table from them.
 */
class ShareSelectedTest {
    private class Source(val flow: MutableStateFlow<Int?>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `follows the flow selected from the current source`() {
        val first = MutableStateFlow<Int?>(1)
        val source = MutableStateFlow<Source?>(Source(first))
        val shared = source.shareSelected(scope) { it.flow }
        assertEquals(1, shared.value)

        source.value = Source(MutableStateFlow(5))
        assertEquals(5, shared.value)
        first.value = 2
        assertEquals("the previous source is no longer followed", 5, shared.value)

        source.value = null
        assertNull(shared.value)
    }

    @Test
    fun `a value set on a background thread is shared before its setter returns`() = runBlocking(Dispatchers.Default) {
        val selected = MutableStateFlow<Int?>(null)
        val shared = MutableStateFlow<Source?>(Source(selected)).shareSelected(scope) { it.flow }

        repeat(1_000) { i ->
            selected.value = i
            assertEquals(i, shared.value)
        }
    }
}
