package inc.combustion.framework.ble

import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.util.Log
import androidx.lifecycle.*
import com.juul.kable.Advertisement
import com.juul.kable.Scanner
import inc.combustion.framework.LOG_TAG
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.*
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

// The scanSettings property is only available on Android and
// is considered a Kable obsolete API, meaning it will be removed
// when a DSL specific API becomes available.
class DeviceScanner private constructor() {

    companion object {
        private var probeAllMatchesScanJob: Job? = null
        private val isProbeScanning = AtomicBoolean(false)
        private val _probeAdvertisements = MutableSharedFlow<ProbeAdvertisingData>()

        val probeAdvertisements = _probeAdvertisements.asSharedFlow()

        private val probeAllMatchesScanner = Scanner {
            services = listOf(ProbeManager.NEEDLE_SERVICE_UUID.uuid)
            scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()
        }

        val isScanningForProbes: Boolean
            get() = isProbeScanning.get()

        fun startProbeScanning(owner: LifecycleOwner) {
            if(!isProbeScanning.getAndSet(true)) {
                probeAllMatchesScanJob = owner.lifecycleScope.launch {
                    // launches the block in a new coroutine every time the LifecycleOwner is
                    // in the CREATED state or above, and cancels the block when the LifecycleOwner
                    // is destroyed
                   owner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                       Log.d(LOG_TAG, "ProbeManager Scanning Started ...")
                       // filter and collect on incoming BLE advertisements
                       probeAllMatchesScanner
                           .advertisements
                           .catch { cause ->
                               stopProbeScanning()
                               isProbeScanning.set(false)
                               Log.e(LOG_TAG, "ProbeManager Scan Error: ${cause.localizedMessage}")
                               Log.e(LOG_TAG, Log.getStackTraceString(cause))
                           }
                           .onCompletion {
                               Log.d(LOG_TAG, "ProbeManager scanning stopped ...")
                               isProbeScanning.set(false)
                           }
                           .collect { advertisement ->
                               // probe advertisement received.  create the data class and emit
                               // into the flow for downstream processing.
                               val scanResult = advertisement.getPrivateProperty<Advertisement, ScanResult>("scanResult")
                               advertisement.manufacturerData(AdvertisingData.VENDOR_ID)?.let {
                                   _probeAdvertisements.emit(
                                       ProbeAdvertisingData(
                                           name = advertisement.name ?: "Unknown",
                                           mac = advertisement.address,
                                           rssi = advertisement.rssi,
                                           advertisingData = AdvertisingData(
                                               data = it.toUByteArray(),
                                               isConnectable = scanResult?.isConnectable ?: false
                                           )
                                       )
                                   )
                               }
                           }
                   }
                }
            }
        }

        fun stopProbeScanning() {
            probeAllMatchesScanJob?.cancelChildren()
            probeAllMatchesScanJob = null
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
    }
}