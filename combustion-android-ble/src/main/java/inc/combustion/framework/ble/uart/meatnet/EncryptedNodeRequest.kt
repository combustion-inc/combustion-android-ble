package inc.combustion.framework.ble.uart.meatnet

// wrapper around a NodeRequest to encrypt the data
open class EncryptedNodeRequest (outgoingPayload: UByteArray, messageId: NodeMessage)
{
    override fun toString(): String {
        return nodeRequest.toString()
    }

    private val nodeRequest :  NodeRequest = NodeRequest(encryptPayload(outgoingPayload), messageId)

    val sData get() = nodeRequest.sData

    private fun encryptPayload(unencryptedPayload: UByteArray) : UByteArray{
        // TODO: implement me
        return unencryptedPayload
    }
}