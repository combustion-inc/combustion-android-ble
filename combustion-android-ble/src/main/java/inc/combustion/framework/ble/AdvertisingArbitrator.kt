/*
 * Project: Combustion Inc
 * File: AdvertisingArbitrator.kt
 * Author: TODO
 *
 * Copyright (c) 2023. Combustion Inc.
 */
package inc.combustion.framework.ble

import android.util.Log
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.device.ProbeBleDeviceBase
import inc.combustion.framework.ble.scanning.CombustionAdvertisingData
import inc.combustion.framework.service.ProbeMode

internal class AdvertisingArbitrator {
    private data class Preferred(
        /// current preferred advertiser
        var device: ProbeBleDeviceBase? = null,

        /// monitors the preferred advertiser for idle
        val monitor: IdleMonitor = IdleMonitor()
    ) {
        val hopCount: UInt get() { return device?.hopCount ?: UInt.MAX_VALUE }
    }

    companion object {
        private const val NORMAL_MODE_IDLE_TIMEOUT = 5000L
        private const val INSTANT_READ_IDLE_TIMEOUT = 3000L
    }

    private val instantReadMode: Preferred = Preferred()
    private val normalMode: Preferred = Preferred()

    fun shouldUpdate(device: ProbeBleDeviceBase, advertisement: CombustionAdvertisingData): Boolean {
        return when (advertisement.mode) {
            ProbeMode.INSTANT_READ -> shouldUpdateForMode(device, instantReadMode, INSTANT_READ_IDLE_TIMEOUT)
            ProbeMode.NORMAL -> shouldUpdateForMode(device, normalMode, NORMAL_MODE_IDLE_TIMEOUT)
            else -> false
        }
    }

    /**
     * Determines if we should publish data from the advertising packet received by [device]
     * based on the current preferred advertiser ([preferred]) and the idle timeout ([idleTimeout])
     * for the advertising packet's mode (either Normal Mode or Instant Read).
     *
     * Function may change the preferred advertiser to [device].
     *
     * @param device the MeatNet node that advertised the packet being arbitrated.
     * @param preferred the preferred advertising MeatNet node for the advertising packet's mode.
     * @param idleTimeout the idle timeout for the advertising packet's mode.
     *
     * @return true if the data from the packet should be published, false otherwise.
     */
    private fun shouldUpdateForMode(device: ProbeBleDeviceBase, preferred: Preferred, idleTimeout: Long): Boolean {

        // initial condition.  if we don't currently have a preferred advertiser for this mode,
        // then make it the preferred advertiser and return true to publish data from this packet.
        if(preferred.device == null) {
            preferred.device = device
            preferred.monitor.activity()
            logNewPreferred(device, idleTimeout)
            return true
        }

        // if the device that advertised this packet has a lower hop count then the preferred
        // advertiser for his mode, then switch to this new device and return true to publish.
        if(device.hopCount < preferred.hopCount) {
            preferred.device = device
            preferred.monitor.activity()
            logNewPreferred(device, idleTimeout)
            return true
        }

        // if this packet is from the preferred advertiser, then service the watch dog and
        // return true to publish the data from this packet.
        if(device == preferred.device) {
            preferred.monitor.activity()
            return true
        }

        // if the preferred advertiser is idle (i.e. we haven't fallen through the above
        // conditions within the timeout) then make the device that advertised this packet
        // the preferred advertiser, and return true to publish the data from this packet.
        //
        // this new preferred advertiser will have a hop count >= the hop count.
        if(preferred.monitor.isIdle(idleTimeout)) {
            preferred.device = device
            preferred.monitor.activity()
            logNewPreferred(device, idleTimeout)
            return true
        }

        // otherwise, this packet is from a device with a hop count that is greater than
        // or equal to the preferred advertiser and the preferred advertiser is active,
        // so stick with the preferred and return false so that we don't update.
        return false
    }

    private fun logNewPreferred(device: ProbeBleDeviceBase, timeout: Long) {
        /*
        val mode = if(timeout == INSTANT_READ_IDLE_TIMEOUT) ProbeMode.INSTANT_READ else ProbeMode.NORMAL
        Log.w(LOG_TAG, "$mode Preferred Advertiser is ${device.id} with hop count: ${device.hopCount}")
         */
    }
}