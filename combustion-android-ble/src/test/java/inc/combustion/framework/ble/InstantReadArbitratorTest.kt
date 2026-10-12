/*
 * Project: Combustion Inc. Android Framework
 * File: InstantReadArbitratorTest.kt
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
package inc.combustion.framework.ble

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class InstantReadArbitratorTest {

    private var nowMs = 100_000L
    private val tested = InstantReadArbitrator(clock = { nowMs })

    // links are identified by object identity
    private val direct = Any()
    private val nodeA = Any()
    private val nodeB = Any()

    private fun advance(ms: Long) {
        nowMs += ms
    }

    @Test
    fun `the first data is used from any link`() {
        assertTrue(tested.shouldUpdate(nodeA, hopCount = 2u))
    }

    @Test
    fun `a direct link is always used`() {
        assertTrue(tested.shouldUpdate(nodeA, hopCount = 0u))
        assertTrue(tested.shouldUpdate(direct, hopCount = null))
        assertTrue(tested.shouldUpdate(direct, hopCount = null))
    }

    @Test
    fun `a direct link locks out relayed data, even from a node one hop away`() {
        assertTrue(tested.shouldUpdate(direct, hopCount = null))

        assertFalse(tested.shouldUpdate(nodeA, hopCount = 0u))
    }

    @Test
    fun `the current link keeps being used`() {
        assertTrue(tested.shouldUpdate(nodeA, hopCount = 1u))
        advance(500)

        assertTrue(tested.shouldUpdate(nodeA, hopCount = 1u))
    }

    @Test
    fun `a link with a lower hop count takes over`() {
        assertTrue(tested.shouldUpdate(nodeA, hopCount = 2u))

        assertTrue(tested.shouldUpdate(nodeB, hopCount = 1u))
        // and is now the current link: the previous one is ignored
        assertFalse(tested.shouldUpdate(nodeA, hopCount = 2u))
    }

    @Test
    fun `another link at an equal hop count is ignored while the current one is active`() {
        assertTrue(tested.shouldUpdate(nodeA, hopCount = 1u))

        assertFalse(tested.shouldUpdate(nodeB, hopCount = 1u))
    }

    @Test
    fun `another link at a worse hop count is ignored while the current one is active`() {
        assertTrue(tested.shouldUpdate(nodeA, hopCount = 1u))

        assertFalse(tested.shouldUpdate(nodeB, hopCount = 2u))
    }

    @Test
    fun `any link takes over once nothing has been used for the lock timeout`() {
        assertTrue(tested.shouldUpdate(direct, hopCount = null))
        advance(InstantReadArbitrator.LOCK_TIMEOUT_MS - 1)
        assertFalse(tested.shouldUpdate(nodeA, hopCount = 2u))

        advance(1)

        assertTrue(tested.shouldUpdate(nodeA, hopCount = 2u))
        // nodeA is now the current link, so the lockout restarts against it
        assertFalse(tested.shouldUpdate(nodeB, hopCount = 2u))
    }

    @Test
    fun `the lock timeout runs from the last data used, not from the link's first`() {
        assertTrue(tested.shouldUpdate(nodeA, hopCount = 1u))
        advance(800)
        assertTrue(tested.shouldUpdate(nodeA, hopCount = 1u))
        advance(800)

        // 1.6 s after nodeA's first data, but only 0.8 s after its last
        assertFalse(tested.shouldUpdate(nodeB, hopCount = 1u))
    }
}
