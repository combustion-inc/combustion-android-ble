package inc.combustion.framework.ble.uart.meatnet

// wrapper around a NodeRequest to encrypt the data
open class GenericNodeRequest(
    outgoingPayload: UByteArray,
    messageId: NodeMessage)
    : NodeUARTMessage(messageId, outgoingPayload.size.toUByte())
{
    override fun toString(): String {
        return nodeRequest.toString()
    }

    private val nodeRequest :  NodeRequest = NodeRequest(outgoingPayload, messageId)

    internal fun toNodeRequest() : NodeRequest {
        return nodeRequest
    }
    val sData get() = nodeRequest.sData

    companion object {
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
