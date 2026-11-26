package inc.combustion.framework.log

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.ConcurrentSkipListMap

class ValuesInRangesTest {
    /** Sequence numbers 0..20 without 5..9, i.e. with a hole. */
    private val log = ConcurrentSkipListMap<UInt, String>().apply {
        ((0u..4u) + (10u..20u)).forEach { put(it, "v$it") }
    }

    private fun values(vararg sequenceNumbers: UInt) = sequenceNumbers.map { "v$it" }

    @Test
    fun `returns the values in a range`() {
        assertEquals(values(2u, 3u, 4u), log.valuesInRanges(listOf(2u..4u)))
    }

    @Test
    fun `returns the values in several ranges in order`() {
        assertEquals(values(0u, 1u, 19u, 20u), log.valuesInRanges(listOf(0u..1u, 19u..20u)))
    }

    @Test
    fun `skips sequence numbers without values`() {
        assertEquals(values(3u, 4u, 10u, 11u), log.valuesInRanges(listOf(3u..11u)))
        assertEquals(emptyList<String>(), log.valuesInRanges(listOf(5u..9u)))
        assertEquals(emptyList<String>(), log.valuesInRanges(listOf(21u..100u)))
    }

    @Test
    fun `ignores empty ranges`() {
        assertEquals(emptyList<String>(), log.valuesInRanges(emptyList()))
        assertEquals(values(1u), log.valuesInRanges(listOf(4u..2u, 1u..1u)))
    }

    @Test
    fun `handles the ends of the UInt range`() {
        log[UInt.MAX_VALUE] = "max"

        assertEquals(values(20u) + "max", log.valuesInRanges(listOf(20u..UInt.MAX_VALUE)))
        assertEquals(values(0u, 1u), log.valuesInRanges(listOf(UInt.MIN_VALUE..1u)))
    }
}
