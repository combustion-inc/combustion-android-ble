package inc.combustion.framework.ble.uart

internal class SetIDResponse (
    success: Boolean,
    payLoadLength: UInt
) : Response(success, payLoadLength) {

    companion object {
        const val PAYLOAD_LENGTH: UInt = 0u

        fun fromData(success: Boolean): SetIDResponse {
            return SetIDResponse(success, PAYLOAD_LENGTH)
        }
    }
}