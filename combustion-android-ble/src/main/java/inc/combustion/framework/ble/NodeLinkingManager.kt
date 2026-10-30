/*
 * Project: Combustion Inc. Android Framework
 * File: NodeLinkingManager.kt
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
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.device.NodeBleDevice
import inc.combustion.framework.ble.uart.meatnet.NodeHeartbeatRequest
import inc.combustion.framework.ble.uart.meatnet.NodeLinkDeviceRequest
import inc.combustion.framework.ble.uart.meatnet.NodeLinkingTablePageResponse
import inc.combustion.framework.ble.uart.meatnet.NodeLinkingWire
import inc.combustion.framework.ble.uart.meatnet.NodeLinkingWire.toTimestamp
import inc.combustion.framework.ble.uart.meatnet.NodeMessageType
import inc.combustion.framework.ble.uart.meatnet.NodeReadLinkingTableRequest
import inc.combustion.framework.ble.uart.meatnet.NodeReadProximityLinkingRequest
import inc.combustion.framework.ble.uart.meatnet.NodeReadProximityLinkingResponse
import inc.combustion.framework.ble.uart.meatnet.NodeRequest
import inc.combustion.framework.ble.uart.meatnet.NodeResponse
import inc.combustion.framework.ble.uart.meatnet.NodeSetProximityLinkingRequest
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.NodeHeartbeat
import inc.combustion.framework.service.NodeLinkingResult
import inc.combustion.framework.service.NodeLinkingTableEntry
import inc.combustion.framework.service.NodeLinkingTableProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.random.nextUInt

/**
 * The two linking tables a MeatNet Node keeps.
 */
internal enum class LinkingTableKind(val messageType: NodeMessageType) {
    LINKED(NodeMessageType.READ_LINKED_DEVICES),
    UNLINKED(NodeMessageType.READ_UNLINKED_DEVICES),
}

/**
 * Checks the pages of one linking-table read in order and collects their entries, following the
 * MeatNet Node BLE specification's "Read Linked Devices (0x73)": request page 0, then each next
 * page until `page == totalPages - 1`. Mirrors iOS's `LinkingTableReadOperation.receive`.
 *
 * An empty table may be reported with totalPages 0 or 1 (the specification doesn't say which);
 * both are accepted.
 */
internal class LinkingTableReader {
    sealed class Step {
        /** The page was valid; request [nextPage] next. */
        data class Continue(val nextPage: Int, val progress: NodeLinkingTableProgress) : Step()

        /** The page was valid and was the last one; [entries] is the full table. */
        data class Complete(val entries: List<NodeLinkingTableEntry>, val progress: NodeLinkingTableProgress) : Step()

        /** The page was invalid or inconsistent with earlier pages; the read failed. */
        data class Failed(val reason: String) : Step()
    }

    private val entries = mutableListOf<NodeLinkingTableEntry>()
    private var totalPages: Int? = null
    private var totalCount: Int? = null

