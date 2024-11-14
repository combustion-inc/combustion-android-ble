package inc.combustion.framework.ble.uart.meatnet

// wrapper around a NodeRequest to encrypt the data
open class GenericNodeRequest(
    val outgoingPayload: UByteArray,
    messageId: NodeMessage,
) : NodeUARTMessage(
    messageId,
    outgoingPayload.size.toUByte()
) {
    override fun toString(): String {
        return "${nodeRequest} SerialNumber: $serialNumber"
    }

    private val nodeRequest :  NodeRequest = NodeRequest(outgoingPayload, messageId)

    internal fun toNodeRequest() : NodeRequest {
        return nodeRequest
    }

    var serialNumber: String = ""

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
                messageId
            )
        }
    }
}
