/*
 * Project: Combustion Inc. Android Framework
 * File: CombustionService.kt
 * Author: https://github.com/miwright2
 *
 * MIT License
 *
 * Copyright (c) 2022. Combustion Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package inc.combustion.framework.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleService
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.NetworkManager
import inc.combustion.framework.ble.dfu.DfuManager
import inc.combustion.framework.log.LogManager
import inc.combustion.framework.service.dfu.DfuProductType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.asKotlinRandom

/**
 * Android Service for managing Combustion Inc. Predictive Thermometers
 */
@ExperimentalCoroutinesApi
class CombustionService : LifecycleService() {

    private val binder = CombustionServiceBinder()

    internal var dfuManager: DfuManager? = null

    internal companion object {
        private var dfuNotificationTarget: Class<out Activity?>? = null
        private val serviceIsStarted = AtomicBoolean(false)
        private val serviceIsStopping = AtomicBoolean(false)
        private var latestFirmware: Map<DfuProductType, Uri> = emptyMap()
        var serviceNotification: Notification? = null
        var notificationId = 0
        lateinit var settings: DeviceManager.Settings
        private var onServiceStartedCallback: (() -> Unit)? = null

        fun start(
            context: Context,
            notification: Notification?,
            dfuNotificationTarget: Class<out Activity?>?,
            serviceSettings: DeviceManager.Settings = DeviceManager.Settings(),
            latestFirmware: Map<DfuProductType, Uri>,
            onServiceStartedCallback: (() -> Unit)?,
        ): Int {
            if (!serviceIsStarted.get() || serviceIsStopping.get()) {
                settings = serviceSettings
                serviceNotification = notification
                this.dfuNotificationTarget = dfuNotificationTarget
                this.latestFirmware = latestFirmware
                this.onServiceStartedCallback = onServiceStartedCallback
                notificationId = ThreadLocalRandom.current().asKotlinRandom().nextInt()
                Intent(context, CombustionService::class.java).also { intent ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        serviceNotification?.let {
                            context.startForegroundService(intent)
                        } ?: run {
                            context.startService(intent)
                        }
                    } else {
                        context.startService(intent)
                    }
                }
                serviceIsStopping.set(false)
            }

            return notificationId
        }

        fun bind(context: Context, connection: ServiceConnection) {
            Intent(context, CombustionService::class.java).also { intent ->
                val flags = Context.BIND_AUTO_CREATE
                context.bindService(intent, connection, flags)
            }
        }

        fun stop(context: Context) {
            serviceIsStopping.set(true)
            Intent(context, CombustionService::class.java).also { intent ->
                context.stopService(intent)
            }
        }
    }

    inner class CombustionServiceBinder : Binder() {
        fun getService(): CombustionService = this@CombustionService
    }

    private fun startForeground() {
        serviceNotification?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(notificationId, it, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(notificationId, it)
            }
        }
    }

    private fun stopForeground() {
        serviceNotification?.let {
            val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            service.cancel(notificationId)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val bluetooth = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        if (bluetooth.adapter != null) {
            // Note: Older versions of Android running on emulator (e.g. Pixel 4 API 29) do not
            // emulate a Bluetooth adapter.  In this case, the BLE isn't functional.  This is consistent
            // with our manifest file requiring BLE.  So we check for null, and do not initialize the
            // main components of the service if it is null.  This allows the service to run, but
            // most of the API is non-functional, which is reasonable, since Bluetooth can't even be turned on.
            //
            // Newer versions of Android running on emulator (e.g. Pixel XL API 33) do emulate a Bluetooth adapter.

            // Initialize Network Manager
            NetworkManager.initialize(this, bluetooth.adapter, settings)

            // Allocate DFU Manager
            if (dfuManager == null) {
                dfuManager =
                    DfuManager(this, this, bluetooth.adapter, dfuNotificationTarget, latestFirmware)
            }

            // setup receiver to see when platform Bluetooth state changes
            registerReceiver(
                NetworkManager.instance.bluetoothAdapterStateReceiver,
                NetworkManager.instance.bluetoothAdapterStateIntentFilter,
            )
            onServiceStartedCallback?.invoke()
            onServiceStartedCallback = null
        }

        startForeground()

        serviceIsStarted.set(true)

        Log.d(LOG_TAG, "Combustion Android Service Started...")

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopForeground()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        // stop the service notification
        stopForeground()
        serviceNotification = null

        try {
            // in some situations we've seen onDestroy being called before NetworkManager
            // is initialized.  In that case, this line will throw.  We do our best effort
            // here to cleanup.  If we aren't able to get the instance, then there isn't
            // anything to cleanup so, just catch the exception.
            val manager = NetworkManager.instance

            manager.clearDevices()
            manager.finish()
            unregisterReceiver(manager.bluetoothAdapterStateReceiver)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Unable to cleanup NetworkManager ${e.stackTrace}")
        }

        LogManager.instance.clear()

        serviceIsStarted.set(false)
        serviceIsStopping.set(false)

        Log.d(LOG_TAG, "Combustion Android Service Destroyed...")
        super.onDestroy()
    }

    internal fun startDfuMode() {
        NetworkManager.instance.startDfuMode()
        dfuManager?.start()
    }

    internal fun stopDfuMode() {
        dfuManager?.stop()
        NetworkManager.instance.stopDfuMode()
    }
}