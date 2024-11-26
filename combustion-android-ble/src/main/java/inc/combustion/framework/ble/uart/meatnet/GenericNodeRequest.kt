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
        private const val NODE_SERIAL_NUMBER_LENGTH = 10

        fun fromRaw(
            data: UByteArray,
            requestId: UInt,
            payloadLength: UByte,
            messageId: NodeMessage
            ): GenericNodeRequest? {

            if (payloadLength < NODE_SERIAL_NUMBER_LENGTH.toUByte())
                return null

            val nodeSerialNumber = String(
                data.copyOfRange(
                    HEADER_SIZE.toInt(),
                    HEADER_SIZE.toInt() + NODE_SERIAL_NUMBER_LENGTH
                ).toByteArray(), Charsets.UTF_8
            )

            // remove the header from the data since we want to just pass along the payload
            val payloadData = data.copyOfRange(HEADER_SIZE.toInt(), data.size)

            return GenericNodeRequest(
                payloadData,
                nodeSerialNumber,
                requestId,
                payloadLength,
                messageId
            )
        }
    }
}
