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
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class SocCapabilityReaderTest {
    private class Clock {
        data class Entry(
            val due: Long,
            val action: () -> Unit,
            var cancelled: Boolean = false,
        )

        var now = 0L
        val tasks = mutableListOf<Entry>()

        fun schedule(
            delay: Long,
            action: () -> Unit,
        ): VehicleScheduledTask {
            val entry = Entry(now + delay, action)
            tasks += entry
            return VehicleScheduledTask { entry.cancelled = true }
        }

        fun advance(delay: Long) {
            val target = now + delay
            while (true) {
                val next = tasks.filter { !it.cancelled && it.due <= target }.minByOrNull { it.due } ?: break
                tasks.remove(next)
                now = next.due
                next.action()
            }
            now = target
        }
    }

    private class Harness {
        val clock = Clock()
        val calls = mutableListOf<Pair<(Boolean) -> Unit, (Exception) -> Unit>>()
        val dispatched = mutableListOf<() -> Unit>()
        val ready = mutableListOf<Int>()
        val failures = mutableListOf<Pair<Int, Exception>>()
        var activeEpoch = 1
        val reader =
            SocCapabilityReader(
                check = { done, fail -> calls += done to fail },
                schedule = clock::schedule,
                dispatch = {
                    dispatched += it
                    true
                },
                onReadable = { ready += it },
                onError = { epoch, error -> failures += epoch to error },
                isActive = { it == activeEpoch },
            )

        fun drain() {
            while (dispatched.isNotEmpty()) dispatched.removeAt(0)()
        }
    }

    @Test fun timeoutRecoversWithoutRestartAndOldSuccessCannotDoubleSubscribe() {
        val h = Harness()
        h.reader.start(1)
        h.clock.advance(2200)
        assertTrue(h.failures.single().second is TimeoutException)
        h.clock.advance(2000)
        h.drain()
        assertEquals(2, h.calls.size)
        h.calls[0].first(true)
        assertTrue(h.ready.isEmpty())
        h.calls[1].first(true)
        h.calls[1].first(true)
        h.clock.advance(60000)
        h.drain()
        assertEquals(listOf(1), h.ready)
        assertEquals(2, h.calls.size)
    }

    @Test fun fiveTimedOutAttemptsExhaustFiniteBackoffBudget() {
        val h = Harness()
        h.reader.start(1)
        val delays = listOf(2000L, 4000L, 8000L, 15000L)
        for (delay in delays) {
            h.clock.advance(2200)
            h.clock.advance(delay - 1)
            assertTrue(h.dispatched.isEmpty())
            h.clock.advance(1)
            h.drain()
        }
        h.clock.advance(2200 + 60000)
        h.drain()
        assertEquals(5, h.calls.size)
        assertEquals(5, h.failures.size)
        assertTrue(h.ready.isEmpty())
    }

    @Test fun stopCancelsBackoffAndQueuedRetryCannotLeakIntoRestart() {
        val h = Harness()
        h.reader.start(1)
        h.clock.advance(4200)
        assertEquals(1, h.dispatched.size)
        h.reader.stop(1)
        h.activeEpoch = 2
        h.reader.start(2)
        h.drain()
        h.calls[0].first(true)
        assertTrue(h.ready.isEmpty())
        h.calls[1].first(true)
        assertEquals(listOf(2), h.ready)
        h.clock.advance(60000)
        assertEquals(2, h.calls.size)
    }

    @Test fun oldStopCannotCancelNewGeneration() {
        val h = Harness()
        h.reader.start(1)
        h.activeEpoch = 2
        h.reader.start(2)
        h.reader.stop(1)
        h.calls[1].first(true)
        assertEquals(listOf(2), h.ready)
    }

    @Test fun permissionAndUnsupportedResponsesDoNotRetry() {
        for (error in listOf(SecurityException("test"), UnsupportedOperationException("test"))) {
            val h = Harness()
            h.reader.start(1)
            h.calls.single().second(error)
            h.clock.advance(60000)
            h.drain()
            assertEquals(1, h.calls.size)
            assertEquals(error, h.failures.single().second)
        }
        val h = Harness()
        h.reader.start(1)
        h.calls.single().first(false)
        h.clock.advance(60000)
        h.drain()
        assertEquals(1, h.calls.size)
        assertTrue(h.failures.single().second is UnsupportedOperationException)
    }

    @Test fun staleEpochBeforeQueuedQueryMakesNoVendorCall() {
        val h = Harness()
        h.reader.start(1)
        h.clock.advance(4200)
        h.activeEpoch = 2
        h.drain()
        assertEquals(1, h.calls.size)
        assertEquals(1, h.failures.size)
    }

    @Test(timeout = 4000)
    fun blockedVendorDoesNotBlockTimeoutStopOrDispatchAndLateResultIsIgnored() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val ready = AtomicInteger()
        val failures = AtomicInteger()
        val clock = Clock()
        val io = VehicleSdkIo()
        val reader =
            SocCapabilityReader(
                check = { done, _ ->
                    entered.countDown()
                    release.await(3, TimeUnit.SECONDS)
                    done(true)
                },
                schedule = clock::schedule,
                dispatch = { io.request("soc.retry", it) },
                onReadable = { ready.incrementAndGet() },
                onError = { _, _ -> failures.incrementAndGet() },
            )
        Thread {
            try {
                io.run { reader.start(1) }
            } finally {
                finished.countDown()
            }
        }.apply { isDaemon = true }.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        try {
            clock.advance(4200)
            assertEquals(1, failures.get())
            reader.stop(1)
            assertFalse(finished.await(20, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
        }
        assertTrue(finished.await(1, TimeUnit.SECONDS))
        assertEquals(0, ready.get())
    }

    @Test fun synchronousFailureRetriesButRejectedRetryDispatchIsTerminal() {
        val clock = Clock()
        val errors = mutableListOf<Exception>()
        var calls = 0
        val reader =
            SocCapabilityReader(
                check = { _, _ ->
                    calls++
                    throw IllegalStateException("temporary service failure")
                },
                schedule = clock::schedule,
                dispatch = { false },
                onReadable = { fail("must not be readable") },
                onError = { _, error -> errors += error },
            )
        reader.start(1)
        clock.advance(60000)
        assertEquals(1, calls)
        assertEquals(2, errors.size)
    }
}

class SocCapabilityTraceAttributionTest {
    @Test fun anOldCapabilityCallbackKeepsItsOriginalEpochAndAttemptAfterRetryOrRestart() {
        val calls = mutableListOf<Pair<(Boolean) -> Unit, (Exception) -> Unit>>()
        val timers = mutableListOf<() -> Unit>()
        val traces = mutableListOf<Triple<String, Int, Int>>()
        val reader =
            SocCapabilityReader(
                check = { done, failed -> calls += done to failed },
                schedule = { _, action ->
                    timers += action
                    VehicleScheduledTask {}
                },
                dispatch = {
                    it()
                    true
                },
                onReadable = {},
                onError = { _, _ -> },
                traceContext = { event, epoch, attempt, _ -> traces += Triple(event, epoch, attempt) },
            )
        reader.start(3)
        timers[0]()
        timers[1]()
        calls[0].first(true)
        assertEquals(Triple("ignored", 3, 1), traces.last())
        calls[0].second(IllegalStateException("late old failure"))
        assertEquals(Triple("ignored", 3, 1), traces.last())
        reader.start(4)
        calls[1].first(true)
        assertEquals(Triple("ignored", 3, 2), traces.last())
        calls[2].first(true)
        assertEquals(Triple("capability", 4, 1), traces.last())
    }
}
