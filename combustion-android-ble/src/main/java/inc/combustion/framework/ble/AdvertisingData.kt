package inc.combustion.framework.ble

data class AdvertisingData (
    val data: UByteArray,
    val isConnectable: Boolean,
) {
    companion object {
        internal const val VENDOR_ID = 0x09C7
        internal val PRODUCT_TYPE_RANGE = 0..0
        internal val SERIAL_RANGE = 1..4
        internal val TEMPERATURE_RANGE = 5..17
    }

    enum class CombustionProductType(val type: UByte) {
        UNKNOWN(0x00u),
        PROBE(0x01u),
        NODE(0x02u);

        companion object {
            fun fromUByte(byte: UByte) : CombustionProductType {
                return when(byte.toUInt()) {
                    0x01u -> PROBE
                    0x02u -> NODE
                    else -> UNKNOWN
                }
            }
        }
    }

    internal val manufacturerData = data.copyOf()

    val type: CombustionProductType
    val serialNumber: String

    init {
        var serial: UInt = 0u
        // Reverse the byte order (this is a little-endian packed bitfield)
        for(byte in manufacturerData.sliceArray(SERIAL_RANGE).reversed()) {
            serial = serial shl 8
            serial = serial or byte.toUInt()
        }

        serialNumber = Integer.toHexString(serial.toInt()).uppercase()
        type = CombustionProductType.fromUByte(
            manufacturerData.sliceArray(PRODUCT_TYPE_RANGE)[0]
        )
    }
}