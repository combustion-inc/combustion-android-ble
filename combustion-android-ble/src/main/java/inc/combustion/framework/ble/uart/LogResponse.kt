package inc.combustion.framework.ble.uart

import inc.combustion.framework.ble.getLittleEndianUIntAt
import inc.combustion.framework.service.ProbeTemperatures

/**
 * Response message to Log Request
 *
 * @constructor
 * Constructs the response from the byte array received over BLE from the UART service.
 *
 * @param data Data received over BLUE
 * @param success Base response status code.
 */
class LogResponse(
    data: UByteArray,
    success: Boolean
) : Response(success) {

    /**
     * Temperature log sequence number
     */
    val sequenceNumber: UInt = data.getLittleEndianUIntAt(Response.HEADER_SIZE.toInt())

    /**
     * Logged temperature measurements
     */
    val temperatures: ProbeTemperatures = ProbeTemperatures.fromRawData(
        data.sliceArray((HEADER_SIZE + 4u).toInt()..(HEADER_SIZE + PAYLOAD_LENGTH - 1u).toInt())
    )

    companion object {
        /**
         * Expected minimum payload length for this response
         */
        const val PAYLOAD_LENGTH = 17u
    }

}