/*
 * Project: Combustion Inc. Android Framework
 * File: AdvertisingArbitrator.kt
 * Author: https://github.com/miwright2
 *
 * MIT License
 *
 * Copyright (c) 2023. Combustion Inc.
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
package inc.combustion.framework.ble

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
            return true
        }

        // if the device that advertised this packet has a lower hop count then the preferred
        // advertiser for this mode, then switch to this new device and return true to publish.
        if(device.hopCount < preferred.hopCount) {
            preferred.device = device
            preferred.monitor.activity()
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
            return true
        }

        // otherwise, this packet is from a device with a hop count that is greater than
        // or equal to the preferred advertiser and the preferred advertiser is active,
        // so stick with the preferred and return false so that we don't update.
        return false
    }
}