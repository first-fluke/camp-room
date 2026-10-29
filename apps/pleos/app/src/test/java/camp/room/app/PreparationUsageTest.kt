package camp.room.app

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparationUsageTest {
    @Test
    fun defaultsToOptOutAndDoesNotRecordActions() {
        val initial = PreparationUsage()
        assertFalse(initial.optedIn)
        PreparationUsageAction.entries.forEach { action ->
            assertSame(initial, initial.record(action))
        }
        assertEquals(PreparationUsage(false, 0, 0, 0), initial)
    }

    @Test
    fun firstOptInDoesNotCountEarlierActions() {
        var usage = PreparationUsage()
        repeat(3) {
            PreparationUsageAction.entries.forEach { usage = usage.record(it) }
        }
        usage = usage.withOptIn(true)
        assertEquals(PreparationUsage(optedIn = true), usage)
        assertEquals(PreparationUsage(true, 1, 0, 0), usage.record(PreparationUsageAction.CHECKED))
    }

    @Test
    fun settingSameConsentValuePreservesTheCurrentTotals() {
        val optedIn = PreparationUsage(true, 4, 2, 1)
        assertSame(optedIn, optedIn.withOptIn(true))
        val optedOut = PreparationUsage()
        assertSame(optedOut, optedOut.withOptIn(false))
    }

    @Test
    fun withdrawalClearsEveryCounterAndStopsRecording() {
        val withdrawn = PreparationUsage(true, 4, 2, 1).withOptIn(false)
        assertEquals(PreparationUsage(), withdrawn)
        PreparationUsageAction.entries.forEach { action ->
            assertSame(withdrawn, withdrawn.record(action))
        }
    }

    @Test
    fun reconsentStartsNewTotalsWithoutRestoringPreviousConsentPeriod() {
        var usage = PreparationUsage(true, 4, 2, 1).withOptIn(false)
        usage = usage.record(PreparationUsageAction.CHECKED)
        usage = usage.record(PreparationUsageAction.PHASE_CHANGED)
        usage = usage.withOptIn(true)
        assertEquals(PreparationUsage(optedIn = true), usage)
        assertEquals(PreparationUsage(true, 0, 0, 1), usage.record(PreparationUsageAction.TEMPLATE_IMPORTED))
    }

    @Test
    fun actionsIncrementOnlyTheirOwnCounter() {
        val usage = PreparationUsage(true, 4, 2, 1)
        assertEquals(PreparationUsage(true, 5, 2, 1), usage.record(PreparationUsageAction.CHECKED))
        assertEquals(PreparationUsage(true, 4, 3, 1), usage.record(PreparationUsageAction.PHASE_CHANGED))
        assertEquals(PreparationUsage(true, 4, 2, 2), usage.record(PreparationUsageAction.TEMPLATE_IMPORTED))
        assertEquals(PreparationUsage(true, 4, 2, 1), usage)
    }

    @Test
    fun countersReachTheirLimitWithoutOverflowOrChangingOtherCounters() {
        val nearLimit = PreparationUsage(true, Int.MAX_VALUE - 1, Int.MAX_VALUE - 1, Int.MAX_VALUE - 1)
        PreparationUsageAction.entries.forEach { action ->
            val atLimit = nearLimit.record(action)
            val repeated = atLimit.record(action)
            assertEquals(atLimit, repeated)
            val expected =
                when (action) {
                    PreparationUsageAction.CHECKED -> nearLimit.copy(checkedItems = Int.MAX_VALUE)
                    PreparationUsageAction.PHASE_CHANGED -> nearLimit.copy(phaseChanges = Int.MAX_VALUE)
                    PreparationUsageAction.TEMPLATE_IMPORTED -> nearLimit.copy(templateImports = Int.MAX_VALUE)
                }
            assertEquals(expected, atLimit)
            assertTrue(repeated.checkedItems >= 0 && repeated.phaseChanges >= 0 && repeated.templateImports >= 0)
        }
    }

    @Test
    fun jsonRoundTripPreservesOnlyConsentAndAggregateCounters() {
        val usage = PreparationUsage(true, 4, 2, 1)
        val encoded = Json.encodeToString(usage)
        assertEquals(usage, Json.decodeFromString<PreparationUsage>(encoded))
        assertEquals(PreparationUsage(), Json.decodeFromString<PreparationUsage>("{}"))
        assertEquals(
            setOf("optedIn", "checkedItems", "phaseChanges", "templateImports"),
            Json.parseToJsonElement(encoded).let { it as kotlinx.serialization.json.JsonObject }.keys,
        )
    }
}