    /**
     * Checks [page], received in response to a request for [requestedPage]. [page] is null if the
     * response was malformed.
     */
    fun accept(requestedPage: Int, page: NodeLinkingWire.Page?): Step {
        if (page == null) return Step.Failed("malformed page $requestedPage")
        if (page.page != requestedPage) {
            return Step.Failed("requested page $requestedPage but got ${page.page}")
        }
        if (requestedPage == 0) {
            totalPages = page.totalPages
            totalCount = page.totalCount
        }
        if (page.totalPages != totalPages || page.totalCount != totalCount) {
            return Step.Failed(
                "page $requestedPage reports ${page.totalPages} pages and ${page.totalCount} " +
                        "entries, page 0 reported $totalPages and $totalCount",
            )
        }
        if (page.entries.size > NodeLinkingWire.MAX_ENTRIES_PER_PAGE) {
            return Step.Failed("page $requestedPage has ${page.entries.size} entries")
        }

        val isEmptyTable = requestedPage == 0 && page.totalCount == 0 &&
                page.entries.isEmpty() && page.totalPages <= 1
        if (!isEmptyTable && requestedPage >= page.totalPages) {
            return Step.Failed("page $requestedPage is past the last page (${page.totalPages})")
        }

        val nextEntries = entries + page.entries
        if (nextEntries.size > page.totalCount) {
            return Step.Failed("more than ${page.totalCount} entries")
        }
        val isComplete = isEmptyTable || requestedPage + 1 == page.totalPages
        if (isComplete && nextEntries.size != page.totalCount) {
            return Step.Failed("${nextEntries.size} entries but the table has ${page.totalCount}")
        }

        entries.clear()
        entries.addAll(nextEntries)
        val progress = NodeLinkingTableProgress(
            entries = nextEntries,
            pagesReceived = if (isEmptyTable && page.totalPages == 0) 0 else requestedPage + 1,
            totalPages = page.totalPages,
            totalCount = page.totalCount,
        )
        return if (isComplete) {
            Step.Complete(nextEntries, progress)
        } else {
            Step.Continue(requestedPage + 1, progress)
        }
    }
}

/**
 * Sends the MeatNet linking requests (0x73-0x7A) to Nodes the phone is connected to, and publishes
 * each Node's linking tables, its proximity-linking setting, and the heartbeats of every Node.
 *
 * Linking requests have no destination field, and the specification describes each one as acting
 * on "this Node", so they're only sent to the Node they're meant for: every command needs a direct
 * connection to it, and fails straight away without one, like
 * [WiFiNodesManager.sendNodeRequestRequiringWiFi]. iOS behaves the same in practice: its MeatNet
 * fallback looks for connected Nodes relaying the target by serial number, which never matches a
 * Node, so it sends nothing.
 *
 * Every request goes through [CommandCoordinator] with retries enabled: the reads have no side
 * effects, and link, unlink and set proximity linking set a state, so sending one again is
 * harmless. A request the Node answers with failure fails straight away instead: firmware refuses
 * Link Device and Unlink Device when its link table is full (every unlink also takes a slot) or the
 * product type is invalid, and retrying doesn't change that. A request whose Node disconnects
 * fails within [CONNECTION_CHECK_INTERVAL_MS] instead of waiting out the command timeout.
 *
 * @param findConnectedNode returns the connected Node with this serial number, or null.
 */
