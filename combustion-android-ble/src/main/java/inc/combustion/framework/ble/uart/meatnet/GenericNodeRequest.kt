package inc.combustion.framework.ble.uart.meatnet

open class GenericNodeRequest(
    val outgoingPayload: UByteArray,
    var nodeSerialNumber: String,
    val requestId: UInt?,
    payloadLength: UByte,
    messageId: NodeMessage,
) : NodeUARTMessage(
    messageId,
    outgoingPayload.size.toUByte()
) {
    /**
     * Constructor for generating incoming requests without a serial number
     */
    constructor(
        outgoingPayload: UByteArray,
        requestId: UInt,
        payloadLength: UByte,
        messageId: NodeMessage
    ) : this(
        outgoingPayload,
        "",
        requestId,
        payloadLength,
        messageId
    )

    /**
     * Constructor for generating outgoing requests
     */
    constructor(
        outgoingPayload: UByteArray,
        messageId: NodeMessage
    ) : this(
        outgoingPayload,
        "",
        null,
        outgoingPayload.size.toUByte(),
        messageId
    )

    override fun toString(): String {
        return "${nodeRequest} SerialNumber: $nodeSerialNumber"
    }

    private val nodeRequest :  NodeRequest = NodeRequest(outgoingPayload, messageId)

    internal fun toNodeRequest() : NodeRequest {
        return nodeRequest
    }

    val sData get() = nodeRequest.sData

    companion object {
        const val HEADER_SIZE = NodeRequest.HEADER_SIZE

        fun fromRaw(
            data: UByteArray,
            requestId: UInt,
            payloadLength: UByte,
            messageId: NodeMessage
            ): GenericNodeRequest {
            return GenericNodeRequest(
                data,
                requestId,
                payloadLength,
                messageId
            )
        }
    }
}
