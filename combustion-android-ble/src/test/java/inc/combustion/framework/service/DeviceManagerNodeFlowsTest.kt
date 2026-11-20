/*
 * Project: Combustion Inc. Android Framework
 * File: DeviceManagerNodeFlowsTest.kt
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

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The per-Node flows must be shared: a new flow per call would leave a sharing coroutine behind
 * every time, e.g. on every recomposition of a screen that calls them.
 */
class DeviceManagerNodeFlowsTest {
    private val deviceManager = DeviceManager(DeviceManager.Settings())

    @Test
    fun `the same flow is returned for the same Node, whatever the serial number's case`() {
        assertSame(deviceManager.nodeLinkedDevicesFlow("CR10007379"), deviceManager.nodeLinkedDevicesFlow("cr10007379"))
        assertSame(deviceManager.nodeUnlinkedDevicesFlow("CR10007379"), deviceManager.nodeUnlinkedDevicesFlow("CR10007379"))
        assertSame(deviceManager.nodeProximityLinkingFlow("CR10007379"), deviceManager.nodeProximityLinkingFlow("CR10007379"))
    }

    @Test
    fun `different Nodes get different flows`() {
        assertNotSame(deviceManager.nodeLinkedDevicesFlow("CR10007379"), deviceManager.nodeLinkedDevicesFlow("DS10001234"))
    }

    @Test
    fun `flows are null before the framework is initialized`() {
        assertNull(deviceManager.nodeLinkedDevicesFlow("CR10007379").value)
        assertNull(deviceManager.nodeProximityLinkingFlow("CR10007379").value)
    }
}
