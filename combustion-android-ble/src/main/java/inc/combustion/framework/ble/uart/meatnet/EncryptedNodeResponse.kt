package inc.combustion.framework.ble.uart.meatnet

import android.util.Log
import inc.combustion.framework.ble.getCRC16CCITT
import inc.combustion.framework.ble.getLittleEndianUInt32At
import inc.combustion.framework.ble.getLittleEndianUShortAt

class EncryptedNodeResponse(
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
        return "RESP $messageId (REQID: ${String.format("%08x",requestId.toInt())} RSPID: ${String.format("%08x",responseId.toInt())})"
    }

    private fun decryptResponse(encryptedPayload: UByteArray) : UByteArray{
        // TODO: implement me
        return encryptedPayload
    }

    /*internal fun responseFromData(data : UByteArray) : NodeResponse? {
        return NodeResponse.responseFromData(decryptResponse(data))
    }
     */

    internal fun getUARTMessage() : NodeUARTMessage {
        return nodeResponse
    }

    companion object {
        const val HEADER_SIZE : UByte = NodeResponse.HEADER_SIZE

        // NodeResponse messages have the leftmost bit in the 'message type' field set to 1.
        private const val RESPONSE_TYPE_FLAG : UByte = 0x80u

        /*
        fun fromData(data: UByteArray): EncryptedNodeResponse? {

            // Sync bytes
            val syncBytes = data.slice(0..1)
            val expectedSync = listOf<UByte>(202u, 254u) // 0xCA, 0xFE
            if(syncBytes != expectedSync) {
                return null
            }

            // Message type
            val typeRaw = data[4]

            // Verify that this is a Response by checking the response type flag
            if(typeRaw and EncryptedNodeResponse.RESPONSE_TYPE_FLAG != EncryptedNodeResponse.RESPONSE_TYPE_FLAG) {
                // If that 'response type' bit isn't set, this is probably a Request.
                Log.d("ben", "not a response?")
                return null
            }

            val messageType = NodeMessageType.fromUByte((typeRaw and EncryptedNodeResponse.RESPONSE_TYPE_FLAG.inv()))
            if (messageType == null) {
                    Log.d("ben", "not a response, message type is null")
                    return null
                }
            // Request ID
            val requestId = data.getLittleEndianUInt32At(5)

            // Response ID
            val responseId = data.getLittleEndianUInt32At(9)

            // Success/Fail
            val success = data[13] > 0u

            // Payload Length
            val payloadLength = data[14]

            // CRC
            val crc = data.getLittleEndianUShortAt(2)

            val crcDataLength = 11 + payloadLength.toInt()

            var crcData = data.drop(4).toUByteArray()
            crcData = crcData.dropLast(crcData.size - crcDataLength).toUByteArray()

            val calculatedCRC = crcData.getCRC16CCITT()

            Log.d("ben","processing node response")
            if(crc != calculatedCRC) {
                return null
            }

            val responseLength = payloadLength.toInt() + NodeResponse.HEADER_SIZE.toInt()

            // Invalid number of bytes
            if(data.size < responseLength) {
                return null
            }

            Log.d("ben","really processing node response")
            /*
            val responses = mutableListOf<EncryptedNodeResponse>()

            var numberBytesRead = 0

            while (numberBytesRead < data.size) {
                val bytesToDecode = data.copyOfRange(numberBytesRead, data.size)
                val response = NodeResponse.responseFromData(bytesToDecode)

                if (response != null) {
                    responses.add(
                        EncryptedNodeResponse(
                            response.data,
                            response.success,
                            response.requestId,
                            response.responseId,
                            response.payloadLength,
                            response.messageId
                        )
                    )
                    numberBytesRead += (response.payloadLength + NodeResponse.HEADER_SIZE).toInt()
                } else {
                    // Found invalid response, break out of while loop
                    break
                }
            }
             */

            return EncryptedNodeResponse(
                data,
                success,
                requestId,
                responseId,
                payloadLength,
                messageType
            )
        }

         */
        fun fromData(data: UByteArray,
                     success: Boolean,
                     requestId: UInt,
                     responseId: UInt,
                     payloadLength: UByte,
                     messageId: NodeMessage,
                     ): EncryptedNodeResponse? {
            Log.d("ben", "processing encrypted node response")
            return EncryptedNodeResponse(
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