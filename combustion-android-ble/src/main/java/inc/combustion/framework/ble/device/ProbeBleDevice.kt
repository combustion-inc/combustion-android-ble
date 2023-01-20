/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeBleDevice.kt
 * Author: http://github.com/miwright2
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

package inc.combustion.framework.ble.device

import android.bluetooth.BluetoothAdapter
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.juul.kable.characteristicOf
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.ProbeStatus
import inc.combustion.framework.ble.scanning.BaseAdvertisingData
import inc.combustion.framework.ble.scanning.ProbeAdvertisingData
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

internal class ProbeBleDevice (
    mac: String,
    owner: LifecycleOwner,
    baseAdvertisement: BaseAdvertisingData,
    adapter: BluetoothAdapter,
    private val probeUartBleDevice: ProbeUartBleDevice = ProbeUartBleDevice(mac, owner, baseAdvertisement, adapter),
) : IProbeBleDeviceBase,
    IProbeUartBleDevice by probeUartBleDevice,
    IProbeLogResponseBleDevice by probeUartBleDevice {

    private val _probeStatusFlow = MutableSharedFlow<ProbeStatus>(
        replay = 0, extraBufferCapacity = 10, BufferOverflow.DROP_OLDEST)

    override val probeStatusFlow = _probeStatusFlow.asSharedFlow()

    private var advertisement = baseAdvertisement as ProbeAdvertisingData
        private set

    val linkId: LinkID
        get() {
            return ProbeBleDeviceBase.makeLinkId(advertisement)
        }

    val id = probeUartBleDevice.id

    companion object {
        val DEVICE_STATUS_CHARACTERISTIC = characteristicOf(
            service = "00000100-CAAB-3792-3D44-97AE51C1407A",
            characteristic = "00000101-CAAB-3792-3D44-97AE51C1407A"
        )
    }

    init {
        observeProbeStatusCharacteristic()
    }

    fun connect() = probeUartBleDevice.connect()
    fun disconnect() = probeUartBleDevice.disconnect()

    private fun observeProbeStatusCharacteristic() {
        probeUartBleDevice.jobManager.addJob(probeUartBleDevice.owner.lifecycleScope.launch {
            probeUartBleDevice.peripheral.observe(DEVICE_STATUS_CHARACTERISTIC)
                .onCompletion {
                    Log.d(LOG_TAG, "Device Status Characteristic Monitor Complete")
                }
                .catch {
                    Log.i(LOG_TAG, "Device Status Characteristic Monitor Catch: $it")
                }
                .collect { data ->
                    ProbeStatus.fromRawData(data.toUByteArray())?.let { status ->
                        _probeStatusFlow.emit(status)
                    }
                }
        })
    }
}
