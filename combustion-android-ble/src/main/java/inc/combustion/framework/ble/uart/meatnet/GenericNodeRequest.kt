package inc.combustion.framework.ble.uart.meatnet

// wrapper around a NodeRequest to encrypt the data
open class GenericNodeRequest (outgoingPayload: UByteArray, messageId: NodeMessage)
{
    override fun toString(): String {
        return nodeRequest.toString()
    }

    private val nodeRequest :  NodeRequest = NodeRequest(outgoingPayload, messageId)

    internal fun toNodeRequest() : NodeRequest {
        return nodeRequest
    }
    val sData get() = nodeRequest.sData
}