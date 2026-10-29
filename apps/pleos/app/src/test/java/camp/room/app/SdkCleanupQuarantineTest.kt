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
import java.util.concurrent.atomic.AtomicInteger

class SdkCleanupQuarantineTest {
    private class Resource(
        val name: String,
    ) {
        // Vendor objects may have value equality; admission must still use native identity.
        override fun equals(other: Any?) = other is Resource && name == other.name

        override fun hashCode() = name.hashCode()
    }

    @Test fun failedReleaseRetainsExactOldOwnerAndScheduledRetrySucceedsAfterHostDropsIt() {
        val quarantine = SdkCleanupQuarantine<Resource>()
        val sdk = Resource("sdk")
        val scheduled = mutableListOf<() -> Unit>()
        var releases = 0
        val binding = SdkReleaseLease(sdk)
        binding.markAcquired()
        var failureCount = 0

        fun close() {
            binding.requestRelease()
            quarantine.retain(sdk, this, {
                binding.releaseIfRequested { exact ->
                    assertSame(sdk, exact)
                    releases++
                    if (releases == 1) throw IllegalStateException("release failed")
                }
                check(!binding.hasResource())
            }, { _, task -> scheduled += task }, { failureCount++ })
            quarantine.drain(sdk)
        }
        close()
        assertTrue(binding.hasResource())
        assertTrue(quarantine.hasPending(sdk))
        assertEquals(1, releases)
        scheduled.removeAt(0).invoke() // No reference or callback from the retired host is required.
        assertFalse(binding.hasResource())
        assertFalse(quarantine.hasPending(sdk))
        assertEquals(2, releases)
        assertEquals(1, failureCount)
        close()
        assertEquals(2, releases)
    }

    @Test fun initializeFailureStillOwnsPossiblyBoundResourceAndMustReleaseBeforeRetry() {
        val sdk = Resource("partial-binding")
        val binding = SdkReleaseLease(sdk)
        assertThrows(IllegalStateException::class.java) {
            binding.markAcquired()
            throw IllegalStateException("initialize bound then failed")
        }
        assertTrue(binding.hasResource())
        assertThrows(IllegalStateException::class.java) { binding.markAcquired() }
        binding.requestRelease()
        assertThrows(IllegalStateException::class.java) {
            binding.releaseIfRequested {
                assertSame(sdk, it)
                throw IllegalStateException("first release failed")
            }
        }
        assertTrue(binding.hasResource())
        assertTrue(binding.releaseIfRequested { assertSame(sdk, it) })
        binding.markAcquired()
        assertTrue(binding.hasResource())
        assertFalse(binding.releaseIfRequested { fail("new generation has not requested release") })
        binding.requestRelease()
        assertTrue(binding.releaseIfRequested { assertSame(sdk, it) })
    }

    @Test fun duplicateCloseKeepsOriginalCleanupAndOnlySuccessfulReleaseCompletesIt() {
        val quarantine = SdkCleanupQuarantine<Resource>()
        val sdk = Resource("same")
        val owner = Any()
        var releases = 0
        var shouldFail = true
        val cleanup = {
            releases++
            if (shouldFail) throw IllegalStateException("pending")
        }
        quarantine.retain(sdk, owner, cleanup, { _, _ -> }, {})
        assertFalse(quarantine.drain(sdk))
        quarantine.retain(sdk, owner, { fail("must not replace exact cleanup") }, { _, _ -> }, {})
        shouldFail = false
        assertTrue(quarantine.drain(sdk))
        assertTrue(quarantine.drain(sdk))
        assertEquals(2, releases)
    }

    @Test fun newOwnerOfSameNativeObjectMustCleanOldOwnerBeforeInitializeButDifferentObjectIsIndependent() {
        val quarantine = SdkCleanupQuarantine<Resource>()
        val oldSdk = Resource("sdk")
        val differentSdk = Resource("sdk")
        var releases = 0
        var failed = true
        val events = mutableListOf<String>()
        quarantine.retain(oldSdk, Any(), {
            releases++
            events += "release-old"
            if (failed) throw IllegalStateException("still bound")
        }, { _, _ -> }, {})

        fun start(resource: Resource): Boolean {
            if (!quarantine.drain(resource)) return false
            events += if (resource === oldSdk) "initialize-same" else "initialize-other"
            return true
        }
        assertFalse(start(oldSdk))
        assertTrue(start(differentSdk))
        assertTrue(quarantine.hasPending(oldSdk))
        failed = false
        assertTrue(start(oldSdk))
        assertEquals(listOf("release-old", "initialize-other", "release-old", "initialize-same"), events)
        assertEquals(2, releases)
    }

    @Test fun delayedRetryForOldGenerationCannotRemoveOrReleaseReplacementOwner() {
        val quarantine = SdkCleanupQuarantine<Resource>()
        val sdk = Resource("sdk")
        val owner = Any()
        val scheduled = mutableListOf<() -> Unit>()
        var oldCalls = 0
        var replacementCalls = 0
        quarantine.retain(sdk, owner, {
            oldCalls++
            if (oldCalls == 1) throw IllegalStateException("old failure")
        }, { _, task -> scheduled += task }, {})
        assertFalse(quarantine.drain(sdk))
        assertTrue(quarantine.drain(sdk))
        quarantine.retain(
            sdk,
            owner,
            {
                replacementCalls++
                throw IllegalStateException("new pending")
            },
            { _, task -> scheduled += task },
            {},
        )
        val oldRetry = scheduled.removeAt(0)
        oldRetry()
        assertEquals(0, replacementCalls)
        assertTrue(quarantine.hasPending(sdk))
        assertFalse(quarantine.drain(sdk))
        assertEquals(1, replacementCalls)
        assertEquals(2, oldCalls)
    }

