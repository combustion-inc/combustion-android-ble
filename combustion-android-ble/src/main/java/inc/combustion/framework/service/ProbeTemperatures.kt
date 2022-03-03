package inc.combustion.framework.service

import kotlin.random.Random

infix fun UShort.shl(shift: Int) = ((this.toInt() shl shift) and (0x0000FFFF)).toUShort()
infix fun UShort.shr(shift: Int) = ((this.toInt() shr shift) and (0x0000FFFF)).toUShort()

data class ProbeTemperatures(
    val values: List<Double>
) {
    companion object {
        // deserializes temperature data from reversed set of bytes
        private fun fromReversed(bytes: UByteArray): ProbeTemperatures {
            var rawCounts = mutableListOf<UShort>();

            rawCounts.add(0, ((bytes[0]  and 0xFF.toUByte()).toUShort() shl 5) or ((bytes[1]   and 0xF8.toUByte()).toUShort() shr 3))
            rawCounts.add(0, ((bytes[1]  and 0x07.toUByte()).toUShort() shl 10) or ((bytes[2]  and 0xFF.toUByte()).toUShort() shl 2) or ((bytes[3]  and 0xC0.toUByte()).toUShort() shr 6))
            rawCounts.add(0, ((bytes[3]  and 0x3F.toUByte()).toUShort() shl  7) or ((bytes[4]  and 0xFE.toUByte()).toUShort() shr 1))
            rawCounts.add(0, ((bytes[4]  and 0x01.toUByte()).toUShort() shl 12) or ((bytes[5]  and 0xFF.toUByte()).toUShort() shl 4) or ((bytes[6]  and 0xF0.toUByte()).toUShort() shr 4))
            rawCounts.add(0, ((bytes[6]  and 0x0F.toUByte()).toUShort() shl  9) or ((bytes[7]  and 0xFF.toUByte()).toUShort() shl 1) or ((bytes[8]  and 0x80.toUByte()).toUShort() shr 7))
            rawCounts.add(0, ((bytes[8]  and 0x7F.toUByte()).toUShort() shl  6) or ((bytes[9]  and 0xFC.toUByte()).toUShort() shr 2))
            rawCounts.add(0, ((bytes[9]  and 0x03.toUByte()).toUShort() shl 11) or ((bytes[10] and 0xFF.toUByte()).toUShort() shl 3) or ((bytes[11] and 0xE0.toUByte()).toUShort() shr 5))
            rawCounts.add(0, ((bytes[11] and 0x1F.toUByte()).toUShort() shl  8) or ((bytes[12] and 0xFF.toUByte()).toUShort() shr 0))

            val temperatures = rawCounts.map{ (it.toDouble() * 0.05) - 20.0 }
            return ProbeTemperatures(temperatures)
        }

        // deserializes temperature data from raw data buffer
        fun fromRawData(bytes: UByteArray): ProbeTemperatures {
            return fromReversed(bytes.reversedArray())
        }

        fun withRandomData(): ProbeTemperatures {
            val temperatures = mutableListOf<Double>()
            for (i in 0..7) {
                temperatures.add(Random.nextDouble(45.0, 60.0))
            }
            return ProbeTemperatures(temperatures)
        }
    }
}