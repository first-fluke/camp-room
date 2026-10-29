package camp.room.app

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class SdkLifecycleQueueTest {
    @Test(timeout = 4000) fun uncontendedCleanupAlwaysRunsOnRealWorkerNotCaller() {
        val caller = Thread.currentThread().id
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val done = CountDownLatch(1)
        var nativeThread = caller
        try {
            val queue = SdkLifecycleQueue(worker)
            assertTrue(queue.submit(SdkLifecycleStep.CLOSE) {
                nativeThread = Thread.currentThread().id
                done.countDown()
            })
            assertTrue(done.await(1, TimeUnit.SECONDS))
            assertNotEquals(caller, nativeThread)
        } finally { worker.close() }
    }

    @Test(timeout = 4000) fun blockedStopFinishesBeforeCloseAndFinalReleaseCannotCancelCleanup() {
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val entered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val calls = Collections.synchronizedList(mutableListOf<String>())
        try {
            val queue = SdkLifecycleQueue(worker)
            queue.submit(SdkLifecycleStep.STOP) {
                entered.countDown()
                releaseStop.await(2, TimeUnit.SECONDS)
                calls += "unregister"
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            queue.submit(SdkLifecycleStep.CLOSE) { calls += "release"; finished.countDown() }
            assertFalse(finished.await(20, TimeUnit.MILLISECONDS))
            assertFalse(queue.submit(SdkLifecycleStep.START) { fail("work after close") })
            releaseStop.countDown()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertEquals(listOf("unregister", "release"), calls.toList())
        } finally { releaseStop.countDown(); worker.close() }
    }

    @Test(timeout = 4000) fun pendingTransitionsCoalesceWithinThreeKeysButFinalCleanupIsRetained() {
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val done = CountDownLatch(1)
        val calls = Collections.synchronizedList(mutableListOf<String>())
        try {
            val queue = SdkLifecycleQueue(worker)
            queue.submit(SdkLifecycleStep.START) { entered.countDown(); unblock.await(2, TimeUnit.SECONDS) }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            repeat(100) { n ->
                queue.submit(SdkLifecycleStep.STOP) { calls += "stop$n" }
                queue.submit(SdkLifecycleStep.START) { calls += "start$n" }
            }
            queue.submit(SdkLifecycleStep.CLOSE) { calls += "release"; done.countDown() }
            unblock.countDown()
            assertTrue(done.await(1, TimeUnit.SECONDS))
            assertEquals(listOf("stop99", "start99", "release"), calls.toList())
        } finally { unblock.countDown(); worker.close() }
    }

    @Test(timeout = 4000) fun failedStopStillAllowsFinalCleanup() {
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val done = CountDownLatch(1)
        val errors = Collections.synchronizedList(mutableListOf<Exception>())
        try {
            val queue = SdkLifecycleQueue(worker) { errors += it }
            queue.submit(SdkLifecycleStep.STOP) { throw IllegalStateException("native unregister failed") }
            queue.submit(SdkLifecycleStep.CLOSE) { done.countDown() }
            assertTrue(done.await(1, TimeUnit.SECONDS))
            assertEquals(1, errors.size)
        } finally { worker.close() }
    }

    @Test(timeout = 6000) fun ensureWinningGuardBeforeOldStopKeepsNewSessionButStillDrainsOldCleanup() = runBlocking {
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val guard = Mutex()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val done = CountDownLatch(1)
        val epoch = AtomicInteger(1)
        val session = AtomicInteger(1)
        val calls = Collections.synchronizedList(mutableListOf<String>())
        try {
            val queue = SdkLifecycleQueue(worker, withGuard = { work -> guard.withLock { work() } })
            queue.submit(SdkLifecycleStep.START) { entered.countDown(); unblock.await(3, TimeUnit.SECONDS) }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            epoch.set(2)
            val stoppedEpoch = epoch.get()
            queue.submitStop(
                shouldStop = { stoppedEpoch == epoch.get() },
                cleanup = { calls += "old-unregister" },
                stop = { session.set(0); calls += "stop" },
            )
            epoch.set(3)
            // Ensure becomes the first suspended guard waiter while the queue
            // worker is still in the preceding lifecycle item.
            val ensure = launch(start = CoroutineStart.UNDISPATCHED) {
                guard.withLock { session.set(3); calls += "new-initialize" }
            }
            queue.submit(SdkLifecycleStep.CLOSE) { done.countDown() }
            unblock.countDown()
            ensure.join()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(3, session.get())
            assertEquals(listOf("new-initialize", "old-unregister"), calls.toList())
        } finally { unblock.countDown(); worker.close() }
    }
}
