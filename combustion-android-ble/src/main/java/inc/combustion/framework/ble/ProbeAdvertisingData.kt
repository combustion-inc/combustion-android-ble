package inc.combustion.framework.ble

import android.bluetooth.le.ScanResult
import com.juul.kable.Advertisement
import inc.combustion.framework.service.ProbeTemperatures
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

data class ProbeAdvertisingData (
    val name: String,
    val mac: String,
    val rssi: Int,
    val serialNumber: String,
    val type: CombustionProductType,
    val isConnectable: Boolean,
    val probeTemperatures: ProbeTemperatures
) {
    companion object {
        private const val VENDOR_ID = 0x09C7
        private val PRODUCT_TYPE_RANGE = 0..0
        private val SERIAL_RANGE = 1..4
        private val TEMPERATURE_RANGE = 5..17

        fun fromAdvertisement(advertisement: Advertisement): ProbeAdvertisingData? {
            // Check vendor ID of Manufacturing data
            val manufacturerData = advertisement.manufacturerData(VENDOR_ID)?.toUByteArray() ?: return null

            val scanResult = advertisement.getPrivateProperty<Advertisement, ScanResult>("scanResult")

            var serial: UInt = 0u
            // Reverse the byte order (this is a little-endian packed bitfield)
            val rawSerialNumber = manufacturerData.copyOf().sliceArray(SERIAL_RANGE)
            for(byte in rawSerialNumber.reversed()) {
                serial = serial shl 8
                serial = serial or byte.toUInt()
            }

            val serialNumber = Integer.toHexString(serial.toInt()).uppercase()
            val type = CombustionProductType.fromUByte(
                manufacturerData.copyOf().sliceArray(PRODUCT_TYPE_RANGE)[0]
            )

            val probeTemperatures = ProbeTemperatures.fromRawData(manufacturerData.copyOf().sliceArray(TEMPERATURE_RANGE))

            return ProbeAdvertisingData(
                name = advertisement.name ?: "Unknown",
                mac = advertisement.address,
                rssi = advertisement.rssi,
                serialNumber,
                type,
                isConnectable = scanResult?.isConnectable ?: false,
                probeTemperatures
            )
        }
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
}

/**
 * This extension get's access to the ScanResult to return the isConnectable
 * state of the advertisement.  See related feature request:
 * https://github.com/JuulLabs/kable/issues/210
 *
 * @return value of Advertisement.ScanResult.isConnectable
 * @see https://stackoverflow.com/questions/48158909/java-android-kotlin-reflection-on-private-field-and-call-public-methods-on-it
 */
@Suppress("UNCHECKED_CAST")
private inline fun <reified T : Any, R> T.getPrivateProperty(name: String): R? =
    T::class
        .memberProperties
        .firstOrNull { it.name == name }
        ?.apply { isAccessible = true }
        ?.get(this) as? R