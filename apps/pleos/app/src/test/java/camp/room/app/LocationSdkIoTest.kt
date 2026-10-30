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

class LocationSdkIoTest {
    @Test(timeout = 5000)
    fun blockedReleaseCannotHoldStopOrRestartMetadataAndOldCallbackIsRejected() {
        val io = LocationSdkIo()
        val old = io.advance()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        Thread {
            try {
                io.native {
                    entered.countDown()
                    release.await(3, TimeUnit.SECONDS)
                }
            } finally {
                done.countDown()
            }
        }.apply { isDaemon = true }
            .start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        try {
            val start = System.nanoTime()
            synchronized(io.stateLock) { io.advance() }
            val replacement = io.advance()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 300)
            assertFalse(io.current(old))
            assertTrue(io.current(replacement))
            assertFalse(done.await(20, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
        }
        assertTrue(done.await(1, TimeUnit.SECONDS))
    }

    @Test fun metadataHeldNativeEntryIsRejectedRatherThanCallingVendor() {
        val io = LocationSdkIo()
        synchronized(io.stateLock) {
            assertThrows(IllegalStateException::class.java) { io.native { fail("native") } }
            assertThrows(IllegalStateException::class.java) { io.enqueue("bad") { fail("native") } }
        }
    }

    @Test(timeout = 5000)
    fun blockedNativeUnregisterDoesNotPreventRetainingAnotherExactListener() {
        val cleanup = LocationListenerCleanup<Any>()
        val old = Any()
        val next = Any()
        cleanup.add(old)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        Thread {
            try {
                cleanup.prepare(true) {
                    entered.countDown()
                    release.await(3, TimeUnit.SECONDS)
                }
            } finally {
                done.countDown()
            }
        }.apply { isDaemon = true }
            .start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        try {
            val start = System.nanoTime()
            cleanup.add(next)
            assertTrue(cleanup.isNotEmpty())
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 300)
        } finally {
            release.countDown()
        }
        assertTrue(done.await(1, TimeUnit.SECONDS))
        val removed = mutableListOf<Any>()
        assertEquals(LocationRegistrationReadiness.READY, cleanup.prepare(true) { removed.add(it) })
        assertEquals(listOf(next), removed)
    }
}
