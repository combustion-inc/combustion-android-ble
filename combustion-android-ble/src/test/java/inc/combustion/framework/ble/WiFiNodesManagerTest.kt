/*
 * Project: Combustion Inc. Android Framework
 * File: WiFiNodesManagerTest.kt
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

package inc.combustion.framework.ble

import android.util.Log
import inc.combustion.framework.ble.device.NodeBleDevice
import inc.combustion.framework.ble.uart.meatnet.GenericNodeRequest
import inc.combustion.framework.ble.uart.meatnet.GenericNodeResponse
import inc.combustion.framework.ble.uart.meatnet.NodeMessageType
import inc.combustion.framework.ble.uart.meatnet.NodeReadFeatureFlagsResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers [WiFiNodesManager.sendNodeRequestRequiringWiFi], which sends via [CommandCoordinator].
 * Covers both the "nothing connected" retry-then-fail path and, by driving a fake
 * [BleManager.nodeConnectionFlow] through [WiFiNodesManager.subscribeToNodeFlow], the success
 * path via a connected WiFi-capable node.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WiFiNodesManagerTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `sendNodeRequestRequiringWiFi fails immediately when no WiFi node is connected for the device, without waiting out the retry window`() =
        runTest {
            // Regression test: this must fail fast (matching the pre-CommandCoordinator
            // behavior), not retry CommandCoordinator's window out -- see
            // WiFiNodesManager.sendNodeRequestRequiringWiFi's KDoc. A deliberately large
            // timeout/retry window here proves the assertion below isn't just "it happened to
            // finish before the window closed."
            val manager = WiFiNodesManager(
                scope = backgroundScope,
                commandCoordinator = CommandCoordinator(requestTimeoutMs = 30_000, retryIntervalMs = 5_000),
                getNodeDevice = { mockk<NodeBleDevice>(relaxed = true) },
            )

            var result: Pair<Boolean, Any?>? = null
            manager.sendNodeRequestRequiringWiFi(
                deviceId = "device-1",
                request = GenericNodeRequest(ubyteArrayOf(1u, 2u), NodeMessageType.SESSION_INFO),
            ) { success, data ->
                result = success to data
            }

            // No virtual time needed to elapse at all -- returns synchronously, never touching
            // CommandCoordinator's retry/timeout machinery.
            assertEquals(0L, testScheduler.currentTime)
            assertEquals(false, result?.first)
            assertNull(result?.second)
        }

    /**
     * Drives a connected WiFi node through the real (non-mocked) path: emits a device ID on a
     * fake [BleManager.nodeConnectionFlow], which [WiFiNodesManager.subscribeToNodeFlow] observes
     * and probes via [NodeBleDevice.sendFeatureFlagRequest] -- exactly what happens in production
     * when a node with the WiFi feature flag connects.
     */
    @Test
    fun `sendNodeRequestRequiringWiFi succeeds once a WiFi-capable node connects and responds`() =
        runTest {
            val nodeConnectionFlow = MutableSharedFlow<Set<String>>()
            val deviceManager = mockk<BleManager>(relaxed = true)
            every { deviceManager.nodeConnectionFlow } returns nodeConnectionFlow

            val node = mockk<NodeBleDevice>(relaxed = true)
            every { node.deviceInfoSerialNumber } returns "device-1"
            every { node.sendFeatureFlagRequest(any(), any()) } answers {
                secondArg<((Boolean, Any?) -> Unit)?>()?.invoke(
                    true,
                    NodeReadFeatureFlagsResponse(
                        nodeSerialNumber = "device-1",
                        wifi = true,
                        success = true,
                        requestId = 1u,
                        responseId = 1u,
                        payloadLength = NodeReadFeatureFlagsResponse.PAYLOAD_LENGTH,
                    ),
                )
            }

            // Captures the callback instead of resolving it immediately -- a real NodeBleDevice
            // response arrives asynchronously (see UartBleDevice.MessageCompletionHandler), and
            // CommandCoordinator's registerAttempt must run before completion is signaled, or the
            // completion is silently lost (the same race fixed for the simulated devices -- see
            // SimulatedProbeBleDevice.sendSetPowerMode).
            val sendNodeRequestCallbacks = mutableListOf<(Boolean, Any?) -> Unit>()
            every { node.sendNodeRequest(any(), any()) } answers {
                secondArg<((Boolean, Any?) -> Unit)?>()?.let { sendNodeRequestCallbacks.add(it) }
            }

            val manager = WiFiNodesManager(
                scope = backgroundScope,
                commandCoordinator = CommandCoordinator(requestTimeoutMs = 10_000, retryIntervalMs = 5_000),
                getNodeDevice = { node },
            )
            manager.subscribeToNodeFlow(deviceManager)
            runCurrent()

            nodeConnectionFlow.emit(setOf("device-1"))
            runCurrent()

            var result: Pair<Boolean, Any?>? = null
            launch {
                manager.sendNodeRequestRequiringWiFi(
                    deviceId = "device-1",
                    request = GenericNodeRequest(ubyteArrayOf(1u, 2u), NodeMessageType.SESSION_INFO),
                ) { success, data ->
                    result = success to data
                }
            }
            runCurrent()

            assertEquals(1, sendNodeRequestCallbacks.size)
            assertNull(result)

            sendNodeRequestCallbacks[0](
                true,
                GenericNodeResponse(
                    payload = ubyteArrayOf(),
                    success = true,
                    requestId = 1u,
                    responseId = 1u,
                    payloadLength = 0u,
                    messageId = NodeMessageType.SESSION_INFO,
                ),
            )
            runCurrent()

            assertTrue(result?.first == true)
        }

    @Test
    fun `sendNodeRequestRequiringWiFi does not retry by default when a connected node never responds`() =
        runTest {
            // Regression test: retriesEnabled defaults to false for this generic, opaque-payload
            // path (unlike Engine/Gauge/Probe's set* commands) -- see the KDoc. A merely slow or
            // lost response must not cause the framework to silently re-send an arbitrary
            // consumer-supplied command.
            val nodeConnectionFlow = MutableSharedFlow<Set<String>>()
            val deviceManager = mockk<BleManager>(relaxed = true)
            every { deviceManager.nodeConnectionFlow } returns nodeConnectionFlow

            val node = mockk<NodeBleDevice>(relaxed = true)
            every { node.deviceInfoSerialNumber } returns "device-1"
            every { node.sendFeatureFlagRequest(any(), any()) } answers {
                secondArg<((Boolean, Any?) -> Unit)?>()?.invoke(
                    true,
                    NodeReadFeatureFlagsResponse(
                        nodeSerialNumber = "device-1",
                        wifi = true,
                        success = true,
                        requestId = 1u,
                        responseId = 1u,
                        payloadLength = NodeReadFeatureFlagsResponse.PAYLOAD_LENGTH,
                    ),
                )
            }

            var sendCount = 0
            every { node.sendNodeRequest(any(), any()) } answers { sendCount++ }

            val manager = WiFiNodesManager(
                scope = backgroundScope,
                commandCoordinator = CommandCoordinator(requestTimeoutMs = 3_000, retryIntervalMs = 1_000),
                getNodeDevice = { node },
            )
            manager.subscribeToNodeFlow(deviceManager)
            runCurrent()

            nodeConnectionFlow.emit(setOf("device-1"))
            runCurrent()

            var result: Pair<Boolean, Any?>? = null
            launch {
                manager.sendNodeRequestRequiringWiFi(
                    deviceId = "device-1",
                    request = GenericNodeRequest(ubyteArrayOf(1u, 2u), NodeMessageType.SESSION_INFO),
                ) { success, data ->
                    result = success to data
                }
            }
            runCurrent()
            assertEquals(1, sendCount)

            // Past what would have been the first retry interval if retries were enabled.
            advanceTimeBy(1001)
            runCurrent()
            assertEquals(1, sendCount)

            // Out to the overall timeout -- still only ever sent once.
            advanceTimeBy(2000)
            runCurrent()
            assertEquals(1, sendCount)
            assertEquals(false, result?.first)
        }

    @Test
    fun `sendNodeRequestRequiringWiFi retries when a caller opts in with retriesEnabled = true`() =
        runTest {
            val nodeConnectionFlow = MutableSharedFlow<Set<String>>()
            val deviceManager = mockk<BleManager>(relaxed = true)
            every { deviceManager.nodeConnectionFlow } returns nodeConnectionFlow

            val node = mockk<NodeBleDevice>(relaxed = true)
            every { node.deviceInfoSerialNumber } returns "device-1"
            every { node.sendFeatureFlagRequest(any(), any()) } answers {
                secondArg<((Boolean, Any?) -> Unit)?>()?.invoke(
                    true,
                    NodeReadFeatureFlagsResponse(
                        nodeSerialNumber = "device-1",
                        wifi = true,
                        success = true,
                        requestId = 1u,
                        responseId = 1u,
                        payloadLength = NodeReadFeatureFlagsResponse.PAYLOAD_LENGTH,
                    ),
                )
            }

            var sendCount = 0
            every { node.sendNodeRequest(any(), any()) } answers { sendCount++ }

            val manager = WiFiNodesManager(
                scope = backgroundScope,
                commandCoordinator = CommandCoordinator(requestTimeoutMs = 3_000, retryIntervalMs = 1_000),
                getNodeDevice = { node },
            )
            manager.subscribeToNodeFlow(deviceManager)
            runCurrent()

            nodeConnectionFlow.emit(setOf("device-1"))
            runCurrent()

            var result: Pair<Boolean, Any?>? = null
            launch {
                manager.sendNodeRequestRequiringWiFi(
                    deviceId = "device-1",
                    request = GenericNodeRequest(ubyteArrayOf(1u, 2u), NodeMessageType.SESSION_INFO),
                    retriesEnabled = true,
                ) { success, data ->
                    result = success to data
                }
            }
            runCurrent()
            assertEquals(1, sendCount)

            advanceTimeBy(1001)
            runCurrent()
            assertEquals(2, sendCount)

            // Out to the overall 3s timeout.
            advanceTimeBy(2000)
            runCurrent()
            assertEquals(false, result?.first)
        }
}