    @Test fun automaticRetryIsBoundedAndExhaustionStillRetainsCleanupForLaterExplicitAdmission() {
        val quarantine = SdkCleanupQuarantine<Resource>(automaticAttempts = 3)
        val sdk = Resource("sdk")
        val scheduled = mutableListOf<() -> Unit>()
        var calls = 0
        var fail = true
        quarantine.retain(
            sdk,
            Any(),
            {
                calls++
                if (fail) throw IllegalStateException("failed")
            },
            { _, task -> scheduled += task },
            {},
        )
        assertFalse(quarantine.drain(sdk))
        while (scheduled.isNotEmpty()) scheduled.removeAt(0).invoke()
        assertEquals(3, calls)
        assertTrue(quarantine.hasPending(sdk))
        fail = false
        assertTrue(quarantine.drain(sdk))
        assertEquals(4, calls)
    }

    @Test(timeout = 6000)
    fun blockedNativeCleanupDoesNotHoldRegistryMonitorAndSameObjectAdmissionFailsClosed() {
        val quarantine = SdkCleanupQuarantine<Resource>()
        val sdk = Resource("sdk")
        val other = Resource("other")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val calls = AtomicInteger()
        val lease = SdkReleaseLease(sdk)
        lease.markAcquired()
        lease.requestRelease()
        quarantine.retain(sdk, Any(), {
            lease.releaseIfRequested { exact ->
                assertSame(sdk, exact)
                calls.incrementAndGet()
                entered.countDown()
                release.await(3, TimeUnit.SECONDS)
            }
            check(!lease.hasResource())
        }, { _, _ -> }, {})
        Thread {
            try {
                quarantine.drain(sdk)
            } finally {
                finished.countDown()
            }
        }.apply { isDaemon = true }
            .start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        try {
            assertTrue(quarantine.hasPending(sdk))
            assertTrue(lease.hasResource())
            assertThrows(IllegalStateException::class.java) { lease.markAcquired() }
            assertFalse(quarantine.drain(sdk))
            assertTrue(quarantine.drain(other))
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
        }
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertFalse(quarantine.hasPending(sdk))
        assertFalse(lease.hasResource())
    }

    @Test fun failedListenerCleanupDoesNotReleaseResourceOrLoseOwnership() {
        val quarantine = SdkCleanupQuarantine<Resource>()
        val sdk = Resource("sdk")
        var listenersPending = true
        var releases = 0
        quarantine.retain(sdk, Any(), {
            check(!listenersPending) { "listener unregister not confirmed" }
            releases++
        }, { _, _ -> }, {})
        assertFalse(quarantine.drain(sdk))
        assertEquals(0, releases)
        listenersPending = false
        assertTrue(quarantine.drain(sdk))
        assertEquals(1, releases)
    }

    @Test fun diagnosticFailureOrRetryDispatchFailureCannotDiscardNativeCleanup() {
        val quarantine = SdkCleanupQuarantine<Resource>()
        val sdk = Resource("sdk")
        var fail = true
        quarantine.retain(
            sdk,
            Any(),
            { if (fail) throw IllegalStateException("native") },
            { _, _ -> throw IllegalStateException("scheduler unavailable") },
            { throw IllegalStateException("diagnostic failed") },
        )
        assertFalse(quarantine.drain(sdk))
        assertTrue(quarantine.hasPending(sdk))
        fail = false
        assertTrue(quarantine.drain(sdk))
    }

    @Test fun fiveFailedUnregistersRemainBoundedThenExplicitCloseCanReleaseBeforeNextInitialize() {
        val quarantine = SdkCleanupQuarantine<Resource>()
        val sdk = Resource("sdk")
        val owner = Any()
        var now = 0L
        val listeners = VehicleListenerCleanup<Any>(nowMs = { now })
        val listener = Any()
        listeners.retain(listener)
        val lease = SdkReleaseLease(sdk)
        lease.markAcquired()
        lease.requestRelease()
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        var unregisters = 0
        var releases = 0
        var initializes = 1
        var failNative = true

        fun retainClose() {
            quarantine.retain(sdk, owner, {
                listeners.drain({ exact ->
                    assertSame(listener, exact)
                    unregisters++
                    if (failNative) throw IllegalStateException("native unregister failed")
                }, {})
                check(!listeners.hasPending())
                lease.releaseIfRequested { exact ->
                    assertSame(sdk, exact)
                    releases++
                }
                check(!lease.hasResource())
            }, { delay, task -> scheduled += delay to task }, {}, listeners::rearmExhausted)
        }
        retainClose()
        assertFalse(quarantine.drain(sdk))
        while (scheduled.isNotEmpty()) {
            val (delay, task) = scheduled.removeAt(0)
            now += delay
            task()
        }
        assertEquals(5, unregisters)
        assertEquals(0, releases)
        assertEquals(1, initializes)
        assertTrue(listeners.hasPending())
        assertTrue(quarantine.hasPending(sdk))

        // Old host close explicitly retried after the bounded automatic budget ran out.
        failNative = false
        retainClose()
        assertTrue(quarantine.drain(sdk))
        assertEquals(6, unregisters)
        assertEquals(1, releases)
        assertFalse(listeners.hasPending())
        // The next owner can initialize only after the exact old object has really released.
        if (quarantine.drain(sdk)) initializes++
        assertEquals(2, initializes)
        assertEquals(1, releases)
    }
}
