package inc.combustion.framework.ble

import android.bluetooth.BluetoothAdapter
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.testing.TestLifecycleOwner
import inc.combustion.framework.MainDispatcherRule
import inc.combustion.framework.ble.scanning.CombustionAdvertisingData
import inc.combustion.framework.ble.scanning.DeviceScanner
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.DeviceManager
import inc.combustion.framework.service.ProbeBatteryStatus
import inc.combustion.framework.service.ProbeColor
import inc.combustion.framework.service.ProbeID
import inc.combustion.framework.service.ProbeMode
import inc.combustion.framework.service.ProbeTemperatures
import inc.combustion.framework.service.ProbeVirtualSensors
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.BeforeTest
import kotlin.test.assertNotNull

class NetworkManagerTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val advertisingData = CombustionAdvertisingData(
        mac = "00:11:22:33:44:55",
        name = "name",
        rssi = -44,
        productType = CombustionProductType.PROBE,
        isConnectable = false,
        probeSerialNumber = "serialnumber",
        probeTemperatures = ProbeTemperatures(
            values = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        ),
        probeID = ProbeID.ID1,
        color = ProbeColor.COLOR1,
        mode = ProbeMode.NORMAL,
        batteryStatus = ProbeBatteryStatus.OK,
        virtualSensors = ProbeVirtualSensors.DEFAULT,
        hopCount = 0u
    )

    private val adapter: BluetoothAdapter = mockk(relaxed = true)
    private val settings = DeviceManager.Settings()

    private val advertisements = MutableSharedFlow<CombustionAdvertisingData>()

    @BeforeTest
    fun setUp() {
        every { adapter.isEnabled } returns true

        // val advertisingArbitrator = mockkClass(AdvertisingArbitrator::class)
        // every { advertisingArbitrator.shouldUpdate(any(), any()) } returns true

        // val dataLinkArbitrator = mockkClass(DataLinkArbitrator::class)
        // every { dataLinkArbitrator.shouldUpdateDataFromAdvertisingPacket(any(), any()) } answers { true }

        mockkConstructor(DataLinkArbitrator::class)
        every { anyConstructed<DataLinkArbitrator>().shouldUpdateDataFromAdvertisingPacket(any(), any()) } answers { true }

        mockkObject(DeviceScanner)
        every { DeviceScanner.advertisements } returns advertisements
        every { DeviceScanner.scan(any()) } answers { }

        NetworkManager.initialize(TestLifecycleOwner(), adapter, settings)
    }

    @Test
    fun `Advertising Thermometer is Added to Devices in Proximity`() = runTest {
        // advertisements.emit(advertisingData)
        // NetworkManager.instance.setProbeID("abc", ProbeID.ID1) { }
        NetworkManager.instance.startScanForProbes()
        advertisements.emit(advertisingData)

        assertNotNull(
            NetworkManager.instance.deviceSmoothedRssiFlow(advertisingData.probeSerialNumber)
        )
    }
}