package inc.combustion.framework.service

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import inc.combustion.framework.LOG_TAG
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicBoolean

class DeviceManager {
    private val onBoundInitList = mutableListOf<() -> Unit>()
    private lateinit var service: CombustionService

    companion object {
        private lateinit var INSTANCE: DeviceManager
        private lateinit var app : Application
        private lateinit var onServiceBound : (deviceManager: DeviceManager) -> Unit
        private val initialized = AtomicBoolean(false)
        private val bound = AtomicBoolean(false)

        private val connection = object : ServiceConnection {

            override fun onServiceConnected(className: ComponentName, serviceBinder: IBinder) {
                val binder = serviceBinder as CombustionService.CombustionServiceBinder
                INSTANCE.service = binder.getService()

                bound.set(true)
                onServiceBound(INSTANCE)

                INSTANCE.onBoundInitList.forEach{ initCallback ->
                    initCallback()
                }
            }

            override fun onServiceDisconnected(arg0: ComponentName) {
                bound.set(false)
                bindCombustionService()
            }
        }

        val instance: DeviceManager get() = INSTANCE

        fun initialize(application: Application, onBound: (deviceManager: DeviceManager) -> Unit) {
            if(!initialized.getAndSet(true)) {
                app = application
                onServiceBound = onBound
                INSTANCE = DeviceManager()
            }
        }

        fun startCombustionService() {
            CombustionService.start(app.applicationContext)
        }

        fun bindCombustionService() {
            if(!bound.get()) {
                CombustionService.bind(app.applicationContext, connection)
            }
        }

        fun unbindCombustionService() {
            app.unbindService(connection)
            bound.set(false)
        }

        fun stopCombustionService() {
            CombustionService.stop(app.applicationContext)
        }
    }

    val discoveredProbesFlow : SharedFlow<DeviceDiscoveredEvent>
        get() = service.discoveredProbesFlow

    val isScanningForDevices: Boolean
        get() = service.isScanningForProbes

    val discoveredProbes: List<String> get() = service.discoveredProbes

    init {
        Log.d(LOG_TAG, "Singleton initialized")
    }

    fun registerOnBoundInitialization(callback : () -> Unit) {
        // if service is already bound, then run the callback right away.
        if(bound.get()) {
            callback()
        }
        // otherwise queue the callback to be run when the service is bound.
        else {
            onBoundInitList.add(callback)
        }
    }

    fun startScanningForProbes() = service.startScanningForProbes()

    fun stopScanningForProbes() = service.stopScanningForProbes()

    fun probeFlow(serialNumber: String) = service.probeFlow(serialNumber)

    fun probe(serialNumber: String): Probe? = service.probeState(serialNumber)

    fun connect(serialNumber : String) = service.connect(serialNumber)

    fun disconnect(serialNumber: String) = service.disconnect(serialNumber)

    fun startRecordTransfer(serialNumber: String) = service.requestLogsFromDevice(serialNumber)

    fun exportLogsForDevice(serialNumber: String): List<LoggedProbeDataPoint>? =
        service.exportLogsForDevice(serialNumber)

    fun createLogFlowForDevice(serialNumber: String): Flow<LoggedProbeDataPoint> =
        service.createLogFlowForDevice(serialNumber)

    fun clearDevices() = service.clearDevices()
}