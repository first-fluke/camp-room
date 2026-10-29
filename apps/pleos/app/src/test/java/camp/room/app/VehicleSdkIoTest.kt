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
import java.util.concurrent.Executor

class VehicleSdkIoTest {
    @Test fun oneWorkerAndBoundedPendingCallbacksCoalesceByIdentityKey() {
        val queue = mutableListOf<Runnable>()
        val events = mutableListOf<Int>()
        val io = VehicleSdkIo(Executor { queue += it }, capacity = 2)
        assertTrue(io.request("soc") { events += 1 })
        assertTrue(io.request("soc") { events += 2 })
        assertTrue(io.request("confirm") { events += 3 })
        assertFalse(io.request("excess") { fail("capacity must be bounded") })
        assertEquals(1, queue.size)
        queue.single().run()
        assertEquals(listOf(2, 3), events)
    }

    @Test fun synchronousLifecycleCallCompletesBeforeReturning() {
        val events = mutableListOf<String>()
        val io = VehicleSdkIo(Executor { error("synchronous lifecycle must not enqueue") })
        io.run { events += "initialize" }
        events += "returned"
        assertEquals(listOf("initialize", "returned"), events)
    }

    @Test fun callbackFailureDoesNotLoseFollowingWorkAndRejectedWorkerCanRecover() {
        val queue = mutableListOf<Runnable>()
        val errors = mutableListOf<Exception>()
        var reject = true
        val io =
            VehicleSdkIo(
                Executor {
                    if (reject) throw java.util.concurrent.RejectedExecutionException("test")
                    queue += it
                },
                onFailure = { errors += it },
            )
        assertFalse(io.request("initial") { fail("rejected") })
        reject = false
        var done = false
        assertTrue(io.request("failed") { throw IllegalStateException("test") })
        assertTrue(io.request("next") { done = true })
        queue.single().run()
        assertTrue(done)
        assertEquals(2, errors.size)
    }
}
