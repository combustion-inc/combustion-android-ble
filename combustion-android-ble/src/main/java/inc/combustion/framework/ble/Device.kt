package inc.combustion.framework.ble

import android.bluetooth.BluetoothAdapter
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.juul.kable.Peripheral
import com.juul.kable.characteristicOf
import com.juul.kable.peripheral
import inc.combustion.framework.LOG_TAG
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

open class Device (
    private val mac: String,
    private val _owner: LifecycleOwner,
    adapter: BluetoothAdapter
){
    companion object {
        const val DEVICE_INFO_SERVICE_UUID = "180a"
        const val UART_SERVICE_UUID        = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
        const val DISCONNECT_TIMEOUT_MS    = 2500L

        val UART_RX_CHARACTERISTIC = characteristicOf(
            service = UART_SERVICE_UUID,
            characteristic = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
        )

        val UART_TX_CHARACTERISTIC = characteristicOf(
            service = UART_SERVICE_UUID,
            characteristic = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
        )
    }

    class IdleMonitor() {
        var lastUpdateTime : Long = 0

        fun activity() {
            lastUpdateTime = SystemClock.elapsedRealtime()
        }

        fun isIdle(timeout: Long): Boolean {
            return (SystemClock.elapsedRealtime() - lastUpdateTime) >= timeout
        }
    }

    private val _jobList = mutableListOf<Job>()

    protected val monitor = IdleMonitor()
    protected var peripheral: Peripheral =
        _owner.lifecycleScope.peripheral(adapter.getRemoteDevice(mac)) {
            logging {
                // The following enables logging in Kable

                // engine = SystemLogEngine
                // level = Logging.Level.Events
                // format = Logging.Format.Multiline
                // data = Hex

            }
        }

    open suspend fun checkIdle() { }

    open fun connect() {
        _owner.lifecycleScope.launch {
            Log.d(LOG_TAG, "Connecting to $mac")
            try {
                peripheral.connect()
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Connect Error: ${e.localizedMessage}")
                Log.e(LOG_TAG, Log.getStackTraceString(e))
            }
        }
    }

    open fun disconnect() {
        _owner.lifecycleScope.launch {
            withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
                peripheral.disconnect()
            }
        }
    }

    fun addJob(job: Job) {
        _jobList.add(job)
    }

    fun finish() {
        disconnect()
        cancelJobs()
    }

    private fun cancelJobs() {
        _jobList.forEach { it.cancel() }
    }
}