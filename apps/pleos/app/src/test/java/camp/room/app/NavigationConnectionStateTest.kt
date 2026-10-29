package camp.room.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class NavigationConnectionStateTest {
    @Test fun releaseFailureDoesNotTurnNextCloseIntoFalseSuccess() {
        val state = NavigationConnectionState()
        state.connect({}, {}) { done, _ -> done() }
        var releases = 0
        assertThrows(IllegalStateException::class.java) { state.close { releases++; throw IllegalStateException("native failure") } }
        assertThrows(IllegalStateException::class.java) { state.requireConnected() }
        state.close { releases++ }
        state.close { fail("already successful release must not repeat") }
        assertEquals(2, releases)
    }

    @Test(timeout = 6000) fun sdkConnectionCallbackAndStateAccessDoNotWaitForBlockedInitializeMonitor() {
        val state = NavigationConnectionState()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val connected = AtomicInteger()
        var success: (() -> Unit)? = null
        Thread {
            try { state.connect({ connected.incrementAndGet() }, {}) { done, _ ->
                success = done
                entered.countDown()
                release.await(3, TimeUnit.SECONDS)
            } } finally { finished.countDown() }
        }.apply { isDaemon = true }.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        try {
            val before = System.nanoTime()
            success!!.invoke()
            state.requireConnected()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 500)
            assertEquals(1, connected.get())
        } finally { release.countDown() }
        assertTrue(finished.await(2, TimeUnit.SECONDS))
    }

    @Test fun closedOrTerminalConnectCallbacksCannotReviveConnection() {
        val state = NavigationConnectionState()
        var done: (() -> Unit)? = null
        var failed: ((Throwable) -> Unit)? = null
        var successes = 0
        var failures = 0
        state.connect({ successes++ }, { failures++ }) { success, error -> done = success; failed = error }
        failed!!.invoke(IllegalStateException("service failure"))
        done!!.invoke()
        assertEquals(0, successes)
        assertEquals(1, failures)
        assertThrows(IllegalStateException::class.java) { state.requireConnected() }
        state.close {}
        done!!.invoke()
        assertEquals(0, successes)
    }

    @Test(timeout = 6000) fun clientCallbackRunsOutsideConnectionMetadataMonitor() {
        val state = NavigationConnectionState()
        val checked = CountDownLatch(1)
        state.connect({
            Thread { state.requireConnected(); checked.countDown() }.apply { isDaemon = true }.start()
            assertTrue(checked.await(2, TimeUnit.SECONDS))
        }, {}) { done, _ -> done() }
    }
}
