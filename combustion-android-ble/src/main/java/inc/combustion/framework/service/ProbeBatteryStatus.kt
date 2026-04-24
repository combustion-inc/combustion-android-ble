package inc.combustion.framework.service

enum class ProbeBatteryStatus(val type: UByte) {
    OK(0x00u),
    LOW_BATTERY(0x01u);

    val isLowBattery: Boolean
        get() = this == LOW_BATTERY

    companion object {
        private const val PROBE_BATTERY_STATUS_MASK = 0x01

        fun fromUByte(byte: UByte): ProbeBatteryStatus {
            return when ((byte.toUShort() and PROBE_BATTERY_STATUS_MASK.toUShort()).toUByte()) {
                OK.type -> OK
                LOW_BATTERY.type -> LOW_BATTERY
                else -> OK
            }
        }
    }
}
