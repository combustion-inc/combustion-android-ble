package inc.combustion.framework.service

data class Probe(
    val serialNumber: String,
    val mac: String,
    val fwVersion: String?,
    val temperatures: ProbeTemperatures,
    val rssi: Int,
    val minSequence: UInt,
    val maxSequence: UInt,
    val connectionState: DeviceConnectionState,
    val uploadState: ProbeUploadState
)