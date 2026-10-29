package camp.room.app

import org.junit.Assert.*
import org.junit.Test

class VehicleListenerCleanupTest {
    @Test fun failedUnregisterRetainsExactIdentityAndBlocksNewNativeRegistrationUntilRecovery() {
        var now = 0L
        val cleanup = VehicleListenerCleanup<Any> { now }
        val old = Any()
        val attempted = mutableListOf<Any>()
        var nativeRegistrations = 1
        cleanup.retain(old)
        cleanup.retain(old)
        assertFalse(cleanup.drain({ attempted += it; throw IllegalStateException("temporary failure") }, {}))
        if (!cleanup.hasPending()) nativeRegistrations++
        assertEquals(1, nativeRegistrations)
        assertFalse(cleanup.drain({ fail("backoff must suppress IPC") }, {}))
        now = 2000
        assertTrue(cleanup.drain({ attempted += it }, {}))
        if (!cleanup.hasPending()) nativeRegistrations++
        assertEquals(2, nativeRegistrations)
        assertEquals(2, attempted.size)
        assertTrue(attempted.all { it === old })
    }

    @Test fun fiveCleanupFailuresRemainPendingWithoutSpinningOrDroppingIdentity() {
        var now = 0L
        val cleanup = VehicleListenerCleanup<Any> { now }
        val listener = Any()
        cleanup.retain(listener)
        var calls = 0
        for (delay in listOf(2000L, 4000L, 8000L, 15000L, 15000L)) {
            assertFalse(cleanup.drain({ assertSame(listener, it); calls++; throw IllegalStateException("test") }, {}))
            now += delay
        }
        now += 100000
        repeat(20) { assertFalse(cleanup.drain({ calls++ }, {})) }
        assertEquals(5, calls)
        assertTrue(cleanup.hasPending())
    }

    @Test fun equalityCannotSubstituteAnotherListenerIdentity() {
        data class Listener(val value: Int)
        val first = Listener(1)
        val second = Listener(1)
        val cleanup = VehicleListenerCleanup<Listener>()
        cleanup.retain(first)
        cleanup.retain(second)
        val seen = mutableListOf<Listener>()
        assertTrue(cleanup.drain({ seen += it }, {}))
        assertEquals(2, seen.size)
        assertSame(first, seen[0])
        assertSame(second, seen[1])
    }

    @Test fun throwingDiagnosticsCannotStrandOtherReservedExactListenersInFlight() {
        var now=0L
        val cleanup=VehicleListenerCleanup<Any>{now}
        val first=Any();val second=Any()
        cleanup.retain(first);cleanup.retain(second)
        val attempted=mutableListOf<Any>()
        assertFalse(cleanup.drain({listener->
            attempted.add(listener)
            if(listener===first)throw IllegalStateException("first native unregister failed")
        },{throw IllegalStateException("diagnostic sink unavailable")}))
        assertEquals(2,attempted.size)
        assertSame(first,attempted[0]);assertSame(second,attempted[1])
        now=2000
        assertTrue(cleanup.drain({listener->assertSame(first,listener);attempted.add(listener)},{}))
        assertEquals(3,attempted.size)
    }
}
