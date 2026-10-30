package camp.room.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleAreaObservationsTest {
    private class Clock(
        var now: Long = 1_000,
    )

    private val left = VehicleAreaKey(VehicleAreaKind.DOOR, "ROW_1_LEFT")
    private val right = VehicleAreaKey(VehicleAreaKind.DOOR, "ROW_1_RIGHT")
    private val window = VehicleAreaKey(VehicleAreaKind.WINDOW, "ROW_1_LEFT")

    private fun config(
        key: VehicleAreaKey = left,
        members: Set<String> = setOf(key.nativeAreaId),
        min: Int? = 0,
        max: Int? = 100,
        readable: Boolean = true,
    ) = VehicleAreaConfig(key, members, min, max, "GET", readable)

    private fun tracker(
        clock: Clock = Clock(),
        configs: List<VehicleAreaConfig> = listOf(config()),
    ) = VehicleAreaObservations({ clock.now }, maxAgeMillis = 1_000).also {
        assertTrue(it.configure(configs, 0))
    }

    private fun read(
        tracker: VehicleAreaObservations,
        key: VehicleAreaKey = left,
        epoch: Int = 0,
    ) = requireNotNull(tracker.beginRead(key, epoch))

    private fun sample(
        tracker: VehicleAreaObservations,
        key: VehicleAreaKey = left,
        epoch: Int = 0,
    ) = tracker.snapshot(epoch).single { it.key == key }

    @Test
    fun areasAndKindsKeepIndependentValuesAndReadTokens() {
        val t = tracker(configs = listOf(config(left), config(right), config(window)))
        val leftRead = read(t, left)
        val rightRead = read(t, right)
        val windowRead = read(t, window)
        assertTrue(t.onEvent(left, 0, 70))
        assertFalse(t.completeRead(leftRead, 0))
        assertTrue(t.completeRead(rightRead, 20))
        assertTrue(t.completeRead(windowRead, 35))

        assertEquals(70, sample(t, left).rawPosition)
        assertEquals(20, sample(t, right).rawPosition)
        assertEquals(35, sample(t, window).rawPosition)
    }

    @Test
    fun compositeAreaIdentityAndEveryNativeMemberArePreserved() {
        val composite = VehicleAreaKey(VehicleAreaKind.WINDOW, "ROW_1_ALL")
        val members = linkedSetOf("ROW_1_LEFT", "ROW_1_RIGHT")
        val t = tracker(configs = listOf(config(composite, members)))
        members.clear()
        assertTrue(t.completeRead(read(t, composite), 18))

        val observed = sample(t, composite)
        assertEquals(composite, observed.key)
        assertEquals(setOf("ROW_1_LEFT", "ROW_1_RIGHT"), observed.members)
        assertEquals(VehicleAreaKind.WINDOW, observed.kind)
        assertEquals("ROW_1_ALL", observed.nativeAreaId)
        assertEquals(1, t.snapshot(0).size)
    }

    @Test
    fun aValidMinimumNeverImpliesClosedForDoorOrWindow() {
        val t = tracker(configs = listOf(config(left, min = 5), config(window, min = 5)))
        for (key in listOf(left, window)) {
            assertTrue(t.completeRead(read(t, key), 5))
            assertEquals(VehicleAreaStatus.OBSERVED, sample(t, key).status)
            assertNull(sample(t, key).closed)
        }
        assertNull(t.allClosed(VehicleAreaKind.DOOR, 0))
        assertNull(t.allClosed(VehicleAreaKind.WINDOW, 0))
    }

    @Test
    fun nullAndPartialSamplesStayUnknownRatherThanAllClosed() {
        val t = tracker(configs = listOf(config(left), config(right)))
        assertTrue(t.completeRead(read(t, left), 0))
        assertEquals(VehicleAreaStatus.UNKNOWN, sample(t, right).status)
        assertNull(t.allClosed(VehicleAreaKind.DOOR, 0))

        assertTrue(t.completeRead(read(t, right), null))
        assertEquals(VehicleAreaStatus.EMPTY, sample(t, right).status)
        assertEquals("null_position", sample(t, right).reason)
        assertNull(sample(t, right).rawPosition)
        assertNull(t.allClosed(VehicleAreaKind.DOOR, 0))
    }

    @Test
    fun emptyCapabilitiesNeverMeanAllClosed() {
        val t = tracker(configs = emptyList())
        assertTrue(t.snapshot(0).isEmpty())
        assertNull(t.allClosed(VehicleAreaKind.DOOR, 0))
        assertNull(t.allClosed(VehicleAreaKind.WINDOW, 0))
        assertNull(t.beginRead(left, 0))
        assertFalse(t.onEvent(left, 0, 0))
    }

    @Test
    fun missingReversedAndCollapsedBoundsRetainRawEvidenceWithoutInterpretation() {
        for ((min, max) in listOf(null to 100, 0 to null, 10 to 5, 5 to 5)) {
            val t = tracker(configs = listOf(config(min = min, max = max)))
            assertTrue(t.completeRead(read(t), 5))
            val observed = sample(t)
            assertEquals(VehicleAreaStatus.INVALID_BOUNDS, observed.status)
            assertEquals(5, observed.rawPosition)
            assertEquals(min, observed.minPosition)
            assertEquals(max, observed.maxPosition)
            assertNull(observed.closed)
            assertNull(t.allClosed(VehicleAreaKind.DOOR, 0))
        }
    }

    @Test
    fun outOfRangePositionsAreNotClampedIntoAClosedEndpoint() {
        for (position in listOf(Int.MIN_VALUE, -1, 101, Int.MAX_VALUE)) {
            val t = tracker()
            assertTrue(t.completeRead(read(t), position))
            assertEquals(position, sample(t).rawPosition)
            assertEquals(VehicleAreaStatus.OUT_OF_RANGE, sample(t).status)
            assertNull(sample(t).closed)
        }
    }

    @Test
    fun newerEventWinsOverLateGetterEvenWhenClockTimesAreEqual() {
        val t = tracker()
        val getter = read(t)
        assertTrue(t.onEvent(left, 0, 60))
        assertFalse(t.completeRead(getter, 0))
        assertFalse(t.fail(getter, "late_error"))
        assertEquals(60, sample(t).rawPosition)
        assertEquals(VehicleAreaStatus.OBSERVED, sample(t).status)
    }

    @Test
    fun aNewerNullEventAlsoInvalidatesAnOlderGetter() {
        val t = tracker()
        val getter = read(t)
        assertTrue(t.onEvent(left, 0, null))
        assertFalse(t.completeRead(getter, 0))
        assertEquals(VehicleAreaStatus.EMPTY, sample(t).status)
        assertNull(sample(t).rawPosition)
    }

    @Test
    fun anOutOfOrderEventCannotReplaceANewerSampleOrCancelItsNewRead() {
        val clock = Clock()
        val t = tracker(clock)
        assertTrue(t.onEvent(left, 0, 45, 1_000))
        clock.now = 1_100
        val next = read(t)
        assertFalse(t.onEvent(left, 0, 0, 999))
        assertTrue(t.completeRead(next, 65, 1_100))
        assertEquals(65, sample(t).rawPosition)
    }

    @Test
    fun staleEpochCallbacksAndReadsAreDroppedAfterReconnect() {
        val t = tracker()
        val old = read(t)
        t.reset(1)
        assertTrue(t.configure(listOf(config()), 1))
        val fresh = read(t, epoch = 1)
        assertFalse(t.completeRead(old, 0))
        assertFalse(t.onEvent(left, 0, 0))
        assertFalse(t.fail(old, "old_connection"))
        assertFalse(t.configure(listOf(config(right)), 0))
        assertTrue(t.snapshot(0).isEmpty())
        assertNull(t.beginRead(left, 0))
        assertTrue(t.completeRead(fresh, 33))
        assertEquals(33, sample(t, epoch = 1).rawPosition)
    }

    @Test
    fun resetWithTheSameEpochStillInvalidatesOldGetterTokens() {
        val t = tracker()
        val old = read(t)
        t.reset(0)
        assertTrue(t.configure(listOf(config()), 0))
        val fresh = read(t)
        assertNotEquals(old.id, fresh.id)
        assertFalse(t.completeRead(old, 0))
        assertTrue(t.completeRead(fresh, 18))
        assertEquals(18, sample(t).rawPosition)
    }

    @Test
    fun reconfigurationDropsOldCapabilitiesSamplesAndInFlightTokens() {
        val t = tracker()
        val old = read(t)
        assertTrue(t.configure(listOf(config(right)), 0))
        assertFalse(t.completeRead(old, 0))
        assertFalse(t.onEvent(left, 0, 0))
        assertEquals(listOf(right), t.snapshot(0).map { it.key })
        assertNull(sample(t, right).sampledAtMillis)
    }

    @Test
    fun stopResetClearsAllSamplesWithoutPromotingAbsenceToClosed() {
        val t = tracker()
        assertTrue(t.onEvent(left, 0, 0))
        t.reset(1)
        assertTrue(t.snapshot(1).isEmpty())
        assertFalse(t.onEvent(left, 0, 0))
        assertFalse(t.onEvent(left, 1, 0))
        assertNull(t.allClosed(VehicleAreaKind.DOOR, 1))
    }

    @Test
    fun freshnessUsesSampleTimeAndKeepsRawValuesForDiagnosisOnly() {
        val clock = Clock()
        val t = tracker(clock)
        assertTrue(t.completeRead(read(t), 27))
        clock.now = 2_000
        assertEquals(VehicleAreaStatus.OBSERVED, sample(t).status)
        clock.now = 2_001
        val old = sample(t)
        assertEquals(VehicleAreaStatus.STALE, old.status)
        assertEquals("stale_sample", old.reason)
        assertEquals(27, old.rawPosition)
        assertEquals(1_000L, old.sampledAtMillis)
        assertEquals(1_000L, old.updatedAtMillis)
        assertNull(old.closed)
        assertNull(t.allClosed(VehicleAreaKind.DOOR, 0))
    }

    @Test
    fun anAlreadyOldResultIsStaleAtCompletionAndANewEventCanRecoverIt() {
        val clock = Clock(5_000)
        val t = tracker(clock)
        assertTrue(t.completeRead(read(t), 0, 1_000))
        assertEquals(VehicleAreaStatus.STALE, sample(t).status)
        assertTrue(t.onEvent(left, 0, 50, 5_000))
        assertEquals(VehicleAreaStatus.OBSERVED, sample(t).status)
        assertEquals(50, sample(t).rawPosition)
    }

    @Test
    fun failedReadDoesNotRefreshThePreviousSampleTimestamp() {
        val clock = Clock()
        val t = tracker(clock)
        assertTrue(t.onEvent(left, 0, 12))
        clock.now = 1_500
        assertTrue(t.fail(read(t), "permission_denied"))
        val failed = sample(t)
        assertEquals(VehicleAreaStatus.ERROR, failed.status)
        assertEquals("permission_denied", failed.reason)
        assertEquals(12, failed.rawPosition)
        assertEquals(1_000L, failed.sampledAtMillis)
        assertEquals(1_500L, failed.updatedAtMillis)
        assertNull(failed.closed)
    }

    @Test
    fun futureTimestampIsAnErrorAndCannotPoisonOrderingOfLaterRealSamples() {
        val t = tracker()
        assertTrue(t.onEvent(left, 0, 0, 2_000))
        assertEquals(VehicleAreaStatus.ERROR, sample(t).status)
        assertEquals("future_sample_time", sample(t).reason)
        assertTrue(t.onEvent(left, 0, 42, 1_000))
        assertEquals(VehicleAreaStatus.OBSERVED, sample(t).status)
        assertEquals(42, sample(t).rawPosition)
    }

    @Test
    fun subscriptionFailurePreservesEvidenceAndInvalidatesOnlyItsAreasGetter() {
        val clock = Clock()
        val t = tracker(clock, configs = listOf(config(left), config(right)))
        assertTrue(t.onEvent(left, 0, 12))
        val leftRead = read(t, left)
        val rightRead = read(t, right)
        clock.now = 1_500
        assertTrue(t.failArea(left, 0, "subscription_unavailable"))
        assertFalse(t.completeRead(leftRead, 0))
        assertEquals(12, sample(t).rawPosition)
        assertEquals(1_000L, sample(t).sampledAtMillis)
        assertEquals(VehicleAreaStatus.ERROR, sample(t).status)
        assertEquals("subscription_unavailable", sample(t).reason)
        assertTrue(t.completeRead(rightRead, 22))
        assertEquals(VehicleAreaStatus.OBSERVED, sample(t, right).status)

        assertTrue(t.onEvent(left, 0, 44))
        assertEquals(VehicleAreaStatus.OBSERVED, sample(t).status)
        assertEquals(44, sample(t).rawPosition)
    }

    @Test
    fun staleSubscriptionFailureCannotMarkTheNewConnectionUnavailable() {
        val t = tracker()
        t.reset(1)
        assertTrue(t.configure(listOf(config()), 1))
        assertTrue(t.onEvent(left, 1, 40))
        assertFalse(t.failArea(left, 0, "old_subscription_error"))
        assertFalse(t.failArea(right, 1, "unknown_area"))
        assertEquals(VehicleAreaStatus.OBSERVED, sample(t, epoch = 1).status)
        assertEquals(40, sample(t, epoch = 1).rawPosition)
    }

    @Test
    fun unreadableOrMissingAreaMetadataCannotProduceAUsableRead() {
        for (cfg in listOf(
            config(readable = false),
            config(members = emptySet()),
            config(members = setOf(" ")),
            config(key = left.copy(nativeAreaId = "")),
        )) {
            val t = tracker(configs = listOf(cfg))
            assertEquals(VehicleAreaStatus.UNAVAILABLE, sample(t, cfg.key).status)
            assertNull(t.beginRead(cfg.key, 0))
            assertNull(t.allClosed(VehicleAreaKind.DOOR, 0))
        }
    }

    @Test
    fun duplicateCapabilityKeysCannotSilentlyOverwriteOrPreserveOldSamples() {
        val t = tracker()
        assertTrue(t.onEvent(left, 0, 0))
        assertFalse(t.configure(listOf(config(left), config(left, members = setOf("OTHER"))), 0))
        assertTrue(t.snapshot(0).isEmpty())
        assertNull(t.allClosed(VehicleAreaKind.DOOR, 0))
    }

    @Test
    fun onlyOneGetterPerAreaIsAdmittedAndCompletedTokensCannotBeReused() {
        val t = tracker()
        val first = read(t)
        assertNull(t.beginRead(left, 0))
        assertTrue(t.completeRead(first, 1))
        assertFalse(t.completeRead(first, 99))
        val second = read(t)
        assertNotNull(second)
        assertFalse(t.fail(first, "old_failure"))
        assertTrue(t.completeRead(second, 2))
        assertEquals(2, sample(t).rawPosition)
    }

    @Test
    fun cancellationReleasesTheAreaForRetryWithoutChangingItsObservation() {
        val clock = Clock()
        val t = tracker(clock)
        assertTrue(t.onEvent(left, 0, 34))
        val before = sample(t)
        val pending = read(t)
        clock.now = 1_500

        assertTrue(t.cancelRead(pending))
        assertEquals(before, sample(t))
        val retry = read(t)
        assertNotEquals(pending.id, retry.id)
        assertTrue(t.completeRead(retry, 55))
        assertEquals(55, sample(t).rawPosition)
    }

    @Test
    fun delayedCancellationCannotClearTheNextReadOfTheSameArea() {
        val t = tracker()
        val old = read(t)
        assertTrue(t.cancelRead(old))
        val current = read(t)

        assertFalse(t.cancelRead(old))
        assertNull(t.beginRead(left, 0))
        assertTrue(t.completeRead(current, 72))
        assertEquals(72, sample(t).rawPosition)
    }

    @Test
    fun cancellationFollowingAnEventPreservesTheEventAndItsSubsequentRead() {
        val t = tracker()
        val old = read(t)
        assertTrue(t.onEvent(left, 0, 65))
        val observed = sample(t)
        val current = read(t)

        assertFalse(t.cancelRead(old))
        assertEquals(observed, sample(t))
        assertNull(t.beginRead(left, 0))
        assertTrue(t.completeRead(current, 66))
        assertEquals(66, sample(t).rawPosition)
    }

    @Test
    fun cancellationFromAnOldConnectionCannotClearTheNewConnectionsRead() {
        val t = tracker()
        val old = read(t)
        t.reset(1)
        assertTrue(t.configure(listOf(config()), 1))
        val current = read(t, epoch = 1)

        assertFalse(t.cancelRead(old))
        assertNull(t.beginRead(left, 1))
        assertTrue(t.completeRead(current, 40))
        assertEquals(40, sample(t, epoch = 1).rawPosition)
    }

    @Test
    fun sameEpochResetAlsoRejectsCancellationOfThePreviousGeneration() {
        val t = tracker()
        val old = read(t)
        t.reset(0)
        assertTrue(t.configure(listOf(config()), 0))
        val current = read(t)

        assertFalse(t.cancelRead(old))
        assertNull(t.beginRead(left, 0))
        assertTrue(t.completeRead(current, 28))
        assertEquals(28, sample(t).rawPosition)
    }
}