internal class NodeLinkingManager(
    private val scope: CoroutineScope,
    private val commandCoordinator: CommandCoordinator = CommandCoordinator(),
    private val findConnectedNode: suspend (serialNumber: String) -> NodeBleDevice?,
    private val makeRequestId: () -> UInt = { Random.nextUInt() },
) {
    private data class ReadKey(val serialNumber: String, val kind: LinkingTableKind)

    private val linkedDevices =
        ConcurrentHashMap<String, MutableStateFlow<List<NodeLinkingTableEntry>?>>()
    private val unlinkedDevices =
        ConcurrentHashMap<String, MutableStateFlow<List<NodeLinkingTableEntry>?>>()
    private val proximityLinking = ConcurrentHashMap<String, MutableStateFlow<Boolean?>>()
    private val readJobs = ConcurrentHashMap<ReadKey, Job>()

    private val _heartbeatFlow = MutableSharedFlow<NodeHeartbeat>(
        replay = 0,
        extraBufferCapacity = HEARTBEAT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Every heartbeat received, including ones identical to the previous one from the same Node:
     * consumers use each one as a sign that the sender's connections are still current.
     */
    val heartbeatFlow: SharedFlow<NodeHeartbeat> = _heartbeatFlow.asSharedFlow()

    /** The last complete linked table read from the Node, or null if none has been read. */
    fun linkedDevicesFlow(serialNumber: String): StateFlow<List<NodeLinkingTableEntry>?> =
        tableFlow(LinkingTableKind.LINKED, serialNumber).asStateFlow()

    /** The last complete unlinked table read from the Node, or null if none has been read. */
    fun unlinkedDevicesFlow(serialNumber: String): StateFlow<List<NodeLinkingTableEntry>?> =
        tableFlow(LinkingTableKind.UNLINKED, serialNumber).asStateFlow()

    /** The Node's proximity-linking setting, or null if it hasn't been read or set. */
    fun proximityLinkingFlow(serialNumber: String): StateFlow<Boolean?> =
        proximityLinking.getOrPut(serialNumber.key()) { MutableStateFlow(null) }.asStateFlow()

    /**
     * Reads every page of the Node's [kind] table. The table is published to its flow only once
     * every page has been received and checked (see [LinkingTableReader]); if the read fails,
     * the flow keeps the previous table. [progressHandler] is called after each valid page.
     *
     * Only one read per Node and table runs at a time: starting another cancels the one running,
     * whose [completionHandler] then gets [NodeLinkingResult.Cancelled].
     */
    fun readTable(
        serialNumber: String,
        kind: LinkingTableKind,
        progressHandler: ((NodeLinkingTableProgress) -> Unit)?,
        completionHandler: (NodeLinkingResult<List<NodeLinkingTableEntry>>) -> Unit,
    ) {
        val readKey = ReadKey(serialNumber.key(), kind)
        // Stays Cancelled if the read is cancelled before it finishes.
        var result: NodeLinkingResult<List<NodeLinkingTableEntry>> = NodeLinkingResult.Cancelled
        val job = scope.launch(start = CoroutineStart.LAZY) {
            result = readAllPages(serialNumber, kind, progressHandler)
        }
        // invokeOnCompletion rather than a finally block: a job cancelled before it's dispatched
        // never runs its body, but still completes.
        job.invokeOnCompletion {
            readJobs.remove(readKey, job)
            completionHandler(result)
        }
        readJobs.put(readKey, job)?.cancel()
        job.start()
    }

    /**
     * Sends Link Device (0x75) or Unlink Device (0x76) to the Node. Calls [completionHandler] with
     * [NodeLinkingResult.InvalidSerialNumber], without sending anything, if [deviceSerialNumber]
     * isn't valid for [deviceType] (see [NodeLinkingWire.encodeProductSerialNumber]).
     */
    fun setDeviceLinked(
        serialNumber: String,
        linked: Boolean,
        deviceType: CombustionProductType,
        deviceSerialNumber: String,
        timestamp: Date?,
        completionHandler: (NodeLinkingResult<Unit>) -> Unit,
    ) {
        val payload = NodeLinkingWire.encodeDevicePayload(
            deviceType,
            deviceSerialNumber,
            timestamp.toTimestamp(),
        )
        if (payload == null) {
            Log.w(
                LOG_TAG,
                "Can't ${if (linked) "link" else "unlink"} $deviceType $deviceSerialNumber: " +
                        "invalid serial number for this product type",
            )
            completionHandler(NodeLinkingResult.InvalidSerialNumber)
            return
        }
        val messageType = if (linked) NodeMessageType.LINK_DEVICE else NodeMessageType.UNLINK_DEVICE

        scope.launch {
            val result = sendToConnectedNode(serialNumber, messageType) { requestId ->
                NodeLinkDeviceRequest(serialNumber, messageType, payload, requestId)
            }
            completionHandler(result.map { })
        }
    }

    /**
     * Sends Set Proximity Linking (0x79) to the Node, and on success publishes [enabled] to its
     * proximity-linking flow before calling [completionHandler].
     */
    fun setProximityLinking(
        serialNumber: String,
        enabled: Boolean,
        completionHandler: (NodeLinkingResult<Unit>) -> Unit,
    ) {
        scope.launch {
            val result = sendToConnectedNode(serialNumber, NodeMessageType.SET_PROXIMITY_LINKING) { requestId ->
                NodeSetProximityLinkingRequest(serialNumber, enabled, requestId)
            }
            if (result.isSuccess) {
                proximityLinking.getOrPut(serialNumber.key()) { MutableStateFlow(null) }.value = enabled
            }
            completionHandler(result.map { })
        }
    }

    /**
     * Sends Read Proximity Linking (0x7A) to the Node, and on success publishes the setting to its
     * proximity-linking flow before calling [completionHandler] with it.
     */
    fun readProximityLinking(
        serialNumber: String,
        completionHandler: (NodeLinkingResult<Boolean>) -> Unit,
    ) {
        scope.launch {
            val sent = sendToConnectedNode(serialNumber, NodeMessageType.READ_PROXIMITY_LINKING) { requestId ->
                NodeReadProximityLinkingRequest(serialNumber, requestId)
            }
            val result = when (sent) {
                is NodeLinkingResult.Success -> (sent.value as? NodeReadProximityLinkingResponse)?.enabled
                    ?.let { NodeLinkingResult.Success(it) }
                    ?: NodeLinkingResult.InvalidResponse

                else -> sent.asFailure()
            }
            if (result is NodeLinkingResult.Success) {
                proximityLinking.getOrPut(serialNumber.key()) { MutableStateFlow(null) }.value = result.value
            }
            completionHandler(result)
        }
    }

    fun handleHeartbeat(request: NodeHeartbeatRequest) {
        val serialNumber = request.serialNumber ?: return
        _heartbeatFlow.tryEmit(
            NodeHeartbeat(
                serialNumber = serialNumber,
                macAddress = request.macAddress,
                productType = request.productType,
                hopCount = request.hopCount,
                inbound = request.inbound,
                connections = request.connectionDetails.map {
                    NodeHeartbeat.Connection(
                        present = it.present,
                        serialNumber = it.serialNumber,
                        productType = it.productType,
                        rssi = it.rssi,
                    )
                },
            )
        )
    }

    /**
     * Cancels running reads and forgets every Node's tables and setting. Flows already handed out
     * stay valid and go back to null.
     */
    fun clear() {
        readJobs.values.forEach { it.cancel() }
        readJobs.clear()
        linkedDevices.values.forEach { it.value = null }
        unlinkedDevices.values.forEach { it.value = null }
        proximityLinking.values.forEach { it.value = null }
    }

    private suspend fun readAllPages(
        serialNumber: String,
        kind: LinkingTableKind,
        progressHandler: ((NodeLinkingTableProgress) -> Unit)?,
    ): NodeLinkingResult<List<NodeLinkingTableEntry>> {
        val reader = LinkingTableReader()
        var page = 0
        while (true) {
            val sent = sendToConnectedNode(serialNumber, kind.messageType) { requestId ->
                NodeReadLinkingTableRequest(serialNumber, kind.messageType, page, requestId)
            }
            val response = when (sent) {
                is NodeLinkingResult.Success -> sent.value as? NodeLinkingTablePageResponse
                    ?: return NodeLinkingResult.InvalidResponse

                else -> return sent.asFailure()
            }

            when (val step = reader.accept(page, response.page)) {
                is LinkingTableReader.Step.Failed -> {
                    Log.w(LOG_TAG, "Reading $kind table of $serialNumber failed: ${step.reason}")
                    return NodeLinkingResult.InvalidResponse
                }

                is LinkingTableReader.Step.Continue -> {
                    // A read replaced by a newer one must not report progress either.
                    currentCoroutineContext().ensureActive()
                    progressHandler?.invoke(step.progress)
                    page = step.nextPage
                }

                is LinkingTableReader.Step.Complete -> {
                    // A read replaced by a newer one must not publish its table.
                    currentCoroutineContext().ensureActive()
                    tableFlow(kind, serialNumber).value = step.entries
                    progressHandler?.invoke(step.progress)
                    return NodeLinkingResult.Success(step.entries)
                }
            }
        }
    }

    /**
     * Sends the request built by [buildRequest] to the connected Node [serialNumber], building a
     * fresh one (with a new request ID) for each attempt. Returns the Node's successful response,
     * or why there is none.
     */
    private suspend fun sendToConnectedNode(
        serialNumber: String,
        messageType: NodeMessageType,
        buildRequest: (requestId: UInt) -> NodeRequest,
    ): NodeLinkingResult<NodeResponse> {
        val node = findConnectedNode(serialNumber) ?: run {
            Log.w(LOG_TAG, "Can't send $messageType: Node $serialNumber isn't connected")
            return NodeLinkingResult.NotConnected
        }

        // Both written before completeAttempt, for the same reason as
        // WiFiNodesManager.sendNodeRequestRequiringWiFi's responseData.
        var response: NodeResponse? = null
        var isRejected = false
        val result = coroutineScope {
            val command = async {
                commandCoordinator.sendRoutedCommand(
                    targetSerialNumber = serialNumber,
                    send = {
                        if (!node.isConnected) {
                            emptySet()
                        } else {
                            val requestId = makeRequestId()
                            val key = CommandAttemptKey.Node(messageType, requestId)
                            node.sendLinkingRequest(buildRequest(requestId)) { success, data ->
                                if (success) {
                                    response = data as? NodeResponse
                                    commandCoordinator.completeAttempt(key, success = true)
                                } else if (data is NodeResponse) {
                                    // The Node answered and refused, e.g. Link Device with a full
                                    // table; sending it again won't help. (A timeout passes no
                                    // response.)
                                    isRejected = true
                                    commandCoordinator.completeAttempt(key, success = false)
                                }
                            }
                            setOf(key)
                        }
                    },
                    isConfirmed = { false },
                )
            }
            // Ends the command once the Node disconnects, instead of when it times out.
            val connectionWatch = launch {
                while (node.isConnected) delay(CONNECTION_CHECK_INTERVAL_MS)
                command.cancel()
            }
            try {
                command.await()
            } catch (e: CancellationException) {
                // Cancelled by connectionWatch, unless this coroutine itself was cancelled.
                currentCoroutineContext().ensureActive()
                null
            } finally {
                connectionWatch.cancel()
            }
        }

        return when (result) {
            CommandResult.SUCCESS -> response?.let { NodeLinkingResult.Success(it) } ?: NodeLinkingResult.InvalidResponse
            CommandResult.FAILURE -> if (isRejected) NodeLinkingResult.Rejected else NodeLinkingResult.TimedOut
            null -> {
                Log.w(LOG_TAG, "$messageType to Node $serialNumber failed: it disconnected")
                NodeLinkingResult.NotConnected
            }
        }
    }

    private inline fun <T, R> NodeLinkingResult<T>.map(transform: (T) -> R): NodeLinkingResult<R> =
        when (this) {
            is NodeLinkingResult.Success -> NodeLinkingResult.Success(transform(value))
            else -> asFailure()
        }

    /** This failure, typed for another success value; only [NodeLinkingResult.Success] carries one. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> NodeLinkingResult<*>.asFailure(): NodeLinkingResult<T> {
        check(this !is NodeLinkingResult.Success) { "$this is not a failure" }
        return this as NodeLinkingResult<T>
    }

    private fun tableFlow(
        kind: LinkingTableKind,
        serialNumber: String,
    ): MutableStateFlow<List<NodeLinkingTableEntry>?> {
        val flows = when (kind) {
            LinkingTableKind.LINKED -> linkedDevices
            LinkingTableKind.UNLINKED -> unlinkedDevices
        }
        return flows.getOrPut(serialNumber.key()) { MutableStateFlow(null) }
    }

    private fun String.key(): String = uppercase()

    companion object {
        private const val HEARTBEAT_BUFFER = 64
        const val CONNECTION_CHECK_INTERVAL_MS = 500L
    }
}
