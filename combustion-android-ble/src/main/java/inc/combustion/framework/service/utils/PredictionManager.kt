/*
 * Project: Combustion Inc. Android Framework
 * File: PredictionManager.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2024. Combustion Inc.
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
package inc.combustion.framework.service.utils

import inc.combustion.framework.service.PredictionStatus
import inc.combustion.framework.service.Probe
import inc.combustion.framework.service.ProbePredictionMode
import inc.combustion.framework.service.ProbePredictionState
import inc.combustion.framework.service.ProbePredictionType
import java.util.*

/**
 * Handler to be called when a prediction value's updated.
 *
 * [shouldUpdateStatus] is used to indicate if the status should be updated--callers doing in-band
 * updates (i.e., updating due to an incoming status message) should set this to false to indicate
 * that the status is being updated elsewhere.
 */
typealias PredictionUpdatedCallback =
            (predictionInfo: PredictionManager.PredictionInfo?, shouldUpdateStatus: Boolean) -> Unit

class PredictionManager(
    private val timer: LinearizationTimer
) {
    interface LinearizationTimer {
        /**
         * Starts the linearization timer with the given [callback] and [intervalMs].
         */
        fun start(callback: () -> Unit, intervalMs: Long)
        fun stop()
        val isActive: Boolean
        val timeSinceStartMs: Long
    }

    data class PredictionInfo(
        val predictionState: ProbePredictionState,
        val predictionMode: ProbePredictionMode,
        val predictionType: ProbePredictionType,
        val setPointTemperature: Double,
        val estimatedCoreTemperature: Double,
        val heatStartTemperature: Double,
        val rawPredictionSeconds: UInt,
        val linearizedProgress: LinearizedProgress,
    ) {
        data class LinearizedProgress(
            val secondsRemaining: UInt? = null,
            val percentThroughCook: Int = 0,
        )

        companion object {
            internal fun fromPredictionStatus(
                predictionStatus: PredictionStatus?
            ): PredictionInfo? {
                return predictionStatus?.let {
                    PredictionInfo(
                        predictionState = it.predictionState,
                        predictionMode = it.predictionMode,
                        predictionType = it.predictionType,
                        setPointTemperature = it.setPointTemperature,
                        heatStartTemperature = it.heatStartTemperature,
                        rawPredictionSeconds = it.predictionValueSeconds,
                        estimatedCoreTemperature = it.estimatedCoreTemperature,
                        linearizedProgress = LinearizedProgress()
                    )
                }
            }

            fun fromProbe(
                probe: Probe?,
            ): PredictionInfo? {
                return probe?.let {
                    return PredictionInfo(
                        predictionState = it.predictionState ?: return null,
                        predictionMode = it.predictionMode ?: return null,
                        predictionType = it.predictionType ?: return null,
                        setPointTemperature = it.setPointTemperatureCelsius ?: return null,
                        heatStartTemperature = it.heatStartTemperatureCelsius ?: return null,
                        rawPredictionSeconds = it.rawPredictionSeconds ?: return null,
                        estimatedCoreTemperature = it.estimatedCoreCelsius ?: return null,
                        linearizedProgress = LinearizedProgress()
                    )
                }
            }
        }
    }

    companion object {
        /// Prediction is considered stale after 15 seconds
        private const val PREDICTION_STALE_TIMEOUT = 15000L // 15 seconds in ms

        /// Cap the prediction to 6 hours
        private const val MAX_PREDICTION_TIME: UInt = 21600u // 6 hours

        /// Number of samples to wait between updates to prediction 'seconds remaining',
        /// for syncing time remaining across apps, Displays etc.
        private const val PREDICTION_TIME_UPDATE_COUNT: UInt = 3u

        /// Number of prediction seconds at or above which to round the 'time remaining' display.
        private const val LOW_RESOLUTION_CUTOFF_SECONDS: UInt = 300u // 5 minutes

        /// Resolution of prediction display in 'low resolution' mode.
        private const val LOW_RESOLUTION_PRECISION_SECONDS: UInt = 15u

        /// Rate at which the linearization timer is run
        private const val LINEARIZATION_UPDATE_RATE_MS: Double = 200.0

        /// Rate at which the prediction status information is transmitted
        private const val PREDICTION_STATUS_RATE_MS: Double = 5000.0
    }

    private var previousPredictionInfo: PredictionInfo? = null
    private var previousSequenceNumber: UInt? = null

    private var linearizationTargetSeconds = 0
    private var linearizationTimerUpdateValue: Double = 0.0
    private var currentLinearizationMs: Double = 0.0
    private var runningLinearization = false

    private var predictionUpdatedCallback: PredictionUpdatedCallback? = null

    /**
     * Sets a callback to be invoked when the prediction status is updated. It's called in-band when
     * [updatePredictionStatus] is called, and out-of-band when the linearization timer updates the
     * status. See [PredictionUpdatedCallback] for more information on how it's invoked.
     */
    fun setPredictionCallback(predictionUpdatedCallback: PredictionUpdatedCallback) {
        this.predictionUpdatedCallback = predictionUpdatedCallback
    }

    /**
     * Updates the prediction status with the updated values in [predictionStatus] and
     * [sequenceNumber]. Returns the updated prediction information, or null if [sequenceNumber] has
     * not increased since the last call to this function (or if [predictionStatus] is null).
     *
     * [predictionUpdatedCallback] is called with the [shouldUpdateStatus] parameter set to false, so it's
     * on the caller of this function to update their status appropriately.
     */
    fun updatePredictionStatus(
        predictionStatus: PredictionStatus?,
        sequenceNumber: UInt,
    ): PredictionInfo? {
        // Duplicate status messages are sent when prediction is started. Ignore the duplicate sequence number
        // unless the prediction information has changed
        previousSequenceNumber?.let {
            if (it == sequenceNumber &&
                predictionStatus?.setPointTemperature == previousPredictionInfo?.setPointTemperature
            ) {
                return null
            }
        }

        // Stop the previous timer
        timer.stop()

        // Update prediction information with latest prediction status from probe
        val predictionInfo = infoFromStatus(predictionStatus, sequenceNumber)

        // Save sequence number to check for duplicates
        previousSequenceNumber = sequenceNumber

        // Publish new prediction info
        publishPredictionInfo(predictionInfo = predictionInfo, shouldUpdateStatus = false)

        return predictionInfo
    }

    private fun infoFromStatus(
        predictionStatus: PredictionStatus?,
        sequenceNumber: UInt
    ): PredictionInfo? {
        val status = predictionStatus ?: return null

        val secondsRemaining = secondsRemaining(status, sequenceNumber)

        return PredictionInfo(
            predictionState = status.predictionState,
            predictionMode = status.predictionMode,
            predictionType = status.predictionType,
            setPointTemperature = status.setPointTemperature,
            estimatedCoreTemperature = status.estimatedCoreTemperature,
            heatStartTemperature = status.heatStartTemperature,
            rawPredictionSeconds = status.predictionValueSeconds,
            linearizedProgress = PredictionInfo.LinearizedProgress(
                secondsRemaining = secondsRemaining,
                percentThroughCook = percentThroughCook(status)
            ),
        )
    }

    private fun secondsRemaining(predictionStatus: PredictionStatus, sequenceNumber: UInt): UInt? {
        // Do not return a value if not in predicting state
        if (predictionStatus.predictionState != ProbePredictionState.PREDICTING) {
            return null
        }

        // Do not return a value if above max seconds remaining
        if (predictionStatus.predictionValueSeconds > MAX_PREDICTION_TIME) {
            return null
        }

        val previousSecondsRemaining = previousPredictionInfo?.linearizedProgress?.secondsRemaining

        if (predictionStatus.predictionValueSeconds > LOW_RESOLUTION_CUTOFF_SECONDS) {
            runningLinearization = false

            // If the prediction is longer than the low-resolution cutoff, only update every few samples
            // (unless we don't yet have a value), using modulo to sync with other apps, Displays etc.
            if (previousSecondsRemaining == null || sequenceNumber % PREDICTION_TIME_UPDATE_COUNT == 0u) {
                // In low-resolution mode, round the value to the nearest 15 seconds.
                val remainder =
                    predictionStatus.predictionValueSeconds % LOW_RESOLUTION_PRECISION_SECONDS
                return if (remainder > (LOW_RESOLUTION_PRECISION_SECONDS / 2u)) {
                    // Round up
                    predictionStatus.predictionValueSeconds + (LOW_RESOLUTION_PRECISION_SECONDS - remainder)
                } else {
                    // Round down
                    predictionStatus.predictionValueSeconds - remainder
                }
            } else {
                return previousSecondsRemaining
            }
        } else {

            // If we're less than the 'low-resolution' cutoff time, linearize the value

            // Calculate new linearization target, when next prediction status arrives (in 5 seconds)
            // the linearization should hit the current prediction - 5 seconds
            val predictionUpdateRateSeconds = (PREDICTION_STATUS_RATE_MS / 1000.0).toUInt()

            linearizationTargetSeconds =
                if (predictionStatus.predictionValueSeconds < predictionUpdateRateSeconds) {
                    0
                } else {
                    (predictionStatus.predictionValueSeconds - predictionUpdateRateSeconds).toInt()
                }

            if (!runningLinearization) {
                // If not already running linearization, then initialize values
                currentLinearizationMs = predictionStatus.predictionValueSeconds.toDouble() * 1000.0
                linearizationTimerUpdateValue = LINEARIZATION_UPDATE_RATE_MS
            } else {
                val intervalCount = PREDICTION_STATUS_RATE_MS / LINEARIZATION_UPDATE_RATE_MS
                linearizationTimerUpdateValue =
                    (currentLinearizationMs - linearizationTargetSeconds * 1000.0) / intervalCount
            }

            // Create a new linearization timer
            timer.stop()
            timer.start(::updatePredictionSeconds, LINEARIZATION_UPDATE_RATE_MS.toLong())

            runningLinearization = true

            return (currentLinearizationMs / 1000.0).toUInt()
        }
    }

    private fun updatePredictionSeconds() {
        val previousInfo = previousPredictionInfo ?: return

        currentLinearizationMs -= linearizationTimerUpdateValue

        // Don't let the linearization value go below 0 or the UInt conversion will crash.
        if (currentLinearizationMs < 0.0) {
            currentLinearizationMs = 0.0
        }

        val secondsRemaining = (currentLinearizationMs / 1000.0).toUInt()

        val predictionInfo = PredictionInfo(
            predictionState = previousInfo.predictionState,
            predictionMode = previousInfo.predictionMode,
            predictionType = previousInfo.predictionType,
            setPointTemperature = previousInfo.setPointTemperature,
            estimatedCoreTemperature = previousInfo.estimatedCoreTemperature,
            heatStartTemperature = previousInfo.heatStartTemperature,
            rawPredictionSeconds = previousInfo.rawPredictionSeconds,
            linearizedProgress = PredictionInfo.LinearizedProgress(
                secondsRemaining = secondsRemaining,
                percentThroughCook = previousInfo.linearizedProgress.percentThroughCook,
            ),
        )

        // Publish new prediction info
        publishPredictionInfo(predictionInfo = predictionInfo, shouldUpdateStatus = true)

        // Stop the timer if the prediction has gone stale
        if (timer.timeSinceStartMs >= PREDICTION_STALE_TIMEOUT) {
            timer.stop()
        }
    }

    private fun percentThroughCook(predictionStatus: PredictionStatus): Int {
        val start = predictionStatus.heatStartTemperature
        val end = predictionStatus.setPointTemperature
        val core = predictionStatus.estimatedCoreTemperature

        // Max percentage is 100
        if (core > end) {
            return 100
        }

        // Minimum percentage is 0
        if (start > core) {
            return 0
        }

        // This should never happen, but would cause a crash
        if (end == start) {
            return 100
        }

        return (((core - start) / (end - start)) * 100.0).toInt()
    }

    /**
     * Caches the prediction information [predictionInfo], and calls back to the probe manager with
     * [predictionUpdatedCallback] to update the prediction status. See [PredictionUpdatedCallback] for more
     * information on how [shouldUpdateStatus] is used.
     */
    private fun publishPredictionInfo(
        predictionInfo: PredictionInfo?, shouldUpdateStatus: Boolean
    ) {
        // Save prediction information
        previousPredictionInfo = predictionInfo

        // Send new value to Probe manager
        predictionUpdatedCallback?.let { it(predictionInfo, shouldUpdateStatus) }
    }
}