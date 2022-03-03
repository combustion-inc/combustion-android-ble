package inc.combustion.framework.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.CountDownTimer
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.lifecycleScope
import inc.combustion.framework.service.ProbeTemperatures
import kotlinx.coroutines.launch
import kotlin.concurrent.fixedRateTimer
import kotlin.random.Random
import kotlin.random.nextUInt

class SimulatedProbeManager (
    mac: String,
    private val owner: LifecycleOwner,
    private val advertisingData: ProbeAdvertisingData,
    adapter: BluetoothAdapter
): ProbeManager(mac, owner, advertisingData, adapter) {

    companion object {
        const val SIMULATED_MAC = "00:00:00:00:00:00"

        fun create(owner: LifecycleOwner, adapter: BluetoothAdapter) : SimulatedProbeManager {
            val fakeSerialNumber = "%08X".format(Random.nextInt())
            val fakeMacAddress = "%02X:%02X:%02X:%02X:%02X:%02X".format(
                Random.nextBytes(1).first(),
                Random.nextBytes(1).first(),
                Random.nextBytes(1).first(),
                Random.nextBytes(1).first(),
                Random.nextBytes(1).first(),
                Random.nextBytes(1).first())

            val data = ProbeAdvertisingData(
                "CP",
                fakeMacAddress,
                randomRSSI(),
                fakeSerialNumber,
                ProbeAdvertisingData.CombustionProductType.PROBE,
                true,
                ProbeTemperatures.withRandomData()
            )

            return SimulatedProbeManager(SIMULATED_MAC, owner, data, adapter)
        }

        fun randomRSSI() : Int {
            return Random.nextInt(-80, -40)
        }

    }

    init {
        fixedRateTimer(name = "FakeAdvertising",
            initialDelay = 1000, period = 1000) {
            owner.lifecycleScope.launch {
                simulateAdvertising()
            }
        }

        fixedRateTimer(name = "FakeStatusNotifications",
            initialDelay = 1000, period = 1000) {
            owner.lifecycleScope.launch {
                simulateAdvertising()
            }
        }
    }

    override fun connect() {
        _isConnected.set(true)
        _fwVersion = "v1.2.3"
    }

    override fun disconnect() {
        _isConnected.set(false)
    }

    private suspend fun simulateAdvertising() {
        val data = ProbeAdvertisingData("CP",
            advertisingData.mac,
            randomRSSI(),
            advertisingData.serialNumber,
            ProbeAdvertisingData.CombustionProductType.PROBE,
            true,
            ProbeTemperatures.withRandomData()
        )

        super.onNewAdvertisement(data)
    }

    private suspend fun simulateStatusNotifications() {
        val status = DeviceStatus()

        _deviceStatusFlow.emit(DeviceStatus(data.toUByteArray()))
    }
}