package inc.combustion.framework.ble.uart.meatnet

internal class NodeReadFeatureFlagsResponse(
    val nodeSerialNumber: String,
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
        private const val NODE_SERIAL_NUMBER_LENGTH: UByte = 10u
        private val WIFI_FLAG_MASK: UByte = 0x01u

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

            val nodeSerialNumber = payload.sliceArray(HEADER_SIZE.toInt() until HEADER_SIZE.toInt() + NODE_SERIAL_NUMBER_LENGTH.toInt()).joinToString("") { it.toString(16).padStart(2, '0') }
            val wifi = payload[(HEADER_SIZE + NODE_SERIAL_NUMBER_LENGTH).toInt()] and WIFI_FLAG_MASK == 0x01.toUByte()

            return NodeReadFeatureFlagsResponse(
                nodeSerialNumber,
                wifi,
                success,
                requestId,
                responseId,
                payloadLength
            )
        }
  }
}