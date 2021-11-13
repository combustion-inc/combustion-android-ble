package inc.combustion.framework.ble.uart

import android.util.Log
import inc.combustion.framework.LOG_TAG
import java.util.concurrent.atomic.AtomicInteger

/**
 * Base class for UART response message
 *
 * @property success successfully processed request when true. otherwise, error handling request.
 */
open class Response(
    val success: Boolean
) {
    class Statistics {
        val droppedPackets = AtomicInteger(0)
        val invalidCrc = AtomicInteger(0)
        val invalidMessageType = AtomicInteger(0)
        val invalidPayloadLength = AtomicInteger(0)
    }

    companion object {

        /**
         * Length of message header
         */
        const val HEADER_SIZE: UByte = 7u

        /**
         * Tracks data error stats
         */
        var stats = Statistics()
            private set

        /**
         * Factory method for constructing response from byte array.
         *
         * @param data data received over BLE from UART service.
         * @return Instance of the response or null on data error.
         */
        fun fromData(data: UByteArray) : Response? {

            // Validate Sync Bytes { 0xCA, 0xFE }
            if((data[0].toUInt() != 0xCAu) or (data[1].toUInt() != 0xFEu)) {
                Log.w(
                    "$LOG_TAG.Response",
                    "Dropped Packet.  Invalid Header Sync Bytes (total: ${stats.droppedPackets.incrementAndGet()})"
                )
                return null
            }

            // Message type
            val messageType = MessageType.fromUByte(data[4]) ?:
                Log.w(
                    "$LOG_TAG.Response",
                    "Dropped Packet.  Invalid Message Type (total: ${stats.invalidMessageType.incrementAndGet()})"
                ).also {
                    return@fromData null
                }

            // Success/Fail
            val success = data[5] > 0u

            // Payload Length
            val length = data[6]

            // validate payload length and construct response
            when(messageType) {
                MessageType.LOG -> {
                    if (length >= LogResponse.PAYLOAD_LENGTH)
                        return LogResponse(data, success)
                    else
                        Log.w(
                            "$LOG_TAG.Response",
                            "Dropped Packet.  Invalid Payload Length ($length) (total: ${stats.invalidPayloadLength.incrementAndGet()})"
                        )
                }
            }

            return null
        }
    }
}