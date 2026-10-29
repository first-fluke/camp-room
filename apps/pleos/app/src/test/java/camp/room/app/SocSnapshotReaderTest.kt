package camp.room.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class SocSnapshotReaderTest {
    private class Harness {
        val listener = Any()
        val hostSoc = AtomicReference<Int?>(null)
        val reads = mutableListOf<Pair<(Int?) -> Unit, (Exception) -> Unit>>()
        val failures = mutableListOf<Exception>()
        val reader = SocSnapshotReader<Any>(
            read = { done, failed -> reads += done to failed },
            onValue = hostSoc::set,
            onError = { failures += it; hostSoc.set(null) },
        )
    }

    @Test(timeout = 6000) fun sameViewModelFastStopStartRefreshesSocWithoutNewEventOrRegistration() = runBlocking {
        val h = Harness()
        val initialize = AtomicInteger()
        val capabilities = AtomicInteger()
        val registrations = AtomicInteger()
        val stops = AtomicInteger()
        val cleanup = AtomicInteger()
        val hostEpoch = AtomicInteger(1)
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val guard = Mutex()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        fun initializeAndDiscover() {
            initialize.incrementAndGet()
            capabilities.incrementAndGet()
            registrations.incrementAndGet()
            h.reader.bind(1, h.listener)
            h.reader.refresh(1, h.listener, "initial")
        }
        try {
            h.reader.startOrRefresh(1, false, null, ::initializeAndDiscover)
            h.reads.single().first(100)
            assertTrue(h.reader.onEvent(1, h.listener, 99))
            val queue = SdkLifecycleQueue(worker, withGuard = { work -> guard.withLock { work() } })
            queue.submit(SdkLifecycleStep.START) { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            hostEpoch.set(2)
            h.hostSoc.set(null)
            val stoppedEpoch = hostEpoch.get()
            queue.submitStop(
                shouldStop = { stoppedEpoch == hostEpoch.get() },
                cleanup = { cleanup.incrementAndGet() },
                stop = { stops.incrementAndGet(); h.reader.invalidate() },
            )
            hostEpoch.set(3)
            h.hostSoc.set(null)
            // The SAME host/reader/listener survives. Ensure wins discoveryGuard
            // ahead of the old STOP and uses the production active-reuse branch.
            val ensure = launch(start = CoroutineStart.UNDISPATCHED) {
                guard.withLock { h.reader.startOrRefresh(1, true, h.listener, ::initializeAndDiscover) }
            }
            queue.submit(SdkLifecycleStep.CLOSE) { finished.countDown() }
            release.countDown()
            ensure.join()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertNull(h.hostSoc.get())
            assertEquals(2, h.reads.size)
            // No subscription event occurs after hostSoc was cleared.
            h.reads[1].first(100)
            assertEquals(100, h.hostSoc.get())
            assertEquals(1, initialize.get())
            assertEquals(1, capabilities.get())
            assertEquals(1, registrations.get())
            assertEquals(0, stops.get())
            assertEquals(1, cleanup.get())
        } finally { release.countDown(); worker.close() }
    }

    @Test fun earlierSubscriptionEventDoesNotSuppressFreshReuseGetter() {
        val h = Harness()
        h.reader.bind(1, h.listener)
        h.reader.onEvent(1, h.listener, 90)
        h.hostSoc.set(null)
        h.reader.startOrRefresh(1, true, h.listener) { fail("reuse must not initialize") }
        h.reads.single().first(91)
        assertEquals(91, h.hostSoc.get())
    }

    @Test fun lateOlderGetterAndErrorCannotOverwriteNewResumeSnapshot() {
        val h = Harness()
        h.reader.bind(1, h.listener)
        h.reader.refresh(1, h.listener, "initial")
        h.reader.startOrRefresh(1, true, h.listener) { fail("reuse must not initialize") }
        h.reads[1].first(95)
        h.reads[0].first(80)
        h.reads[0].second(IllegalStateException("late failure"))
        assertEquals(95, h.hostSoc.get())
        assertTrue(h.failures.isEmpty())
    }

    @Test fun subscriptionAfterFreshRequestWinsOverGetterOrGetterFailure() {
        val h = Harness()
        h.reader.bind(1, h.listener)
        h.reader.refresh(1, h.listener, "active_reuse")
        h.reader.onEvent(1, h.listener, 94)
        h.reads[0].first(90)
        assertEquals(94, h.hostSoc.get())
        h.reader.refresh(1, h.listener, "active_reuse")
        h.reader.onEvent(1, h.listener, 93)
        h.reads[1].second(IllegalStateException("superseded failure"))
        assertEquals(93, h.hostSoc.get())
        assertTrue(h.failures.isEmpty())
    }

    @Test fun stopAndListenerReplacementRejectOldGetterEventAndSpecificInvalidation() {
        val h = Harness()
        h.reader.bind(1, h.listener)
        h.reader.refresh(1, h.listener, "initial")
        h.reader.invalidate()
        val replacement = Any()
        h.reader.bind(2, replacement)
        h.reader.refresh(2, replacement, "initial")
        h.reads[1].first(70)
        h.reader.invalidate(1, h.listener)
        h.reads[0].first(99)
        assertFalse(h.reader.onEvent(1, h.listener, 99))
        assertEquals(70, h.hostSoc.get())
        assertTrue(h.reader.refresh(2, replacement, "active_reuse"))
    }

    @Test fun activeReuseWhileCapabilityPendingDoesNotStartSecondDiscovery() {
        val h = Harness()
        var discovers = 0
        h.reader.startOrRefresh(1, false, null) { discovers++ }
        h.reader.startOrRefresh(1, true, null) { discovers++ }
        assertEquals(1, discovers)
        assertTrue(h.reads.isEmpty())
    }

    @Test fun duplicateGetterCallbacksPublishOnlyOnce() {
        val h = Harness()
        h.reader.bind(1, h.listener)
        h.reader.refresh(1, h.listener, "initial")
        h.reads.single().first(80)
        h.reads.single().first(60)
        h.reads.single().second(IllegalStateException("duplicate error"))
        assertEquals(80, h.hostSoc.get())
        assertTrue(h.failures.isEmpty())
    }

    @Test fun synchronousGetterFailureKeepsSubscriptionAvailableForNextRefresh() {
        val listener = Any()
        var failRead = true
        var value: Int? = null
        val errors = mutableListOf<Exception>()
        val reader = SocSnapshotReader<Any>(
            read = { done, _ -> if (failRead) throw IllegalStateException("temporary") else done(88) },
            onValue = { value = it }, onError = { errors += it },
        )
        reader.bind(1, listener)
        reader.refresh(1, listener, "initial")
        failRead = false
        reader.startOrRefresh(1, true, listener) { fail("must not register again") }
        assertEquals(1, errors.size)
        assertEquals(88, value)
    }

    @Test(timeout = 4000) fun blockedGetterDoesNotHoldSharedMetadataLockAndStopRejectsItsResult() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val invalidated = CountDownLatch(1)
        val value = AtomicReference<Int?>(null)
        val listener = Any()
        val reader = SocSnapshotReader<Any>(
            lock = Any(),
            read = { success, _ -> entered.countDown(); release.await(3, TimeUnit.SECONDS); success(100) },
            onValue = value::set, onError = { fail("unexpected failure") },
        )
        reader.bind(1, listener)
        Thread { try { reader.refresh(1, listener, "active_reuse") } finally { done.countDown() } }
            .apply { isDaemon = true }.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            Thread { reader.invalidate(); invalidated.countDown() }.apply { isDaemon = true }.start()
            assertTrue(invalidated.await(200, TimeUnit.MILLISECONDS))
        } finally { release.countDown() }
        assertTrue(done.await(1, TimeUnit.SECONDS))
        assertNull(value.get())
    }
}
