package inc.combustion.framework.ble.uart.meatnet

open class GenericNodeResponse(
        val payload: UByteArray,
        val success: Boolean,
        val requestId: UInt,
        val responseId: UInt,
        payloadLength: UByte,
        messageId: NodeMessage
) : NodeUARTMessage(
    messageId,
    payloadLength
) {

    private val nodeResponse : NodeResponse = NodeResponse(success, requestId, responseId, payloadLength, messageId)

    override fun toString(): String {
        return nodeResponse.toString()
    }

    companion object {
        fun fromData(data: UByteArray,
                     success: Boolean,
                     requestId: UInt,
                     responseId: UInt,
                     payloadLength: UByte,
                     messageId: NodeMessage,
                     ): GenericNodeResponse {
            return GenericNodeResponse(
                data,
                success,
                requestId,
                responseId,
                payloadLength,
                messageId
            )
        }
    }
}