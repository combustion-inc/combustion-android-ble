package inc.combustion.framework.ble.uart.meatnet

import inc.combustion.framework.ble.putLittleEndianUInt32At

internal class NodeReadFeatureFlagsRequest(
    nodeSerialNumber: String,
    requestId: UInt? = null
) : NodeRequest(populatePayload(nodeSerialNumber), NodeMessageType.GET_FEATURE_FLAGS, requestId, nodeSerialNumber)
{
    companion object {

        const val PAYLOAD_LENGTH: UByte = 10u

        fun populatePayload(nodeSerialNumber: String): UByteArray {
            val payload = UByteArray(PAYLOAD_LENGTH.toInt()){ 0u }
            nodeSerialNumber.toByteArray().copyInto(payload, 0, 0, PAYLOAD_LENGTH.toInt())
            return payload
        }
    }
}

private fun ByteArray.copyInto(destination: UByteArray, destinationOffset: Int, startIndex: Int, endIndex: Int) {
    for (i in startIndex until endIndex) {
        destination[destinationOffset + i] = this[i].toUByte()
    }
}
