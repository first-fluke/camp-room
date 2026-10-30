package camp.room.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThat
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SdkTraceIdentityTest {
    @Test fun sameLocalRequestAndEpochAcrossOwnersOrProcessesNeverShareAKey() {
        val identity = SdkTraceIdentity("process-a")
        val a = SdkTraceContext(identity.owner("vehicle"), 1, 1, "GET_HVAC_TARGET")
        val b = SdkTraceContext(identity.owner("vehicle"), 1, 1, "GET_HVAC_TARGET")
        val otherProcess = SdkTraceContext(SdkTraceIdentity("process-b").owner("vehicle"), 1, 1, "GET_HVAC_TARGET")
        assertEquals(3, setOf(a.requestKey, b.requestKey, otherProcess.requestKey).size)
    }

    @Test fun capabilityAndSnapshotRequestOneHaveDistinctNamespaces() {
        val owner = SdkTraceIdentity("process").owner("adapter")
        val capability = SdkTraceContext(owner, 2, 1, "soc_capability")
        val snapshot = SdkTraceContext(owner, 2, 1, "soc_snapshot")
        assertNotEquals(capability.requestKey, snapshot.requestKey)
    }

    @Test fun delayedAndDuplicateCallbacksKeepTheOriginalContext() {
        val identity = SdkTraceIdentity("process")
        val owner = identity.owner("navigation")
        val old = SdkTraceContext(owner, 3, 9, "PREVIEW")
        val newer = SdkTraceContext(owner, 4, 10, "PREVIEW")
        val first = identity.stamp()
        val duplicate = identity.stamp()
        assertEquals("process:navigation:1:3:PREVIEW:9", old.requestKey)
        assertNotEquals(newer.requestKey, old.requestKey)
        assertNotEquals(first.sequence, duplicate.sequence)
        assertEquals(3, old.fields().toMap()["sessionEpoch"])
    }

    @Test fun unscopedCallbacksDoNotAcquireAFakeCurrentRequest() {
        val identity = SdkTraceIdentity("process")
        val rawConnectionCallback = SdkTraceContext(identity.owner("navigation"), 5, null)
        assertNull(rawConnectionCallback.requestKey)
        assertNull(rawConnectionCallback.fields().toMap()["requestId"])
    }

    @Test(timeout = 6000)
    fun concurrentOwnersAndEventsRemainUniqueWithoutUsingWallClock() {
        val identity = SdkTraceIdentity("process", { 7L })
        val owners = Collections.synchronizedSet(mutableSetOf<String>())
        val sequences = Collections.synchronizedSet(mutableSetOf<Long>())
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks =
                (1..4).map {
                    executor.submit {
                        check(start.await(2, TimeUnit.SECONDS))
                        repeat(100) {
                            owners += identity.owner("vehicle").id
                            val stamp = identity.stamp()
                            sequences += stamp.sequence
                            assertEquals(7L, stamp.elapsedMillis)
                        }
                    }
                }
            start.countDown()
            tasks.forEach { it.get(3, TimeUnit.SECONDS) }
            assertEquals(400, owners.size)
            assertEquals((1L..400L).toSet(), sequences)
        } finally {
            executor.shutdownNow()
        }
    }
}
