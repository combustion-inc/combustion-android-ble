/*
 * Project: Combustion Inc. Android Framework
 * File: NodeLinkingMessages.kt
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

package inc.combustion.framework.ble.uart.meatnet

/**
 * Read Linked Devices (0x73) or Read Unlinked Devices (0x74): requests one page (0-indexed) of
 * the Node's table.
 */
internal class NodeReadLinkingTableRequest(
    nodeSerialNumber: String,
    messageType: NodeMessageType,
    val page: Int,
    requestId: UInt? = null,
) : NodeRequest(
    ubyteArrayOf(page.toUByte()),
    messageType,
    requestId,
    nodeSerialNumber,
) {
    init {
        require(messageType.isLinkingTableRead) { "$messageType is not a linking table read" }
    }

    override fun toString(): String = "${super.toString()} page $page"
}

/**
 * Link Device (0x75) or Unlink Device (0x76), carrying a payload built by
 * [NodeLinkingWire.encodeDevicePayload].
 */
internal class NodeLinkDeviceRequest(
    nodeSerialNumber: String,
    messageType: NodeMessageType,
    devicePayload: UByteArray,
    requestId: UInt? = null,
) : NodeRequest(
    devicePayload,
    messageType,
    requestId,
    nodeSerialNumber,
) {
    init {
        require(messageType == NodeMessageType.LINK_DEVICE || messageType == NodeMessageType.UNLINK_DEVICE) {
            "$messageType is not Link Device or Unlink Device"
        }
        require(devicePayload.size == NodeLinkingWire.DEVICE_PAYLOAD_LENGTH)
    }
}

/**
 * Set Proximity Linking (0x79): enables or disables proximity-based auto-linking on the Node.
 */
internal class NodeSetProximityLinkingRequest(
    nodeSerialNumber: String,
    val enabled: Boolean,
    requestId: UInt? = null,
) : NodeRequest(
    ubyteArrayOf(if (enabled) 1u else 0u),
    NodeMessageType.SET_PROXIMITY_LINKING,
    requestId,
    nodeSerialNumber,
) {
    override fun toString(): String = "${super.toString()} $enabled"
}

/**
 * Read Proximity Linking (0x7A): has no payload.
 */
internal class NodeReadProximityLinkingRequest(
    nodeSerialNumber: String,
    requestId: UInt? = null,
) : NodeRequest(
    UByteArray(0),
    NodeMessageType.READ_PROXIMITY_LINKING,
    requestId,
    nodeSerialNumber,
)

/**
 * A linking request received from MeatNet. The phone never acts on these: Device Linked (0x77),
 * Device Unlinked (0x78) and Sync Link Table (0x7B) are Node-to-Node messages, and the others are
 * requests one Node sends another. They're parsed only so they aren't treated as unknown data,
 * which would drop any messages after them in the same notification (see
 * [NodeUARTMessage.fromData]).
 *
 * @property device the device a Device Linked or Device Unlinked notification is about, or null
 * for other types or a malformed payload.
 */
internal class NodeIncomingLinkingRequest(
    messageType: NodeMessageType,
    requestId: UInt,
    payloadLength: UByte,
    val device: NodeLinkingWire.DevicePayload?,
) : NodeRequest(requestId, payloadLength, messageType, serialNumber = "") {
    companion object {
        fun fromRaw(
            data: UByteArray,
            messageType: NodeMessageType,
            requestId: UInt,
            payloadLength: UByte,
        ): NodeIncomingLinkingRequest {
            val device = when (messageType) {
                NodeMessageType.DEVICE_LINKED, NodeMessageType.DEVICE_UNLINKED ->
                    NodeLinkingWire.parseDevicePayload(
                        data,
                        HEADER_SIZE.toInt(),
                        payloadLength.toInt(),
                    )

                else -> null
            }
            return NodeIncomingLinkingRequest(messageType, requestId, payloadLength, device)
        }
    }

    override fun toString(): String = "${super.toString()} $device"
}

/**
 * Base class for responses to the linking requests the phone sends, so [NodeBleDevice] can match
 * them all with one handler by request ID.
 */
internal abstract class NodeLinkingResponse(
    success: Boolean,
    requestId: UInt,
    responseId: UInt,
    payloadLength: UByte,
    messageType: NodeMessageType,
) : NodeResponse(success, requestId, responseId, payloadLength, messageType)

/**
 * Response to Read Linked Devices (0x73) or Read Unlinked Devices (0x74).
 *
 * @property page the parsed page, or null if the response failed or its payload is malformed (see
 * [NodeLinkingWire.parsePage]). Malformed pages are still returned as a response, rather than not
 * parsed at all, so the read fails straight away instead of waiting out its timeout.
 */
internal class NodeLinkingTablePageResponse(
    success: Boolean,
    requestId: UInt,
    responseId: UInt,
    payloadLength: UByte,
    messageType: NodeMessageType,
    val page: NodeLinkingWire.Page?,
) : NodeLinkingResponse(success, requestId, responseId, payloadLength, messageType) {
    override fun toString(): String = "${super.toString()} $success $page"

    companion object {
        fun fromData(
            data: UByteArray,
            messageType: NodeMessageType,
            success: Boolean,
            requestId: UInt,
            responseId: UInt,
            payloadLength: UByte,
        ): NodeLinkingTablePageResponse {
            val page = if (success) {
                NodeLinkingWire.parsePage(
                    data,
                    HEADER_SIZE.toInt(),
                    payloadLength.toInt(),
                    permitsLegacyEntries = messageType == NodeMessageType.READ_LINKED_DEVICES,
                )
            } else {
                null
            }
            return NodeLinkingTablePageResponse(
                success,
                requestId,
                responseId,
                payloadLength,
                messageType,
                page,
            )
        }
    }
}

/**
 * Response without a payload: Link Device (0x75), Unlink Device (0x76), Set Proximity Linking
 * (0x79) and Sync Link Table (0x7B).
 */
internal class NodeLinkingAckResponse(
    success: Boolean,
    requestId: UInt,
    responseId: UInt,
    payloadLength: UByte,
    messageType: NodeMessageType,
) : NodeLinkingResponse(success, requestId, responseId, payloadLength, messageType) {
    override fun toString(): String = "${super.toString()} $success"
}

/**
 * Response to Read Proximity Linking (0x7A).
 *
 * @property enabled whether proximity linking is enabled, or null if the response failed or has no
 * payload.
 */
internal class NodeReadProximityLinkingResponse(
    success: Boolean,
    requestId: UInt,
    responseId: UInt,
    payloadLength: UByte,
    val enabled: Boolean?,
) : NodeLinkingResponse(
    success,
    requestId,
    responseId,
    payloadLength,
    NodeMessageType.READ_PROXIMITY_LINKING,
) {
    override fun toString(): String = "${super.toString()} $success $enabled"

    companion object {
        fun fromData(
            data: UByteArray,
            success: Boolean,
            requestId: UInt,
            responseId: UInt,
            payloadLength: UByte,
        ): NodeReadProximityLinkingResponse {
            val enabled = if (success && payloadLength >= 1u) {
                data[HEADER_SIZE.toInt()] != 0.toUByte()
            } else {
                null
            }
            return NodeReadProximityLinkingResponse(
                success,
                requestId,
                responseId,
                payloadLength,
                enabled,
            )
        }
    }
}
