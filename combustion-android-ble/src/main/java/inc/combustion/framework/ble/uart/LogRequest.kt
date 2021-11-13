package inc.combustion.framework.ble.uart

import inc.combustion.framework.ble.putLittleEndianUIntAt

/**
 * Request temperature logs message
 *
 * @constructor
 * Constructs the request message
 *
 * @param minSequence minimum sequence number
 * @param maxSequence maximum sequence number
 */
class LogRequest(
    minSequence: UInt,
    maxSequence: UInt
) : Request(PAYLOAD_LENGTH, MessageType.LOG) {
    companion object {
        /**
         * Length of message payload
         */
        const val PAYLOAD_LENGTH: UByte = 8u

        /**
         * Maximum size of message range
         */
        const val MAX_LOG_MESSAGES: UInt = 500u
    }

    init {
        data.putLittleEndianUIntAt((HEADER_SIZE + 0u).toInt(), minSequence)
        data.putLittleEndianUIntAt((HEADER_SIZE + 4u).toInt(), maxSequence)
    }
}