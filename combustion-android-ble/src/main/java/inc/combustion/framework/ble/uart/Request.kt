package inc.combustion.framework.ble.uart

/**
 * Baseclass for UART request messages
 *
 * @constructor
 * Create request message of the specified type and length.
 *
 * @param payloadLength length of payload
 * @param type type of request message
 */
open class Request(
    payloadLength: UByte,
    type: MessageType
) {
    companion object {
        /**
         * Length of message header
         */
        const val HEADER_SIZE: UByte = 6u
    }

    val data = UByteArray((HEADER_SIZE + payloadLength).toInt()) { 0u }
    val sData
        get() = data.toByteArray()

    init {
        // Sync Bytes { 0xCA, 0xFE }
        data[0] = 0xCAu
        data[1] = 0xFEu

        // Message type
        data[4] = type.value

        // Payload length
        data[5] = payloadLength
    }
}