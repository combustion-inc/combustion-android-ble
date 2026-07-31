/*
 * Project: Combustion Inc. Android Framework
 * File: WifiNodesManager.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2025. Combustion Inc.
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
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.device.NodeBleDevice
import inc.combustion.framework.ble.uart.meatnet.GenericNodeRequest
import inc.combustion.framework.ble.uart.meatnet.GenericNodeResponse
import inc.combustion.framework.ble.uart.meatnet.NodeReadFeatureFlagsResponse
import inc.combustion.framework.service.utils.StateFlowMutableMap
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.random.nextUInt

internal class WiFiNodesManager(
    private val scope: CoroutineScope,
    private val commandCoordinator: CommandCoordinator = CommandCoordinator(),
    private val getNodeDevice: (deviceId: String) -> NodeBleDevice,
) {
    private val connectedWiFiNodes = StateFlowMutableMap<String, NodeBleDevice>()
    private val nonWiFiNodes: MutableSet<String> = mutableSetOf()
    private val nodeToMutexMap: MutableMap<String, Semaphore> = ConcurrentHashMap()


    fun clear() {
        connectedWiFiNodes.clear()
    }

    val discoveredWiFiNodesFlow: StateFlow<List<String>> by lazy {
        val mutableDiscoveredWiFiNodesFlow = MutableStateFlow<List<String>>(emptyList())
        connectedWiFiNodes.stateFlow.onEach { nodes ->
            mutableDiscoveredWiFiNodesFlow.value = nodes.keys.toList()
        }.launchIn(scope)
        mutableDiscoveredWiFiNodesFlow.asStateFlow()
    }

    private fun getNodeMutex(deviceId: String): Semaphore =
        nodeToMutexMap[deviceId] ?: (Semaphore(1).also {
            nodeToMutexMap[deviceId] = it
        })

    /**
     * Sends via [commandCoordinator]. Unlike Engine/Gauge/Probe's `set*` functions, [request] is
     * a generic, consumer-supplied payload with no
     * corresponding [SpecializedDeviceStatus] field to confirm against, so [isConfirmed] is always
     * `false` -- this can only complete via a matching response (see [CommandCoordinator.completeAttempt]),
     * not [CommandCoordinator.confirmCommandStatus].
     *
     * A fresh [GenericNodeRequest] (same payload, a new request ID) is built on every attempt --
     * [request]'s own `requestId` is unused, since callers can only construct one via the public
     * 2-arg constructor, which always sets it to null; reusing that null on every retry would key
     * every attempt to the same [NodeBleDevice] response handler slot, which rejects a repeat wait
     * on the same key while the previous one is still outstanding (see
     * [UartBleDevice.MessageCompletionHandler.wait]).
     *
     * [getNodeMutex] still serializes concurrent calls for the same [deviceId] as before, but now
     * held for the duration of the whole retry cycle rather than a single attempt.
     */
    suspend fun sendNodeRequestRequiringWiFi(
        deviceId: String,
        request: GenericNodeRequest,
        completionHandler: (Boolean, GenericNodeResponse?) -> Unit,
    ) {
        val mutex = getNodeMutex(deviceId)
        mutex.acquire()
        try {
            var responseData: GenericNodeResponse? = null

            val result = commandCoordinator.sendRoutedCommand(
                targetSerialNumber = deviceId,
                send = {
                    val node = connectedWiFiNodes[deviceId]
                    if (node == null) {
                        emptySet()
                    } else {
                        val requestId = Random.nextUInt()
                        val key = CommandAttemptKey.Node(request.messageId, requestId)
                        val freshRequest = GenericNodeRequest(
                            outgoingPayload = request.outgoingPayload,
                            nodeSerialNumber = request.nodeSerialNumber,
                            requestId = requestId,
                            payloadLength = request.payloadLength,
                            messageId = request.messageId,
                        )

                        node.sendNodeRequest(freshRequest) { success, data ->
                            responseData = data as? GenericNodeResponse
                            if (success) {
                                commandCoordinator.completeAttempt(key, success = true)
                            }
                        }

                        setOf(key)
                    }
                },
                isConfirmed = { false },
            )

            completionHandler(result == CommandResult.SUCCESS, responseData)
        } finally {
            mutex.release()
        }
    }

    fun subscribeToNodeFlow(deviceManager: BleManager) {
        scope.launch(CoroutineName("NodeConnectionFlow")) {
            deviceManager.nodeConnectionFlow
                .collect { deviceIds ->
                    deviceIds.forEach { deviceId ->
                        val node = getNodeDevice(deviceId)
                        updateConnectedWiFiNodes(node)
                    }
                }
        }
    }

    fun discoveredWiFiNodes(): List<String> = discoveredWiFiNodesFlow.value

    private suspend fun updateConnectedWiFiNodes(node: NodeBleDevice) {
        if (node.deviceInfoSerialNumber == null) {
            node.readSerialNumber()
        }
        node.deviceInfoSerialNumber?.let {
            if (!connectedWiFiNodes.containsKey(it) && !nonWiFiNodes.contains(it)) {
                node.sendFeatureFlagRequest(Random.nextUInt()) { success: Boolean, data: Any? ->
                    if (success) {
                        val featureFlags =
                            data as NodeReadFeatureFlagsResponse
                        if (featureFlags.wifi) {
                            Log.d(
                                LOG_TAG,
                                "Node $it supports WiFi feature flag: add to connectedWiFiNodes",
                            )
                            connectedWiFiNodes[it] = node
                            UUID.randomUUID().toString().let { key ->
                                node.observeDisconnected(key) {
                                    Log.d(
                                        LOG_TAG,
                                        "Node $it disconnected: remove from connectedWiFiNodes",
                                    )
                                    connectedWiFiNodes.remove(it)
                                    node.removeDisconnectedObserver(key)
                                }
                            }
                        } else {
                            Log.d(LOG_TAG, "Node doesn't support WiFi feature flag: $it")
                            nonWiFiNodes.add(it)
                        }
                    }
                }
            }
        }
    }
}