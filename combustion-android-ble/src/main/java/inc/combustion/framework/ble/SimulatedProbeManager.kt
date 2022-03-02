package inc.combustion.framework.ble

import android.bluetooth.BluetoothAdapter
import androidx.lifecycle.LifecycleOwner

class SimulatedProbeManager (
    mac: String,
    owner: LifecycleOwner,
    advertisingData: ProbeAdvertisingData,
    adapter: BluetoothAdapter
): ProbeManager(mac, owner, advertisingData, adapter) {

}