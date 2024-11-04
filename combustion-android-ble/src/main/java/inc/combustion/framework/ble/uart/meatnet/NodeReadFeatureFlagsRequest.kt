package inc.combustion.framework.ble.uart.meatnet

import inc.combustion.framework.ble.putLittleEndianUInt32At

internal class NodeReadFeatureFlagsRequest(
    serialNumber: String,
    requestId: UInt? = null
) : NodeRequest(populatePayload(serialNumber), NodeMessageType.GET_FEATURE_FLAGS, requestId)
{
    companion object {

        const val PAYLOAD_LENGTH: UByte = 10u

        fun populatePayload(serialNumber: String): UByteArray {
            val payload = UByteArray(PAYLOAD_LENGTH.toInt()){ 0u }

            payload.putLittleEndianUInt32At(0, serialNumber.toLong(radix = 16).toUInt())
            return payload
        }
    }
}