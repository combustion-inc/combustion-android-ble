/*
 * Project: Combustion Inc. Android Framework
 * File: DeviceManager.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2025. Combustion Inc.
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

import android.util.Log
import inc.combustion.framework.LOG_TAG
import inc.combustion.framework.ble.device.DeviceID
import inc.combustion.framework.ble.uart.LogResponse
import inc.combustion.framework.service.ProbeUploadState
import inc.combustion.framework.service.SessionInformation
import inc.combustion.framework.service.SpecializedDevice
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

internal abstract class BleManager {

    companion object {
        const val IGNORE_PROBES = false
        const val IGNORE_REPEATERS = false
        const val IGNORE_GAUGES = false

        const val OUT_OF_RANGE_TIMEOUT = 15000L
    }

    abstract val serialNumber: String

    abstract val device: SpecializedDevice

    abstract val deviceFlow: StateFlow<SpecializedDevice>

    abstract val normalModeStatusFlow: SharedFlow<SpecializedDeviceStatus>

    // current minimum sequence number for the device
    abstract val minSequenceNumber: UInt?


    // current maximum sequence number for the device
    abstract val maxSequenceNumber: UInt?

    // the flow that is consumed to get LogResponses from MeatNet
    abstract val logResponseFlow: SharedFlow<LogResponse>

    // manages long-running coroutine scopes for data handling
    private val jobManager = JobManager()

    protected abstract val arbitrator: DataLinkArbitrator<*,*>

    // provides a set of all the nodes that are currently connected
    protected val _nodeConnectionFlow = MutableSharedFlow<Set<String>>(
        replay = 0, extraBufferCapacity = 10, BufferOverflow.DROP_OLDEST
    )

    val nodeConnectionFlow = _nodeConnectionFlow.asSharedFlow()

    // signals when logs are no longer being added to LogManager.
    var logTransferCompleteCallback: () -> Unit = { }

    abstract var uploadState: ProbeUploadState

    abstract var recordsDownloaded: Int

    abstract var logUploadPercent: UInt

    // current session information for the device
    var sessionInfo: SessionInformation? = null
        protected set

    fun addJob(serialNumber: String, job: Job) = jobManager.addJob(serialNumber, job)

    protected fun makeRequestId(): UInt {
        return (0u..UInt.MAX_VALUE).random()
    }

    abstract fun sendLogRequest(startSequenceNumber: UInt, endSequenceNumber: UInt)

    fun finish(deviceIdsToDisconnect: Set<DeviceID>? = null) {
        Log.d(LOG_TAG, "BleManager.finish($deviceIdsToDisconnect) for ($serialNumber)")

        arbitrator.finish(
            nodeAction = {
                // There's a couple specific things that come out of unlinking a probe that need to
                // be addressed here:
                //
                // - Jobs are created on repeated probes (nodes) that need to be cancelled so that
                //   we don't continue to obtain data for probes that we're disconnected from. If
                //   all jobs on a node are blindly cancelled, then we'll likely cancel jobs that
                //   are still needed for other probes connected to this node. The [jobKey]
                //   parameter allows for selective cancellation of jobs.
                // - On a related note, we need to be able to selectively disconnect from nodes as
                //   some are still providing data from other probes.
                it.finish(
                    jobKey = serialNumber,
                    disconnect = deviceIdsToDisconnect?.contains(it.id) != false
                )
            },
            directConnectionAction = {
                it.disconnect()
            }
        )

        jobManager.cancelJobs()
    }
}