package camp.room.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CampUxSerialWorkerTest {
    @Test(timeout = 3000) fun blockedBinderDoesNotBlockRequestersOrSpawnMoreWorkers() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val latestDone = CountDownLatch(1)
        val launches = AtomicInteger()
        val executed = mutableListOf<Int>()
        val worker = CampUxSerialWorker(Executor { task ->
            launches.incrementAndGet()
            Thread(task, "ux-worker-test").apply { isDaemon = true }.start()
        })
        worker.request { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        try {
            for (i in 1..1000) worker.request {
                synchronized(executed) { executed += i }
                latestDone.countDown()
            }
            assertEquals(1, launches.get())
            assertFalse(latestDone.await(20, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
        }
        assertTrue(latestDone.await(1, TimeUnit.SECONDS))
        assertEquals(listOf(1000), synchronized(executed) { executed.toList() })
    }

    @Test fun aFailureStillDrainsTheLatestDesiredLifecycle() {
        val queue = mutableListOf<Runnable>()
        val failures = mutableListOf<Throwable>()
        val worker = CampUxSerialWorker(Executor { queue += it }) { failures += it }
        var stopped = false
        worker.request {
            worker.request { stopped = true }
            throw IllegalStateException("simulated Binder failure")
        }
        queue.removeAt(0).run()
        assertEquals(1, failures.size)
        assertTrue(stopped)
        assertTrue(queue.isEmpty())
    }

    @Test fun anIdleWorkerCanBeRequestedAgain() {
        val queue = mutableListOf<Runnable>()
        val worker = CampUxSerialWorker(Executor { queue += it })
        var runs = 0
        worker.request { runs++ }
        queue.removeAt(0).run()
        worker.request { runs++ }
        assertEquals(1, queue.size)
        queue.removeAt(0).run()
        assertEquals(2, runs)
    }

    @Test fun rejectedDispatchDoesNotPermanentlyBlockRetry() {
        val queue = mutableListOf<Runnable>()
        var rejected = true
        val worker = CampUxSerialWorker(Executor {
            if (rejected) throw java.util.concurrent.RejectedExecutionException("test")
            queue += it
        })
        worker.request { error("rejected work must not run") }
        rejected = false
        var retried = false
        worker.request { retried = true }
        queue.removeAt(0).run()
        assertTrue(retried)
    }
}
