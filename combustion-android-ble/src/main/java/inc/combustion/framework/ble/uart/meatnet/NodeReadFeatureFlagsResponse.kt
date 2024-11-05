package inc.combustion.framework.ble.uart.meatnet

internal class NodeReadFeatureFlagsResponse(
    val serialNumber: String,
    val wifi: Boolean,
    success: Boolean,
    requestId: UInt,
    responseId: UInt,
    payloadLength: UByte
) : NodeResponse(
    success,
    requestId,
    responseId,
    payloadLength,
    NodeMessageType.GET_FEATURE_FLAGS
) {
  companion object {
    const val PAYLOAD_LENGTH: UByte = 14u
    const val NODE_SERIAL_NUMBER_LENGTH: UByte = 10u

    fun fromData(
        payload: UByteArray,
        success: Boolean,
        requestId: UInt,
        responseId: UInt,
        payloadLength: UByte
    ) : NodeReadFeatureFlagsResponse? {

        if (payloadLength < PAYLOAD_LENGTH) {
            return null
        }

        //val serialNumber = payload.getLittleEndianUInt32At(HEADER_SIZE.toInt()).toString(radix = 16).uppercase()
        // TODO: i'm not sure if this is right
        val serialNumber = payload.sliceArray(HEADER_SIZE.toInt() until HEADER_SIZE.toInt() + NODE_SERIAL_NUMBER_LENGTH.toInt()).joinToString("") { it.toString(16).padStart(2, '0') }
        // TOOD: i'm not sure if this is right
        val wifi = payload[(HEADER_SIZE + NODE_SERIAL_NUMBER_LENGTH).toInt()] == 1u.toUByte()

        return NodeReadFeatureFlagsResponse(
            serialNumber,
            wifi,
            success,
            requestId,
            responseId,
            payloadLength
        )
    }

  }
}