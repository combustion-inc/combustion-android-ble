package inc.combustion.framework.ble.uart

/**
 * Enumerates message types in Combustion's UART protocol.
 *
 * @property value byte value for message type.
 */
enum class MessageType(val value: UByte) {
    LOG(0x04u);

    companion object {
        fun fromUByte(value: UByte) = MessageType.values().firstOrNull { it.value == value }
    }
}