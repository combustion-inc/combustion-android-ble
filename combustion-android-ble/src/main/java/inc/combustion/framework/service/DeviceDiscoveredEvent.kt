package inc.combustion.framework.service

sealed class DeviceDiscoveredEvent {
    /**
     * Bluetooth is off, no devices will be discovered
     */
    object BluetoothOff: DeviceDiscoveredEvent()

    /**
     * Bluetooth is on, devices will now be discovered if scanning
     */
    object BluetoothOn: DeviceDiscoveredEvent()

    /**
     * Scanning for Combustion devices
     */
    object ScanningOn: DeviceDiscoveredEvent()

    /**
     * Not scanning for Combustion devices
     */
    object ScanningOff: DeviceDiscoveredEvent()

    /**
     * Combustion device discovered
     *
     * @property serialNumber serial number of the discovered device
     */
    data class DeviceDiscovered(
        val serialNumber: String
    ) : DeviceDiscoveredEvent()

    /**
     * The device cache was cleared.
     */
    object DevicesCleared: DeviceDiscoveredEvent()
}
