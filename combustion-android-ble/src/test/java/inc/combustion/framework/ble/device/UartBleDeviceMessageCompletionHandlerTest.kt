/*
 * Project: Combustion Inc. Android Framework
 * File: UartBleDeviceMessageCompletionHandlerTest.kt
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

package inc.combustion.framework.ble.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the [UartBleDevice.MessageCompletionHandler] rewrite from a single pending-wait slot to
 * a keyed dictionary, needed so a command retry can register a new wait without cancelling an
 * earlier attempt that's still outstanding. No dedicated tests existed for this class before the
 * rewrite; these are written against the documented/inferred contract of the original
 * implementation, preserved as closely as possible except where noted (see the class's own KDoc
 * for the one deliberate behavior change: different request IDs no longer cancel-and-replace each
 * other, they coexist).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UartBleDeviceMessageCompletionHandlerTest {

    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `handled invokes the callback and returns true for a matching request id`() = runTest {
        val handler = UartBleDevice.MessageCompletionHandler()
        var received: Pair<Boolean, Any?>? = null

        handler.wait(scope = backgroundScope, duration = 10_000, reqId = 1u) { success, data ->
            received = success to data
        }

        assertTrue(handler.handled(result = true, data = "payload", reqId = 1u))
        assertEquals(true to "payload", received)
        assertFalse(handler.isWaiting)
    }

    @Test
    fun `handled returns false and does not invoke the callback for a non-matching request id`() =
        runTest {
            val handler = UartBleDevice.MessageCompletionHandler()
            var invoked = false

            handler.wait(scope = backgroundScope, duration = 10_000, reqId = 1u) { _, _ ->
                invoked = true
            }

            assertFalse(handler.handled(result = true, data = null, reqId = 2u))
            assertFalse(invoked)
            // The original wait for reqId 1 is still pending, untouched.
            assertTrue(handler.isWaiting)
            assertTrue(handler.handled(result = true, data = null, reqId = 1u))
            assertTrue(invoked)
        }

    @Test
    fun `wait times out and invokes the callback with false and null after duration elapses`() =
        runTest {
            val handler = UartBleDevice.MessageCompletionHandler()
            var received: Pair<Boolean, Any?>? = null

            handler.wait(scope = backgroundScope, duration = 1000, reqId = 1u) { success, data ->
                received = success to data
            }

            assertTrue(handler.isWaiting)
            advanceTimeBy(1001)
            runCurrent()

            assertEquals(false to null, received)
            assertFalse(handler.isWaiting)
        }

    @Test
    fun `handled clears requestId once the last pending wait completes`() = runTest {
        // Regression test: the previous single-slot handler's cleanup() reset requestId on every
        // completion path (both handled() and timeout) -- restoring that invariant for the keyed
        // rewrite, since a stale non-null requestId on a non-waiting handler is a footgun for
        // callers that read it back (see the class's own KDoc).
        val handler = UartBleDevice.MessageCompletionHandler()

        handler.wait(scope = backgroundScope, duration = 10_000, reqId = 1u) { _, _ -> }
        assertEquals(1u, handler.requestId)

        handler.handled(result = true, data = null, reqId = 1u)

        assertNull(handler.requestId)
    }

    @Test
    fun `wait times out and clears requestId once the last pending wait elapses`() = runTest {
        val handler = UartBleDevice.MessageCompletionHandler()

        handler.wait(scope = backgroundScope, duration = 1000, reqId = 1u) { _, _ -> }
        assertEquals(1u, handler.requestId)

        advanceTimeBy(1001)
        runCurrent()

        assertNull(handler.requestId)
    }

    @Test
    fun `requestId is only cleared once pending drains to empty, not on every individual completion`() =
        runTest {
            val handler = UartBleDevice.MessageCompletionHandler()

            handler.wait(scope = backgroundScope, duration = 10_000, reqId = 1u) { _, _ -> }
            handler.wait(scope = backgroundScope, duration = 10_000, reqId = 2u) { _, _ -> }
            assertEquals(2u, handler.requestId)

            // Completing the most-recently-registered wait first -- reqId 1 is still pending, so
            // requestId must not be cleared out from under it.
            handler.handled(result = true, data = null, reqId = 2u)
            assertTrue(handler.isWaiting)
            assertEquals(2u, handler.requestId)

            // Now the last pending wait completes -- requestId is finally cleared.
            handler.handled(result = true, data = null, reqId = 1u)
            assertFalse(handler.isWaiting)
            assertNull(handler.requestId)
        }

    @Test
    fun `a second wait for the same request id is rejected immediately while the first is pending`() =
        runTest {
            val handler = UartBleDevice.MessageCompletionHandler()
            var firstInvoked = false
            var secondResult: Pair<Boolean, Any?>? = null

            handler.wait(scope = backgroundScope, duration = 10_000, reqId = 1u) { _, _ ->
                firstInvoked = true
            }
            handler.wait(scope = backgroundScope, duration = 10_000, reqId = 1u) { success, data ->
                secondResult = success to data
            }

            // Second call rejected synchronously, first call untouched.
            assertEquals(false to null, secondResult)
            assertFalse(firstInvoked)
            assertTrue(handler.handled(result = true, data = "still-first", reqId = 1u))
            assertTrue(firstInvoked)
        }

    @Test
    fun `a second wait for a null request id is rejected while the first null-keyed wait is pending`() =
        runTest {
            val handler = UartBleDevice.MessageCompletionHandler()
            var firstInvoked = false
            var secondResult: Pair<Boolean, Any?>? = null

            handler.wait(scope = backgroundScope, duration = 10_000, reqId = null) { _, _ ->
                firstInvoked = true
            }
            handler.wait(scope = backgroundScope, duration = 10_000, reqId = null) { success, data ->
                secondResult = success to data
            }

            assertEquals(false to null, secondResult)
            assertFalse(firstInvoked)
            assertTrue(handler.handled(result = true, data = null, reqId = null))
            assertTrue(firstInvoked)
        }

    @Test
    fun `waits for different request ids coexist independently instead of replacing each other`() =
        runTest {
            val handler = UartBleDevice.MessageCompletionHandler()
            var firstResult: Pair<Boolean, Any?>? = null
            var secondResult: Pair<Boolean, Any?>? = null

            handler.wait(scope = backgroundScope, duration = 10_000, reqId = 1u) { success, data ->
                firstResult = success to data
            }
            handler.wait(scope = backgroundScope, duration = 10_000, reqId = 2u) { success, data ->
                secondResult = success to data
            }

            // Both remain pending -- neither was silently cancelled by the other.
            assertTrue(handler.isWaiting)
            assertNull(firstResult)
            assertNull(secondResult)

            assertTrue(handler.handled(result = true, data = "for-2", reqId = 2u))
            assertEquals(true to "for-2", secondResult)
            assertNull(firstResult)
            // First is still independently pending after the second completed.
            assertTrue(handler.isWaiting)

            assertTrue(handler.handled(result = false, data = "for-1", reqId = 1u))
            assertEquals(false to "for-1", firstResult)
            assertFalse(handler.isWaiting)
        }

    @Test
    fun `cancel clears all pending waits without invoking their callbacks`() = runTest {
        val handler = UartBleDevice.MessageCompletionHandler()
        var invoked = false

        handler.wait(scope = backgroundScope, duration = 10_000, reqId = 1u) { _, _ -> invoked = true }
        handler.wait(scope = backgroundScope, duration = 10_000, reqId = 2u) { _, _ -> invoked = true }

        handler.cancel()

        assertFalse(handler.isWaiting)
        assertFalse(invoked)
        assertNull(handler.requestId)
        // A later response for either request id no longer matches anything.
        assertFalse(handler.handled(result = true, data = null, reqId = 1u))
        assertFalse(handler.handled(result = true, data = null, reqId = 2u))
    }

    @Test
    fun `a re-entrant wait from within the completion callback is accepted, not rejected`() =
        runTest {
            val handler = UartBleDevice.MessageCompletionHandler()
            var secondInvoked = false

            handler.wait(scope = backgroundScope, duration = 10_000, reqId = 1u) { _, _ ->
                // Immediately retry with the same request id, from inside the callback -- this
                // must succeed, not be rejected by a stale still-registered entry for reqId 1.
                handler.wait(scope = backgroundScope, duration = 10_000, reqId = 1u) { _, _ ->
                    secondInvoked = true
                }
            }

            handler.handled(result = true, data = null, reqId = 1u)
            assertTrue(handler.isWaiting)

            handler.handled(result = true, data = null, reqId = 1u)
            assertTrue(secondInvoked)
        }
}
