package inc.combustion.framework.ble

import inc.combustion.framework.service.ProbeTemperatures

data class DeviceStatus(
    private var data: UByteArray = UByteArray(21) { 0u }
) {
    companion object {
        internal const val MIN_SEQ_INDEX = 0
        internal const val MAX_SEQ_INDEX = 4
        internal val TEMPERATURE_RANGE = 8..20
    }

    init {
        require(data.size >= 21) {"Invalid input buffer.  Size ${data.size}"}
    }

    val minSequenceNumber: UInt
        get() = data.getLittleEndianUIntAt(MIN_SEQ_INDEX)

    val maxSequenceNumber: UInt
        get() = data.getLittleEndianUIntAt(MAX_SEQ_INDEX)

    val temperatures: ProbeTemperatures
        get() = ProbeTemperatures.fromRawData(data.sliceArray(TEMPERATURE_RANGE))
}
