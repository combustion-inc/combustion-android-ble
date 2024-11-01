package inc.combustion.framework.ble.uart.meatnet

open class EncryptedNodeResponse(
        val success: Boolean,
        val requestId: UInt,
        val responseId: UInt,
        val payloadLength: UByte,
        val messageId: NodeMessage) {

    private val nodeResponse : NodeResponse = NodeResponse(success, requestId, responseId, payloadLength, messageId)

    override fun toString(): String {
        return "RESP $messageId (REQID: ${String.format("%08x",requestId.toInt())} RSPID: ${String.format("%08x",responseId.toInt())})"
    }

    private fun decryptResponse(encryptedPayload: UByteArray) : UByteArray{
        // TODO: implement me
        return encryptedPayload
    }

    internal fun responseFromData(data : UByteArray) : NodeResponse? {
        return NodeResponse.responseFromData(decryptResponse(data))
    }
}