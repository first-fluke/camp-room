package camp.room.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CampQueuedPreviewAdmissionTest {
    private fun key(id: String) = CampTripReadKey(id, "2026-11-07", "2026-11-08", 2)

    @Test fun queuedPreviewAfterStopReconnectAndNewTripDispatchesZeroSdkCalls() =
        runBlocking {
            val admission = CampObservationAdmission()
            val trips = CampTripReadRequests()
            admission.onHostStart()
            admission.onUxAvailabilityChanged(true)
            val observation = requireNotNull(admission.capture())
            var currentTrip = key("site-a")
            val request = trips.begin(currentTrip)
            val admitted = { admission.isCurrent(observation) && trips.isCurrent(request, currentTrip) }
            val queue = Mutex(locked = true)
            var sdkCalls = 0
            val preview =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withAdmittedObservation(queue, admitted, { "unavailable" }) {
                        assertFalse(Thread.holdsLock(admission))
                        assertFalse(Thread.holdsLock(trips))
                        sdkCalls++
                        "route"
                    }
                }
            assertFalse(preview.isCompleted)
            admission.onHostStop()
            admission.onHostStart()
            admission.onUxAvailabilityChanged(true)
            currentTrip = key("site-b")
            trips.begin(currentTrip)
            queue.unlock()
            assertEquals("unavailable", preview.await())
            assertEquals(0, sdkCalls)
        }

    @Test fun queuedOldTripIsRejectedEvenWhenSdkObservationEpochHasNotChanged() =
        runBlocking {
            val admission = CampObservationAdmission()
            val trips = CampTripReadRequests()
            admission.onHostStart()
            admission.onUxAvailabilityChanged(true)
            val observation = requireNotNull(admission.capture())
            var currentTrip = key("site-a")
            val request = trips.begin(currentTrip)
            val queue = Mutex(locked = true)
            var sdkCalls = 0
            val preview =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withAdmittedObservation(
                        queue,
                        { admission.isCurrent(observation) && trips.isCurrent(request, currentTrip) },
                        { "unavailable" },
                    ) {
                        sdkCalls++
                        "route"
                    }
                }
            currentTrip = key("site-b")
            trips.begin(currentTrip)
            queue.unlock()
            assertEquals("unavailable", preview.await())
            assertEquals(0, sdkCalls)
        }

    @Test fun tripChangeWhileBindingAwaitsPreventsTheLaterPreviewDispatch() =
        runBlocking {
            val admission = CampObservationAdmission()
            val trips = CampTripReadRequests()
            admission.onHostStart()
            admission.onUxAvailabilityChanged(true)
            val observation = requireNotNull(admission.capture())
            var currentTrip = key("site-a")
            val request = trips.begin(currentTrip)
            val admitted = { admission.isCurrent(observation) && trips.isCurrent(request, currentTrip) }
            val binding = CompletableDeferred<Unit>()
            var connectCalls = 0
            var previewCalls = 0
            val preview =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withAdmittedObservation(Mutex(), admitted, { "unavailable" }) {
                        connectCalls++ // This request was admitted before the trip changed.
                        binding.await()
                        if (!observationStillAdmitted(admitted)) {
                            "unavailable"
                        } else {
                            previewCalls++
                            "route"
                        }
                    }
                }
            currentTrip = key("site-b")
            trips.begin(currentTrip)
            binding.complete(Unit)
            assertEquals("unavailable", preview.await())
            assertEquals(1, connectCalls)
            assertEquals(0, previewCalls)
        }
}
