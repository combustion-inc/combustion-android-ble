package inc.combustion.framework.ble.uart

internal class SetColorResponse (
    success: Boolean,
    payLoadLength: UInt
) : Response(success, payLoadLength) {

    companion object {
        const val PAYLOAD_LENGTH: UInt = 0u

        fun fromData(success: Boolean): SetColorResponse {
            return SetColorResponse(success, PAYLOAD_LENGTH)
        }
    }
}