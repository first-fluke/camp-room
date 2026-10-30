package camp.room.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CampAdmittedObservationTest {
    @Test fun queuedOldReadDispatchesZeroNativeCallsAfterStopAndReconnect() =
        runBlocking {
            val admission = CampObservationAdmission()
            admission.onHostStart()
            admission.onUxAvailabilityChanged(true)
            val old = requireNotNull(admission.capture())
            val guard = Mutex(locked = true)
            val queued = CompletableDeferred<Unit>()
            var nativeCalls = 0
            val read =
                async(start = CoroutineStart.UNDISPATCHED) {
                    queued.complete(Unit)
                    withAdmittedObservation(guard, { admission.isCurrent(old) }, { "unknown" }) {
                        nativeCalls++
                        "native"
                    }
                }
            queued.await()
            assertFalse(read.isCompleted)
            admission.onUxAvailabilityChanged(false)
            admission.onHostStop()
            admission.onHostStart()
            admission.onUxAvailabilityChanged(true)
            guard.unlock()
            assertEquals("unknown", read.await())
            assertEquals(0, nativeCalls)
            val current = requireNotNull(admission.capture())
            val fresh =
                withAdmittedObservation(guard, { admission.isCurrent(current) }, { "unknown" }) {
                    assertFalse(Thread.holdsLock(admission))
                    nativeCalls++
                    "fresh"
                }
            assertEquals("fresh", fresh)
            assertEquals(1, nativeCalls)
        }

    @Test fun nestedDiscoveryQueueAlsoRechecksAfterOuterAdmissionWasGranted() =
        runBlocking {
            val admission = CampObservationAdmission()
            admission.onHostStart()
            admission.onUxAvailabilityChanged(true)
            val old = requireNotNull(admission.capture())
            val outer = Mutex()
            val discovery = Mutex(locked = true)
            val enteredDiscovery = CompletableDeferred<Unit>()
            var nativeCalls = 0
            val read =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withAdmittedObservation(outer, { admission.isCurrent(old) }, { "unknown" }) {
                        enteredDiscovery.complete(Unit)
                        withAdmittedObservation(discovery, { admission.isCurrent(old) }, { "unknown" }) {
                            assertFalse(Thread.holdsLock(admission))
                            nativeCalls++
                            "native"
                        }
                    }
                }
            enteredDiscovery.await()
            assertFalse(read.isCompleted)
            admission.onHostStop()
            admission.onHostStart()
            admission.onUxAvailabilityChanged(true)
            discovery.unlock()
            assertEquals("unknown", read.await())
            assertEquals(0, nativeCalls)
        }

    @Test fun queuedCoroutineCancellationNeverDispatchesNativeWork() =
        runBlocking {
            val guard = Mutex(locked = true)
            var nativeCalls = 0
            val read =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withAdmittedObservation(guard, { true }, { "unknown" }) {
                        nativeCalls++
                        "native"
                    }
                }
            read.cancel()
            guard.unlock()
            try {
                read.await()
                throw AssertionError("Expected cancellation")
            } catch (_: CancellationException) {
                assertTrue(read.isCancelled)
            }
            assertEquals(0, nativeCalls)
        }
}
