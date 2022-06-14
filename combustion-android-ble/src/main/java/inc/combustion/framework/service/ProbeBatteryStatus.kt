package inc.combustion.framework.service

enum class ProbeBatteryStatus(val type: UByte) {
    OK(0x00u),
    LOW_BATTERY(0x01u);

    companion object {
        private const val PROBE_BATTERY_STATUS_MASK = 0x01

        fun fromUByte(byte: UByte) : ProbeBatteryStatus {
            return when((byte.toUShort() and PROBE_BATTERY_STATUS_MASK.toUShort()).toUInt()) {
                0x00u -> OK
                0x01u -> LOW_BATTERY
                else -> OK
            }
        }
    }
}
