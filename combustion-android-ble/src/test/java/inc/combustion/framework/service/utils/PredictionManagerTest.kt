package inc.combustion.framework.service.utils

import inc.combustion.framework.service.PredictionStatus
import inc.combustion.framework.service.ProbePredictionMode
import inc.combustion.framework.service.ProbePredictionState
import inc.combustion.framework.service.ProbePredictionType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals

class PredictionManagerTest {
    private class LinearizationTimerImpl : PredictionManager.LinearizationTimer {
        private var callback: (() -> Unit)? = null

        var mutableTimeSinceStartMs: Long = 0

        override fun start(callback: () -> Unit, intervalMs: Long) {
            this.callback = callback
        }

        override fun stop() {
            callback = null
        }

        override val isActive: Boolean
            get() = callback != null

        override val timeSinceStartMs: Long
            get() = mutableTimeSinceStartMs

        fun triggerCallback() {
            callback?.invoke()
        }
    }

    private var timer: LinearizationTimerImpl? = null
    private var manager: PredictionManager? = null

    private val defaultPredictionStatus = PredictionStatus(
        predictionState = ProbePredictionState.PROBE_NOT_INSERTED,
        predictionMode = ProbePredictionMode.NONE,
        predictionType = ProbePredictionType.NONE,
        setPointTemperature = 100.0,
        heatStartTemperature = 0.0,
        predictionValueSeconds = 0u,
        estimatedCoreTemperature = 0.0,
    )
    private val defaultPredictionInfo = PredictionManager.PredictionInfo.fromPredictionStatus(
        defaultPredictionStatus
    )

    private var predictionInfo = PredictionManager.PredictionInfo.fromPredictionStatus(
        defaultPredictionStatus
    )
    private var shouldUpdateStatus = false

    @BeforeTest
    fun setUp() {
        predictionInfo = PredictionManager.PredictionInfo.fromPredictionStatus(
            defaultPredictionStatus
        )
        timer = LinearizationTimerImpl()
        manager = PredictionManager(timer!!).also { manager ->
            manager.setPredictionCallback(::onPredictionStatusChanged)
        }
    }

    @AfterTest
    fun tearDown() {
        manager = null
        timer = null
    }

    @Test
    fun `Updates return null if not predicting`() {
        val status = defaultPredictionStatus
        val info = PredictionManager.PredictionInfo.fromPredictionStatus(status)
        manager?.updatePredictionStatus(status, 0u)
        assertEquals(info, predictionInfo)
    }

    @Test
    fun `Updates outside of 5 minutes are rounded to 15 second intervals (predictable update)`() {
        verifyUpdatesOutsideOfFiveMinutes(
            secondsRemainingAdvance = 5u,
            secondsRemaining = 450u,
            secondsRemainingFinal = 435u,
            estimatedCoreTemperature = 30.0,
        )
    }

    @Test
    fun `Updates outside of 5 minutes are rounded to 15 second intervals (slow update)`() {
        verifyUpdatesOutsideOfFiveMinutes(
            secondsRemainingAdvance = 1u,
            secondsRemaining = 450u,
            secondsRemainingFinal = 450u,
            estimatedCoreTemperature = 30.0,
        )
    }

    @Test
    fun `Updates outside of 5 minutes are rounded to 15 second intervals (medium update)`() {
        verifyUpdatesOutsideOfFiveMinutes(
            secondsRemainingAdvance = 2u,
            secondsRemaining = 450u,
            secondsRemainingFinal = 435u,
            estimatedCoreTemperature = 30.0,
        )
    }

    @Test
    fun `Updates outside of 5 minutes are rounded to 15 second intervals (fast update)`() {
        verifyUpdatesOutsideOfFiveMinutes(
            secondsRemainingAdvance = 11u,
            secondsRemaining = 450u,
            secondsRemainingFinal = 405u,
            estimatedCoreTemperature = 30.0,
        )
    }

    @Test
    fun `Updates inside of 5 minutes are linearized (standard update)`() {
        val secondsRemainingAdvance = 5u
        var secondsRemaining = 240u
        var linearizedSecondsRemaining = secondsRemaining
        val secondsRemainingFinal = 225u
        var estimatedCoreTemperature = 30.0

        var status = defaultPredictionStatus.copy(
            predictionState = ProbePredictionState.PREDICTING,
            predictionValueSeconds = secondsRemaining,
            estimatedCoreTemperature = estimatedCoreTemperature,
        )
        var info = PredictionManager.PredictionInfo.fromPredictionStatus(status)?.copy(
            linearizedProgress = PredictionManager.PredictionInfo.LinearizedProgress(
                secondsRemaining = secondsRemaining,
                percentThroughCook = estimatedCoreTemperature.toInt(),
            )
        )

        manager?.updatePredictionStatus(status, 0u)
        info = info.getInfoWithUpdatedLinearizedSeconds(linearizedSecondsRemaining--)

        assertTrue(timer?.isActive ?: false)
        assertEquals(info, predictionInfo)

        // Progress the timer by 5 seconds
        repeat(5) { outer ->
            info = info.getInfoWithUpdatedLinearizedSeconds(linearizedSecondsRemaining--)

            // Progress the timer by 1 second
            repeat(5) { inner ->
                timer?.triggerCallback()
                assertEquals(info, predictionInfo)
                assertTrue(shouldUpdateStatus)
            }
        }

        // Progress the cook by 2 degrees and 5 seconds
        estimatedCoreTemperature += 2.0
        secondsRemaining -= secondsRemainingAdvance

        updateStatusAndInfo(
            status, info, estimatedCoreTemperature, secondsRemaining
        ).let { (newStatus, newInfo) ->
            status = newStatus
            info = newInfo
        }

        manager?.updatePredictionStatus(status, 1u)
        assertFalse(shouldUpdateStatus)
        assertTrue(timer?.isActive ?: false)

        // Progress the timer by 5 seconds
        repeat(5) {
            info = info.getInfoWithUpdatedLinearizedSeconds(linearizedSecondsRemaining--)

            // Progress the timer by 1 second
            repeat(5) {
                timer?.triggerCallback()
                assertEquals(info, predictionInfo)
            }
        }
    }

