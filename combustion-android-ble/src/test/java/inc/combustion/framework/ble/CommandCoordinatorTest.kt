/*
 * Project: Combustion Inc. Android Framework
 * File: CommandCoordinatorTest.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2026. Combustion Inc.
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

import inc.combustion.framework.ble.uart.MessageType
import inc.combustion.framework.ble.uart.meatnet.NodeMessageType
import inc.combustion.framework.service.ProbeMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CommandCoordinator] is self-contained, so these tests exercise it directly rather than through
 * one of its owning managers (`EngineManager`, `GaugeManager`, `ProbeManager`,
 * `WiFiNodesManager`) -- see those classes' test files for coverage of the actual command
 * wiring.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommandCoordinatorTest {

    private fun nodeKey(requestId: UInt = 1u) =
        CommandAttemptKey.Node(NodeMessageType.SET_ENGINE_CONTROL_DEVICE, requestId)

    private fun directKey(deviceId: String = "device-1") =
        CommandAttemptKey.Direct(MessageType.SET_POWER_MODE, deviceId)

    private fun status(): SpecializedDeviceStatus = mockk(relaxed = true)

    @Test
    fun `completes via a response matching a registered attempt key`() = runTest {
        val coordinator = CommandCoordinator(requestTimeoutMs = 1000, retryIntervalMs = 100)
        val key = nodeKey()

        val resultDeferred = async {
            coordinator.sendRoutedCommand(
                targetSerialNumber = "12345678",
                send = { setOf(key) },
                isConfirmed = { false },
            )
        }

        // Let sendRoutedCommand register its attempt before completing it.
        testScheduler.runCurrent()
        assertTrue(coordinator.completeAttempt(key, success = true))

        assertEquals(CommandResult.SUCCESS, resultDeferred.await())
    }

    @Test
    fun `retries by calling send again after the retry interval elapses`() = runTest {
        val coordinator = CommandCoordinator(requestTimeoutMs = 10_000, retryIntervalMs = 1000)
        var sendCount = 0

        val resultDeferred = async {
            coordinator.sendRoutedCommand(
                targetSerialNumber = "12345678",
                send = {
                    sendCount++
                    setOf(nodeKey(requestId = sendCount.toUInt()))
                },
                isConfirmed = { false },
            )
        }

        testScheduler.runCurrent()
        assertEquals(1, sendCount)

        advanceTimeBy(1001)
        testScheduler.runCurrent()
        assertEquals(2, sendCount)

        advanceTimeBy(1001)
        testScheduler.runCurrent()
        assertEquals(3, sendCount)

        // Now let the most recent attempt complete so the coroutine finishes cleanly.
        assertTrue(coordinator.completeAttempt(nodeKey(requestId = sendCount.toUInt()), success = true))
        assertEquals(CommandResult.SUCCESS, resultDeferred.await())
    }

    @Test
    fun `send is passed a 1-based attempt number that increments on each retry`() = runTest {
        val coordinator = CommandCoordinator(requestTimeoutMs = 10_000, retryIntervalMs = 1000)
        val observedAttemptNumbers = mutableListOf<Int>()

        val resultDeferred = async {
            coordinator.sendRoutedCommand(
                targetSerialNumber = "12345678",
                send = { attemptNumber ->
                    observedAttemptNumbers += attemptNumber
                    setOf(nodeKey(requestId = attemptNumber.toUInt()))
                },
                isConfirmed = { false },
            )
        }

        testScheduler.runCurrent()
        advanceTimeBy(1001)
        testScheduler.runCurrent()
        advanceTimeBy(1001)
        testScheduler.runCurrent()

        assertEquals(listOf(1, 2, 3), observedAttemptNumbers)

        assertTrue(coordinator.completeAttempt(nodeKey(requestId = 3u), success = true))
        assertEquals(CommandResult.SUCCESS, resultDeferred.await())
    }

    @Test
    fun `fails after the overall timeout with no completion`() = runTest {
        val coordinator = CommandCoordinator(requestTimeoutMs = 1000, retryIntervalMs = 200)

        val result = coordinator.sendRoutedCommand(
            targetSerialNumber = "12345678",
            send = { setOf(nodeKey()) },
            isConfirmed = { false },
        )

        assertEquals(CommandResult.FAILURE, result)
    }

    @Test
    fun `does not retry when retriesEnabled is false`() = runTest {
        val coordinator = CommandCoordinator(requestTimeoutMs = 1000, retryIntervalMs = 100)
        var sendCount = 0

        val result = coordinator.sendRoutedCommand(
            targetSerialNumber = "12345678",
            send = {
                sendCount++
                setOf(nodeKey())
            },
            isConfirmed = { false },
            retriesEnabled = false,
        )

        assertEquals(1, sendCount)
        assertEquals(CommandResult.FAILURE, result)
    }

    @Test
    fun `confirmCommandStatus completes a pending command for a matching serial number`() = runTest {
        val coordinator = CommandCoordinator(requestTimeoutMs = 1000, retryIntervalMs = 100)

        val resultDeferred = async {
            coordinator.sendRoutedCommand(
                targetSerialNumber = "12345678",
                send = { setOf(nodeKey()) },
                isConfirmed = { true },
            )
        }

        testScheduler.runCurrent()
        coordinator.confirmCommandStatus("12345678", status())

        assertEquals(CommandResult.SUCCESS, resultDeferred.await())
    }

    @Test
    fun `confirmCommandStatus completes a pending command even when send never registered any attempt key`() =
        runTest {
            // Regression test: a command must be confirmable purely from an observed status even
            // when nothing is currently reachable to send to at all (send always returns an empty
            // set, e.g. no connection exists) -- confirmCommandStatus must not depend on the
            // command having at least one registered attempt key to find it.
            val coordinator = CommandCoordinator(requestTimeoutMs = 1000, retryIntervalMs = 100)

            val resultDeferred = async {
                coordinator.sendRoutedCommand(
                    targetSerialNumber = "12345678",
                    send = { emptySet() },
                    isConfirmed = { true },
                )
            }

            testScheduler.runCurrent()
            coordinator.confirmCommandStatus("12345678", status())

            assertEquals(CommandResult.SUCCESS, resultDeferred.await())
        }

    @Test
    fun `confirmCommandStatus ignores a status for a different serial number`() = runTest {
        val coordinator = CommandCoordinator(requestTimeoutMs = 300, retryIntervalMs = 100)

        val resultDeferred = async {
            coordinator.sendRoutedCommand(
                targetSerialNumber = "12345678",
                send = { setOf(nodeKey()) },
                isConfirmed = { true },
            )
        }

        testScheduler.runCurrent()
        coordinator.confirmCommandStatus("differentSerial", status())

        // Not confirmed for this command -- runs out the clock to FAILURE rather than completing.
        assertEquals(CommandResult.FAILURE, resultDeferred.await())
    }

    @Test
    fun `completeAttempt returns false for an unregistered key`() = runTest {
        val coordinator = CommandCoordinator(requestTimeoutMs = 1000, retryIntervalMs = 100)

        assertFalse(coordinator.completeAttempt(nodeKey(requestId = 999u), success = true))
    }

    @Test
    fun `handleDeviceDisconnected purges direct keys so a stale response can no longer complete the command`() =
        runTest {
            val coordinator = CommandCoordinator(requestTimeoutMs = 10_000, retryIntervalMs = 1000)
            val key = directKey("device-1")

            val resultDeferred = async {
                coordinator.sendRoutedCommand(
                    targetSerialNumber = "12345678",
                    send = { setOf(key) },
                    isConfirmed = { false },
                )
            }

            testScheduler.runCurrent()
            coordinator.handleDeviceDisconnected("device-1")

            // The purged key can no longer complete the command.
            assertFalse(coordinator.completeAttempt(key, success = true))

            // But the command is still alive and can complete via a later retry's new key.
            advanceTimeBy(1001)
            testScheduler.runCurrent()
            assertTrue(coordinator.completeAttempt(directKey("device-1"), success = true))
            assertEquals(CommandResult.SUCCESS, resultDeferred.await())
        }

    private fun statusWithSequenceNumber(value: UInt): SpecializedDeviceStatus =
        mockk(relaxed = true) { every { maxSequenceNumber } returns value }

    @Test
    fun `valueConfirmation confirms when the observed value matches the commanded value`() {
        val isConfirmed = CommandCoordinator.valueConfirmation(
            startingValue = ExtractedValue.Present(1u),
            commandedValue = 2u,
            extractValue = { ExtractedValue.Present(it.maxSequenceNumber) },
        )

        assertTrue(isConfirmed(statusWithSequenceNumber(2u)))
    }

    @Test
    fun `valueConfirmation confirms when the observed value merely differs from a known starting value`() {
        // Neither the starting value (1) nor the commanded value (2) -- some other actor (e.g. a
        // competing command from another device) already changed it to 3. Confirmed anyway, so we
        // don't keep retrying and bouncing against that competing command indefinitely.
        val isConfirmed = CommandCoordinator.valueConfirmation(
            startingValue = ExtractedValue.Present(1u),
            commandedValue = 2u,
            extractValue = { ExtractedValue.Present(it.maxSequenceNumber) },
        )

        assertTrue(isConfirmed(statusWithSequenceNumber(3u)))
    }

    @Test
    fun `valueConfirmation does not confirm while the observed value still matches the starting value`() {
        val isConfirmed = CommandCoordinator.valueConfirmation(
            startingValue = ExtractedValue.Present(1u),
            commandedValue = 2u,
            extractValue = { ExtractedValue.Present(it.maxSequenceNumber) },
        )

        assertFalse(isConfirmed(statusWithSequenceNumber(1u)))
    }

    @Test
    fun `valueConfirmation does not confirm when the value can't be extracted from the status`() {
        val isConfirmed = CommandCoordinator.valueConfirmation<UInt>(
            startingValue = ExtractedValue.Present(1u),
            commandedValue = 2u,
            extractValue = { ExtractedValue.Absent },
        )

        assertFalse(isConfirmed(statusWithSequenceNumber(2u)))
    }

    // Regression coverage for the two bugs found in review: a starting value of Absent (never
    // observed) must not be treated the same as a confirmed value, whether that confirmed value is
    // present or -- separately -- present-but-null. See CommandCoordinator.valueConfirmation's KDoc.

    @Test
    fun `valueConfirmation does not confirm a merely-different observation when the starting value is unknown`() {
        // This is the original bug: with no confirmed starting value, the first status carrying
        // *any* value would previously satisfy "observed != startingValue" and spuriously confirm
        // a command that was never actually observed to land.
        val isConfirmed = CommandCoordinator.valueConfirmation(
            startingValue = ExtractedValue.Absent,
            commandedValue = 2u,
            extractValue = { ExtractedValue.Present(it.maxSequenceNumber) },
        )

        assertFalse(isConfirmed(statusWithSequenceNumber(3u)))
    }

    @Test
    fun `valueConfirmation confirms an exact match even when the starting value is unknown`() {
        val isConfirmed = CommandCoordinator.valueConfirmation(
            startingValue = ExtractedValue.Absent,
            commandedValue = 2u,
            extractValue = { ExtractedValue.Present(it.maxSequenceNumber) },
        )

        assertTrue(isConfirmed(statusWithSequenceNumber(2u)))
    }

    @Test
    fun `valueConfirmation treats a confirmed null starting value as known, confirming when observed differs from it`() {
        // A Present(null) starting value -- e.g. EngineStatus.controlSerialNumber confirmed absent
        // -- is a real observation, unlike Absent, so branch 2 ("changed at all") still applies
        // even though the known value happens to be null.
        val isConfirmed = CommandCoordinator.valueConfirmation(
            startingValue = ExtractedValue.Present(null),
            commandedValue = "B",
            extractValue = { ExtractedValue.Present("C") },
        )

        assertTrue(isConfirmed(status()))
    }

    @Test
    fun `valueConfirmation does not confirm a present-null observation against an unknown starting value unless it matches commandedValue`() {
        val isConfirmed = CommandCoordinator.valueConfirmation(
            startingValue = ExtractedValue.Absent,
            commandedValue = "B",
            extractValue = { ExtractedValue.Present(null) },
        )

        assertFalse(isConfirmed(status()))
    }

    @Test
    fun `valueConfirmation confirms a present-null observation that exactly matches a null commandedValue`() {
        val isConfirmed = CommandCoordinator.valueConfirmation(
            startingValue = ExtractedValue.Present("A"),
            commandedValue = null,
            extractValue = { ExtractedValue.Present(null) },
        )

        assertTrue(isConfirmed(status()))
    }

    @Test
    fun `extractedAs returns Present wrapping a null field when the status is the expected subtype`() {
        val status: SpecializedDeviceStatus = FakeStatusA(field = null)

        assertEquals(ExtractedValue.Present(null), status.extractedAs<FakeStatusA, String?> { it.field })
    }

    @Test
    fun `extractedAs returns Absent when the status is not the expected subtype`() {
        val status: SpecializedDeviceStatus = FakeStatusB()

        assertEquals(ExtractedValue.Absent, status.extractedAs<FakeStatusA, String?> { it.field })
    }

    @Test
    fun `toExtractedValue maps null to Absent and a real value to Present`() {
        val nullValue: String? = null

        assertEquals(ExtractedValue.Absent, nullValue.toExtractedValue())
        assertEquals(ExtractedValue.Present("x"), "x".toExtractedValue())
    }

    @Test
    fun `sendRoutedCommand completes via a competing command's value change, using valueConfirmation`() =
        runTest {
            val coordinator = CommandCoordinator(requestTimeoutMs = 10_000, retryIntervalMs = 1000)
            val isConfirmed = CommandCoordinator.valueConfirmation(
                startingValue = ExtractedValue.Present(1u),
                commandedValue = 2u,
                extractValue = { ExtractedValue.Present(it.maxSequenceNumber) },
            )

            val resultDeferred = async {
                coordinator.sendRoutedCommand(
                    targetSerialNumber = "12345678",
                    send = { setOf(nodeKey()) },
                    isConfirmed = isConfirmed,
                )
            }

            testScheduler.runCurrent()
            // A status arrives showing neither our starting nor commanded value -- some other
            // actor already moved it elsewhere. Should still complete as SUCCESS, not keep retrying.
            coordinator.confirmCommandStatus("12345678", statusWithSequenceNumber(99u))

            assertEquals(CommandResult.SUCCESS, resultDeferred.await())
        }

    private data class FakeStatusA(val field: String?) : SpecializedDeviceStatus {
        override val minSequenceNumber: UInt = 0u
        override val maxSequenceNumber: UInt = 0u
        override val mode: ProbeMode = ProbeMode.NORMAL
    }

    private data class FakeStatusB(val other: UInt = 0u) : SpecializedDeviceStatus {
        override val minSequenceNumber: UInt = 0u
        override val maxSequenceNumber: UInt = 0u
        override val mode: ProbeMode = ProbeMode.NORMAL
    }
}
