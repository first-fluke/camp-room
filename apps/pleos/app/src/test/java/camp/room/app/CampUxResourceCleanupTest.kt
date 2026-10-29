package camp.room.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CampUxResourceCleanupTest {
    @Test fun failedUnregisterRetainsExactManagerListenerAndCarUntilRetrySuccess() {
        val state = CampUxResourceCleanup<Any, Any, Any>()
        val car = Any(); val manager = Any(); val listener = Any()
        state.attachCar(car); state.attachBinding(manager, listener)
        var disconnects = 0
        assertThrows(IllegalStateException::class.java) {
            state.cleanup({ m, l ->
                assertSame(manager, m); assertSame(listener, l)
                throw IllegalStateException("temporary unregister failure")
            }, { disconnects++ })
        }
        assertTrue(state.hasBinding()); assertSame(car, state.car()); assertEquals(0, disconnects)
        assertTrue(state.cleanup({ m, l -> assertSame(manager, m); assertSame(listener, l) }, {
            assertSame(car, it); disconnects++
        }))
        assertFalse(state.hasResources()); assertEquals(1, disconnects)
    }

    @Test fun failedDisconnectRetainsExactCarAndDoesNotRepeatSuccessfulUnregister() {
        val state = CampUxResourceCleanup<Any, Any, Any>()
        val car = Any(); state.attachCar(car); state.attachBinding(Any(), Any())
        var unregisters = 0; var disconnects = 0
        assertThrows(IllegalStateException::class.java) {
            state.cleanup({ _, _ -> unregisters++ }, {
                assertSame(car, it); disconnects++; throw IllegalStateException("temporary disconnect failure")
            })
        }
        assertFalse(state.hasBinding()); assertSame(car, state.car())
        assertTrue(state.cleanup({ _, _ -> unregisters++ }, { assertSame(car, it); disconnects++ }))
        assertEquals(1, unregisters); assertEquals(2, disconnects)
        assertFalse(state.hasResources())
    }

    @Test fun repeatedCloseAfterSuccessNeverRepeatsNativeCleanup() {
        val state = CampUxResourceCleanup<Any, Any, Any>()
        state.attachCar(Any()); state.attachBinding(Any(), Any())
        var unregisters = 0; var disconnects = 0
        repeat(3) { assertTrue(state.cleanup({ _, _ -> unregisters++ }, { disconnects++ })) }
        assertEquals(1, unregisters); assertEquals(1, disconnects)
    }

    @Test fun newGenerationCannotReplaceUnreleasedBindingOrCar() {
        val state = CampUxResourceCleanup<Any, Any, Any>()
        val oldCar = Any(); val oldManager = Any(); val oldListener = Any()
        val newCar = Any(); val newManager = Any(); val newListener = Any()
        state.attachCar(oldCar); state.attachBinding(oldManager, oldListener)
        assertThrows(IllegalStateException::class.java) { state.attachCar(newCar) }
        assertThrows(IllegalStateException::class.java) { state.attachBinding(newManager, newListener) }
        assertThrows(IllegalStateException::class.java) {
            state.cleanup({ _, _ -> throw IllegalStateException("still bound") }, { fail("must not disconnect") })
        }
        assertThrows(IllegalStateException::class.java) { state.attachBinding(newManager, newListener) }
        assertTrue(state.cleanup({ m, l -> assertSame(oldManager, m); assertSame(oldListener, l) }, { assertSame(oldCar, it) }))
        state.attachCar(newCar); state.attachBinding(newManager, newListener)
        assertTrue(state.cleanup({ m, l -> assertSame(newManager, m); assertSame(newListener, l) }, { assertSame(newCar, it) }))
    }

    @Test fun rebindOnSameCarRequiresExactOldListenerCleanupAndKeepsCarAlive() {
        val state = CampUxResourceCleanup<Any, Any, Any>()
        val car = Any(); val oldManager = Any(); val oldListener = Any()
        state.attachCar(car); state.attachBinding(oldManager, oldListener)
        assertTrue(state.cleanupBinding { m, l -> assertSame(oldManager, m); assertSame(oldListener, l) })
        assertSame(car, state.car())
        val newManager = Any(); val newListener = Any()
        state.attachBinding(newManager, newListener)
        assertTrue(state.cleanup({ m, l -> assertSame(newManager, m); assertSame(newListener, l) }, { assertSame(car, it) }))
    }

    @Test(timeout = 4000) fun blockedUnregisterDoesNotHoldMetadataMonitor() {
        val state = CampUxResourceCleanup<Any, Any, Any>()
        val car = Any(); state.attachCar(car); state.attachBinding(Any(), Any())
        val entered = CountDownLatch(1); val finish = CountDownLatch(1); val done = CountDownLatch(1)
        Thread {
            try { state.cleanup({ _, _ -> entered.countDown(); finish.await(2, TimeUnit.SECONDS) }, {}) }
            finally { done.countDown() }
        }.apply { isDaemon = true }.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        try {
            val start = System.nanoTime()
            assertSame(car, state.car()); assertTrue(state.hasBinding())
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500)
            assertThrows(IllegalStateException::class.java) { state.attachBinding(Any(), Any()) }
        } finally { finish.countDown() }
        assertTrue(done.await(1, TimeUnit.SECONDS)); assertFalse(state.hasResources())
    }

    @Test fun automaticRetryBudgetIsBoundedButCanResetAfterSuccessfulCleanup() {
        val budget = CampUxCleanupRetryBudget()
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 15000L), (1..5).map { budget.nextDelayMillis() })
        assertNull(budget.nextDelayMillis()); assertNull(budget.nextDelayMillis()); assertEquals(5, budget.attempts)
        budget.reset(); assertEquals(1000L, budget.nextDelayMillis()); assertEquals(1, budget.attempts)
    }
}
