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
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.device.NodeBleDevice
import inc.combustion.framework.ble.uart.meatnet.GenericNodeRequest
import inc.combustion.framework.ble.uart.meatnet.GenericNodeResponse
import inc.combustion.framework.ble.uart.meatnet.NodeReadFeatureFlagsResponse
import inc.combustion.framework.service.utils.StateFlowMutableMap
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.random.nextUInt

internal class WiFiNodesManager(
    private val getNodeDevice: (deviceId: String) -> NodeBleDevice,
) {
    private val connectedWiFiNodes = StateFlowMutableMap<String, NodeBleDevice>()
    private val nonWiFiNodes: MutableSet<String> = mutableSetOf()
    private val nodeToMutexMap: MutableMap<String, Semaphore> = ConcurrentHashMap()
    private val coroutineScope = CoroutineScope(SupervisorJob())

    private lateinit var lifecycleChildScope: CoroutineScope
    private lateinit var lifecycleChildJob: Job

    fun initialize(owner: LifecycleOwner) {
        lifecycleChildJob = SupervisorJob()
        lifecycleChildScope =
            CoroutineScope(owner.lifecycleScope.coroutineContext + lifecycleChildJob)
    }

    fun clear() {
        lifecycleChildJob.cancel()
        connectedWiFiNodes.clear()
    }

    val discoveredWiFiNodesFlow: StateFlow<List<String>> by lazy {
        val mutableDiscoveredWiFiNodesFlow = MutableStateFlow<List<String>>(emptyList())
        connectedWiFiNodes.stateFlow.onEach { nodes ->
            mutableDiscoveredWiFiNodesFlow.value = nodes.keys.toList()
        }.launchIn(coroutineScope)
        mutableDiscoveredWiFiNodesFlow.asStateFlow()
    }

    private fun getNodeMutex(deviceId: String): Semaphore =
        nodeToMutexMap[deviceId] ?: (Semaphore(1).also {
            nodeToMutexMap[deviceId] = it
        })

    suspend fun sendNodeRequestRequiringWiFi(
        deviceId: String,
        request: GenericNodeRequest,
        completionHandler: (Boolean, GenericNodeResponse?) -> Unit,
    ) {
        connectedWiFiNodes[deviceId]?.let {
            val mutex = getNodeMutex(deviceId)
            mutex.acquire()
            it.sendNodeRequest(request) { status, data ->
                mutex.release()
                completionHandler(status, data as? GenericNodeResponse)
            }
        } ?: run {
            completionHandler(false, null)
        }
    }

    fun subscribeToNodeFlow(deviceManager: BleManager) {
        lifecycleChildScope.launch(CoroutineName("NodeConnectionFlow")) {
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