    @Test
    fun `Timer stops when prediction is stale`() {
        val secondsRemaining = 240u
        var linearizedSecondsRemaining = secondsRemaining
        val estimatedCoreTemperature = 30.0

        val status = defaultPredictionStatus.copy(
            predictionState = ProbePredictionState.PREDICTING,
            predictionValueSeconds = secondsRemaining,
            estimatedCoreTemperature = estimatedCoreTemperature,
        )
        var info = PredictionManager.PredictionInfo.fromPredictionStatus(status)?.copy(
            linearizedProgress = PredictionManager.PredictionInfo.LinearizedProgress(
                secondsRemaining = secondsRemaining,
                percentThroughCook = estimatedCoreTemperature.toInt(),
            )
        )

        manager?.updatePredictionStatus(status, 0u)
        info = info.getInfoWithUpdatedLinearizedSeconds(linearizedSecondsRemaining--)

        assertTrue(timer?.isActive ?: false)
        assertEquals(info, predictionInfo)

        // Progress the timer by 5 seconds
        repeat(5) { outer ->
            info = info.getInfoWithUpdatedLinearizedSeconds(linearizedSecondsRemaining--)

            // Progress the timer by 1 second
            repeat(5) { inner ->
                timer?.triggerCallback()
                assertEquals(info, predictionInfo)
            }
        }

        assertTrue(timer?.isActive ?: false)
        timer?.mutableTimeSinceStartMs = 20000
        timer?.triggerCallback()
        assertFalse(timer?.isActive ?: true)
    }

    private fun verifyUpdatesOutsideOfFiveMinutes(
        secondsRemainingAdvance: UInt,
        secondsRemaining: UInt,
        secondsRemainingFinal: UInt,
        estimatedCoreTemperature: Double,
    ) {
        var localEstimatedCoreTemperature = estimatedCoreTemperature
        var localSecondsRemaining = secondsRemaining

        var status = defaultPredictionStatus.copy(
            predictionState = ProbePredictionState.PREDICTING,
            predictionValueSeconds = secondsRemaining,
            estimatedCoreTemperature = estimatedCoreTemperature,
        )
        var info = PredictionManager.PredictionInfo.fromPredictionStatus(status)?.copy(
            linearizedProgress = PredictionManager.PredictionInfo.LinearizedProgress(
                secondsRemaining = secondsRemaining,
                percentThroughCook = estimatedCoreTemperature.toInt(),
            )
        )

        for (sequenceNumber in 0u until 3u) {
            manager?.updatePredictionStatus(status, sequenceNumber)
            assertEquals(info, predictionInfo)

            localEstimatedCoreTemperature += 2.0
            localSecondsRemaining -= secondsRemainingAdvance
            status = status.copy(
                estimatedCoreTemperature = estimatedCoreTemperature,
                predictionValueSeconds = secondsRemaining,
            )
            info = info?.copy(
                estimatedCoreTemperature = estimatedCoreTemperature,
                rawPredictionSeconds = secondsRemaining,
                linearizedProgress = PredictionManager.PredictionInfo.LinearizedProgress(
                    secondsRemaining = secondsRemaining,
                    percentThroughCook = estimatedCoreTemperature.toInt(),
                )
            )
        }

        localSecondsRemaining -= secondsRemainingAdvance
        status = status.copy(
            predictionValueSeconds = localSecondsRemaining,
            estimatedCoreTemperature = estimatedCoreTemperature,
        )
        info = info?.copy(
            rawPredictionSeconds = localSecondsRemaining,
            linearizedProgress = PredictionManager.PredictionInfo.LinearizedProgress(
                secondsRemaining = secondsRemainingFinal,
                percentThroughCook = estimatedCoreTemperature.toInt(),
            )
        )

        manager?.updatePredictionStatus(status, 3u)
        assertEquals(info, predictionInfo)
    }

    private fun onPredictionStatusChanged(
        predictionInfo: PredictionManager.PredictionInfo?,
        shouldUpdateStatus: Boolean,
    ) {
        this.predictionInfo = predictionInfo
        this.shouldUpdateStatus = shouldUpdateStatus
    }

    private fun PredictionManager.PredictionInfo?.getInfoWithUpdatedLinearizedSeconds(
        seconds: UInt,
    ): PredictionManager.PredictionInfo? {
        return this?.copy(
            linearizedProgress = linearizedProgress.copy(
                secondsRemaining = seconds,
            )
        )
    }

    private fun updateStatusAndInfo(
        status: PredictionStatus,
        info: PredictionManager.PredictionInfo?,
        estimatedCoreTemperature: Double,
        secondsRemaining: UInt,
    ): Pair<PredictionStatus, PredictionManager.PredictionInfo?> {
        return Pair(
            status.copy(
                predictionValueSeconds = secondsRemaining,
                estimatedCoreTemperature = estimatedCoreTemperature,
            ),
            info?.copy(
                rawPredictionSeconds = secondsRemaining,
                estimatedCoreTemperature = estimatedCoreTemperature,
                linearizedProgress = PredictionManager.PredictionInfo.LinearizedProgress(
                    secondsRemaining = secondsRemaining,
                    percentThroughCook = estimatedCoreTemperature.toInt(),
                )
            )
        )
    }
}