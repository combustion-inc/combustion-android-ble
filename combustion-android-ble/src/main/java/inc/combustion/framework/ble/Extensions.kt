package inc.combustion.framework.ble

internal fun UByteArray.getLittleEndianUIntAt(index: Int) : UInt {
    return ((this[index+3].toUInt() and 0xFFu) shl 24) or
            ((this[index+2].toUInt() and 0xFFu) shl 16) or
            ((this[index+1].toUInt() and 0xFFu) shl 8) or
            (this[index].toUInt() and 0xFFu)
}

internal fun UByteArray.putLittleEndianUIntAt(index: Int, value: UInt) {
    this[index] = (value and 0x000000FFu).toUByte()
    this[index + 1] = ((value and 0x0000FF00u) shr 8).toUByte()
    this[index + 2] = ((value and 0x00FF0000u) shr 16).toUByte()
    this[index + 3] = ((value and 0xFF000000u) shr 24).toUByte()
}
