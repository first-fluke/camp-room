package camp.room.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThat
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PositionSubscriptionLedgerTest {
    private data class Binding(
        val nativeArea: String,
        val listener: Any,
    )

    @Test fun failedUnregisterRetainsExactAreaEpochBindingAndBlocksReplacementUntilRetrySucceeds() {
        var now = 0L
        val ledger = PositionSubscriptionLedger<String, Binding> { now }
        val old = Binding("native-door-area", Any())
        assertTrue(ledger.reserve("door:left", 1, old, old.listener))
        assertTrue(ledger.beginRegistration("door:left", 1, old.listener))
        assertEquals(1, ledger.detachAll())
        val attempts = mutableListOf<Triple<String, Int, Binding>>()
        assertFalse(
            ledger.drain({ key, epoch, binding ->
                attempts += Triple(key, epoch, binding)
                throw IllegalStateException("temporary unregister failure")
            }, { _, _, _ -> }),
        )
        val replacement = Binding("native-door-area", Any())
        assertFalse(ledger.reserve("door:left", 2, replacement, replacement.listener))
        assertFalse(ledger.drain({ _, _, _ -> fail("backoff must suppress IPC") }, { _, _, _ -> }))
        now = 2000
        assertTrue(ledger.drain({ key, epoch, binding -> attempts += Triple(key, epoch, binding) }, { _, _, _ -> }))
        assertEquals(2, attempts.size)
        attempts.forEach {
            assertEquals("door:left", it.first)
            assertEquals(1, it.second)
            assertSame(old, it.third)
        }
        assertTrue(ledger.reserve("door:left", 2, replacement, replacement.listener))
        assertTrue(ledger.isCurrent("door:left", 2, replacement.listener))
    }

    @Test fun detachRejectsOldCallbacksImmediatelyAndOldFailureCannotRemoveNewGeneration() {
        val ledger = PositionSubscriptionLedger<String, Binding>()
        val old = Binding("window-native", Any())
        assertTrue(ledger.reserve("window:left", 3, old, old.listener))
        assertTrue(ledger.beginRegistration("window:left", 3, old.listener))
        assertTrue(ledger.isCurrent("window:left", 3, old.listener))
        ledger.detachAll()
        assertFalse(ledger.isCurrent("window:left", 3, old.listener))
        assertTrue(ledger.drain({ _, _, binding -> assertSame(old, binding) }, { _, _, _ -> }))
        val next = Binding("window-native", Any())
        assertTrue(ledger.reserve("window:left", 4, next, next.listener))
        assertFalse(ledger.registrationFailed("window:left", old.listener))
        assertTrue(ledger.hasActive("window:left", 4))
        assertTrue(ledger.isCurrent("window:left", 4, next.listener))
        assertFalse(ledger.isCurrent("window:left", 3, old.listener))
    }

    @Test fun uncertainRegisterThrowRetainsNativeBindingAndRejectsFurtherCallbacks() {
        val ledger = PositionSubscriptionLedger<String, Binding>()
        val binding = Binding("combined-native-area", Any())
        assertTrue(ledger.reserve("window:combined", 6, binding, binding.listener))
        assertTrue(ledger.registrationFailed("window:combined", binding.listener))
        assertFalse(ledger.registrationFailed("window:combined", binding.listener))
        assertFalse(ledger.isCurrent("window:combined", 6, binding.listener))
        assertTrue(ledger.hasPending())
        assertFalse(ledger.reserve("window:combined", 7, Binding("other", Any()), Any()))
        var calls = 0
        assertTrue(
            ledger.drain({ key, epoch, native ->
                calls++
                assertEquals("window:combined", key)
                assertEquals(6, epoch)
                assertSame(binding, native)
            }, { _, _, _ -> }),
        )
        assertEquals(1, calls)
    }

    @Test fun cleanupOfOldAreaCannotClearAnotherActiveAreaOrEqualReplacementIdentity() {
        val ledger = PositionSubscriptionLedger<String, Binding>()

        data class Listener(
            val value: Int,
        )
        val oldListener = Listener(1)
        val newListener = Listener(1)
        val old = Binding("door", oldListener)
        assertTrue(ledger.reserve("door:left", 1, old, oldListener))
        assertFalse(ledger.isCurrent("door:left", 1, newListener))
        assertFalse(ledger.registrationFailed("door:left", newListener))
        assertTrue(ledger.registrationFailed("door:left", oldListener))
        val other = Binding("window", Any())
        assertTrue(ledger.reserve("window:right", 2, other, other.listener))
        assertTrue(ledger.drain({ _, _, _ -> }, { _, _, _ -> }))
        assertTrue(ledger.isCurrent("window:right", 2, other.listener))
        assertEquals(setOf("window:right"), ledger.activeKeys(2))
    }

    @Test fun fiveFailuresRemainRetainedAndDoNotSpinOrAllowDuplicateRegistration() {
        var now = 0L
        val ledger = PositionSubscriptionLedger<String, Binding> { now }
        val old = Binding("door", Any())
        ledger.reserve("door:left", 1, old, old.listener)
        ledger.beginRegistration("door:left", 1, old.listener)
        ledger.detachAll()
        var calls = 0
        for (delay in listOf(2000L, 4000L, 8000L, 15000L, 15000L)) {
            assertFalse(
                ledger.drain({ _, _, native ->
                    assertSame(old, native)
                    calls++
                    throw IllegalStateException("test failure")
                }, { _, _, _ -> }),
            )
            now += delay
        }
        now += 100000
        repeat(10) { assertFalse(ledger.drain({ _, _, _ -> calls++ }, { _, _, _ -> })) }
        assertEquals(5, calls)
        assertTrue(ledger.hasPending())
        assertNull(ledger.nextRetryAfterMillis())
        val next = Binding("door", Any())
        assertFalse(ledger.reserve("door:left", 2, next, next.listener))
    }

    @Test fun topologyReconcilePreservesCurrentExpectedBindingsAndRetiresRemovedOrWrongEpoch() {
        val ledger = PositionSubscriptionLedger<String, Binding>()
        val current = Binding("door-left-native", Any())
        val removed = Binding("door-right-native", Any())
        val stale = Binding("window-left-native", Any())
        ledger.reserve("door:left", 7, current, current.listener)
        ledger.reserve("door:right", 7, removed, removed.listener)
        ledger.reserve("window:left", 6, stale, stale.listener)
        ledger.beginRegistration("door:left", 7, current.listener)
        ledger.beginRegistration("door:right", 7, removed.listener)
        ledger.beginRegistration("window:left", 6, stale.listener)
        assertEquals(2, ledger.detachExcept(setOf("door:left", "window:left"), 7))
        assertEquals(setOf("door:left"), ledger.activeKeys(7))
        assertTrue(ledger.isCurrent("door:left", 7, current.listener))
        assertFalse(ledger.isCurrent("door:right", 7, removed.listener))
        assertFalse(ledger.isCurrent("window:left", 6, stale.listener))
        assertEquals(2, ledger.pendingCount())
        val cleaned = mutableListOf<Binding>()
        assertTrue(ledger.drain({ _, _, binding -> cleaned += binding }, { _, _, _ -> }))
        assertEquals(2, cleaned.size)
        assertTrue(cleaned.any { it === removed })
        assertTrue(cleaned.any { it === stale })
        assertFalse(cleaned.any { it === current })
        assertTrue(ledger.isCurrent("door:left", 7, current.listener))
    }

    @Test fun retryDelayReflectsBackoffAndDisappearsAfterSuccessfulCleanup() {
        var now = 10000L
        val ledger = PositionSubscriptionLedger<String, Binding> { now }
        assertNull(ledger.nextRetryAfterMillis())
        val binding = Binding("door", Any())
        ledger.reserve("door:left", 1, binding, binding.listener)
        ledger.beginRegistration("door:left", 1, binding.listener)
        ledger.detachAll()
        assertEquals(1, ledger.pendingCount())
        assertEquals(0L, ledger.nextRetryAfterMillis() ?: -1L)
        ledger.drain({ _, _, _ -> throw IllegalStateException("test failure") }, { _, _, _ -> })
        assertEquals(2000L, ledger.nextRetryAfterMillis() ?: -1L)
        now += 1999
        assertEquals(1L, ledger.nextRetryAfterMillis() ?: -1L)
        now++
        assertEquals(0L, ledger.nextRetryAfterMillis() ?: -1L)
        assertTrue(ledger.drain({ _, _, _ -> }, { _, _, _ -> }))
        assertEquals(0, ledger.pendingCount())
        assertNull(ledger.nextRetryAfterMillis())
    }

    @Test fun discardBeforeNativeCallCannotErasePendingUncertainBindingOrNewIdentity() {
        val ledger = PositionSubscriptionLedger<String, Binding>()
        val neverCalled = Binding("door", Any())
        ledger.reserve("door:left", 1, neverCalled, neverCalled.listener)
        assertTrue(ledger.discardUnregistered("door:left", neverCalled.listener))
        assertFalse(ledger.hasPending())
        val uncertain = Binding("door", Any())
        ledger.reserve("door:left", 2, uncertain, uncertain.listener)
        ledger.registrationFailed("door:left", uncertain.listener)
        assertFalse(ledger.discardUnregistered("door:left", uncertain.listener))
        assertEquals(1, ledger.pendingCount())
        ledger.drain({ _, _, _ -> }, { _, _, _ -> })
        val current = Binding("door", Any())
        ledger.reserve("door:left", 3, current, current.listener)
        assertFalse(ledger.discardUnregistered("door:left", neverCalled.listener))
        assertFalse(ledger.discardUnregistered("door:left", uncertain.listener))
        assertTrue(ledger.isCurrent("door:left", 3, current.listener))
    }

    @Test fun processSharedClaimBlocksNewBridgeBeforeOldBridgeDetachButAllowsAnotherArea() {
        val quarantine = PositionSubscriptionQuarantine<String>()
        val old = PositionSubscriptionLedger<String, Binding>(quarantine = quarantine)
        val next = PositionSubscriptionLedger<String, Binding>(quarantine = quarantine)
        val oldBinding = Binding("native-door", Any())
        assertTrue(old.reserve("door:left", 1, oldBinding, oldBinding.listener))
        val replacement = Binding("native-door", Any())
        assertFalse(next.reserve("door:left", 1, replacement, replacement.listener))
        assertTrue(old.isCurrent("door:left", 1, oldBinding.listener))
        val independent = Binding("native-window", Any())
        assertTrue(next.reserve("window:right", 1, independent, independent.listener))
    }

    @Test fun processSharedClaimSurvivesDetachFailureAndReleasesOnlyAfterExactCleanupSuccess() {
        var now = 0L
        val quarantine = PositionSubscriptionQuarantine<String>()
        val old = PositionSubscriptionLedger<String, Binding>({ now }, quarantine)
        val next = PositionSubscriptionLedger<String, Binding>({ now }, quarantine)
        val oldBinding = Binding("native-door", Any())
        old.reserve("door:left", 1, oldBinding, oldBinding.listener)
        old.beginRegistration("door:left", 1, oldBinding.listener)
        old.detachAll()
        old.drain({ _, _, binding ->
            assertSame(oldBinding, binding)
            throw IllegalStateException("native service temporarily unavailable")
        }, { _, _, _ -> })
        val replacement = Binding("native-door", Any())
        assertFalse(next.reserve("door:left", 1, replacement, replacement.listener))
        now = 2000
        assertTrue(old.drain({ _, _, binding -> assertSame(oldBinding, binding) }, { _, _, _ -> }))
        assertTrue(next.reserve("door:left", 1, replacement, replacement.listener))
        assertFalse(old.registrationFailed("door:left", oldBinding.listener))
        assertFalse(old.discardUnregistered("door:left", oldBinding.listener))
        assertTrue(next.isCurrent("door:left", 1, replacement.listener))
    }

    @Test fun oldQuarantineTokenCannotReleaseNewOwnerWithSameKey() {
        val quarantine = PositionSubscriptionQuarantine<String>()
        val old = Any()
        val next = Any()
        assertTrue(quarantine.claim("door:left", old))
        assertFalse(quarantine.claim("door:left", old))
        assertTrue(quarantine.release("door:left", old))
        assertTrue(quarantine.claim("door:left", next))
        assertFalse(quarantine.release("door:left", old))
        assertFalse(quarantine.claim("door:left", Any()))
        assertTrue(quarantine.release("door:left", next))
    }

    @Test fun exhaustedOldBridgeCleanupKeepsSharedAreaQuarantined() {
        var now = 0L
        val quarantine = PositionSubscriptionQuarantine<String>()
        val old = PositionSubscriptionLedger<String, Binding>({ now }, quarantine)
        val next = PositionSubscriptionLedger<String, Binding>({ now }, quarantine)
        val oldBinding = Binding("native-window", Any())
        old.reserve("window:left", 3, oldBinding, oldBinding.listener)
        old.beginRegistration("window:left", 3, oldBinding.listener)
        old.detachAll()
        repeat(5) {
            old.drain({ _, _, binding ->
                assertSame(oldBinding, binding)
                throw IllegalStateException("test failure")
            }, { _, _, _ -> })
            now += 20000
        }
        assertNull(old.nextRetryAfterMillis())
        assertEquals(1, old.pendingCount())
        val replacement = Binding("native-window", Any())
        assertFalse(next.reserve("window:left", 4, replacement, replacement.listener))
    }

    @Test fun sharedClaimIsReleasedForKnownUncalledReservationButNotUncertainNativeRegister() {
        val quarantine = PositionSubscriptionQuarantine<String>()
        val old = PositionSubscriptionLedger<String, Binding>(quarantine = quarantine)
        val next = PositionSubscriptionLedger<String, Binding>(quarantine = quarantine)
        val neverCalled = Binding("native-window", Any())
        old.reserve("window:left", 1, neverCalled, neverCalled.listener)
        assertTrue(old.discardUnregistered("window:left", neverCalled.listener))
        val uncertain = Binding("native-window", Any())
        assertTrue(next.reserve("window:left", 1, uncertain, uncertain.listener))
        assertTrue(next.registrationFailed("window:left", uncertain.listener))
        assertFalse(next.discardUnregistered("window:left", uncertain.listener))
        val newer = Binding("native-window", Any())
        assertFalse(old.reserve("window:left", 2, newer, newer.listener))
    }

    @Test fun stopBetweenReservationAndNativeInvocationReleasesNeverInvokedSharedClaim() {
        val quarantine = PositionSubscriptionQuarantine<String>()
        val old = PositionSubscriptionLedger<String, Binding>(quarantine = quarantine)
        val next = PositionSubscriptionLedger<String, Binding>(quarantine = quarantine)
        val cancelled = Binding("native-door", Any())
        assertTrue(old.reserve("door:left", 7, cancelled, cancelled.listener))
        assertEquals(1, old.detachAll())
        var nativeCalls = 0
        if (old.beginRegistration("door:left", 7, cancelled.listener)) nativeCalls++
        assertEquals(0, nativeCalls)
        assertTrue(old.discardUnregistered("door:left", cancelled.listener))
        assertFalse(old.hasPending())
        assertTrue(old.drain({ _, _, _ -> fail("never-invoked listener needs no unregister") }, { _, _, _ -> }))
        val replacement = Binding("native-door", Any())
        assertTrue(next.reserve("door:left", 1, replacement, replacement.listener))
    }

    @Test fun invokedOrUncertainPendingBindingCannotBeDiscardedAsNeverInvoked() {
        val quarantine = PositionSubscriptionQuarantine<String>()
        val old = PositionSubscriptionLedger<String, Binding>(quarantine = quarantine)
        val next = PositionSubscriptionLedger<String, Binding>(quarantine = quarantine)
        val invoked = Binding("native-window", Any())
        assertTrue(old.reserve("window:left", 7, invoked, invoked.listener))
        assertFalse(old.beginRegistration("window:left", 6, invoked.listener))
        assertFalse(old.beginRegistration("window:left", 7, Any()))
        assertTrue(old.beginRegistration("window:left", 7, invoked.listener))
        assertFalse(old.beginRegistration("window:left", 7, invoked.listener))
        assertFalse(old.discardUnregistered("window:left", invoked.listener))
        old.detachAll()
        assertFalse(old.discardUnregistered("window:left", invoked.listener))
        val replacement = Binding("native-window", Any())
        assertFalse(next.reserve("window:left", 1, replacement, replacement.listener))
        assertTrue(old.drain({ _, _, binding -> assertSame(invoked, binding) }, { _, _, _ -> }))
        assertTrue(next.reserve("window:left", 1, replacement, replacement.listener))
        assertTrue(next.registrationFailed("window:left", replacement.listener))
        assertFalse(next.discardUnregistered("window:left", replacement.listener))
        assertEquals(1, next.pendingCount())
        val failureAfterStop = Binding("native-right-window", Any())
        old.reserve("window:right", 9, failureAfterStop, failureAfterStop.listener)
        old.detachAll()
        assertFalse(old.registrationFailed("window:right", failureAfterStop.listener))
        assertFalse(old.discardUnregistered("window:right", failureAfterStop.listener))
        assertFalse(next.reserve("window:right", 1, Binding("other", Any()), Any()))
    }

    @Test(timeout = 4000)
    fun blockedNativeCleanupDoesNotHoldMetadataLockAndBlocksSameAreaUntilReturn() {
        val quarantine = PositionSubscriptionQuarantine<String>()
        val ledger = PositionSubscriptionLedger<String, Binding>(quarantine = quarantine)
        val otherBridge = PositionSubscriptionLedger<String, Binding>(quarantine = quarantine)
        val old = Binding("door", Any())
        ledger.reserve("door:left", 1, old, old.listener)
        ledger.beginRegistration("door:left", 1, old.listener)
        ledger.detachAll()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val worker =
            Thread {
                try {
                    ledger.drain({ _, _, binding ->
                        assertSame(old, binding)
                        entered.countDown()
                        check(release.await(3, TimeUnit.SECONDS))
                    }, { _, _, error -> throw error })
                } finally {
                    finished.countDown()
                }
            }.apply { isDaemon = true }
        worker.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        try {
            assertTrue(ledger.hasPending())
            assertFalse(ledger.isCurrent("door:left", 1, old.listener))
            val next = Binding("door", Any())
            assertFalse(ledger.reserve("door:left", 2, next, next.listener))
            assertFalse(otherBridge.reserve("door:left", 2, next, next.listener))
            val other = Binding("window", Any())
            assertTrue(ledger.reserve("window:right", 2, other, other.listener))
            assertFalse(ledger.drain({ _, _, _ -> fail("in-flight cleanup cannot be duplicated") }, { _, _, _ -> }))
        } finally {
            release.countDown()
        }
        assertTrue(finished.await(1, TimeUnit.SECONDS))
        assertFalse(ledger.hasPending())
    }
}
