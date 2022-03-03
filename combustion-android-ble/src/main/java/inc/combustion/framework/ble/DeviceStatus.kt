package inc.combustion.framework.ble

import inc.combustion.framework.service.ProbeTemperatures

data class DeviceStatus(
    val minSequenceNumber: UInt,
    val maxSequenceNumber: UInt,
    val temperatures: ProbeTemperatures
) {
    companion object {
        private const val MIN_SEQ_INDEX = 0
        private const val MAX_SEQ_INDEX = 4
        private val TEMPERATURE_RANGE = 8..20

        fun fromRawData(data: UByteArray): DeviceStatus? {
            if (data.size < 21) return null

            val minSequenceNumber = data.getLittleEndianUIntAt(MIN_SEQ_INDEX)
            val maxSequenceNumber = data.getLittleEndianUIntAt(MAX_SEQ_INDEX)
            val temperatures = ProbeTemperatures.fromRawData(data.sliceArray(TEMPERATURE_RANGE))

            return DeviceStatus(minSequenceNumber, maxSequenceNumber, temperatures)
        }
    }
}
