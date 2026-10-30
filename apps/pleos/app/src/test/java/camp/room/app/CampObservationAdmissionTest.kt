package camp.room.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake-only admission tests; no Android service, microphone, speaker or vehicle. */
class CampObservationAdmissionTest {
    private class FakeReads {
        val admission = CampObservationAdmission()
        var starts = 0
        var stops = 0
        var vehicleReads = 0
        var previews = 0

        fun transition(value: CampObservationAdmission.Transition) {
            when (value) {
                CampObservationAdmission.Transition.START -> starts++
                CampObservationAdmission.Transition.STOP -> stops++
                CampObservationAdmission.Transition.NONE -> Unit
            }
        }

        fun bootstrapVehicle(): String {
            val token = admission.capture() ?: return "unknown"
            vehicleReads++
            return if (admission.isCurrent(token)) "native" else "unknown"
        }

        fun preview() {
            val token = admission.capture() ?: return
            if (admission.isCurrent(token)) previews++
        }
    }

    @Test fun initAndBootstrapCannotAdmitNativeReadsBeforeHostAndUx() {
        val f = FakeReads()
        assertEquals("unknown", f.bootstrapVehicle())
        f.transition(f.admission.onUxAvailabilityChanged(true))
        assertEquals("unknown", f.bootstrapVehicle())
        assertEquals(0, f.starts)
        assertEquals(0, f.vehicleReads)
    }

    @Test fun foregroundWithUnknownOrRestrictedUxAdmitsNeitherNativeReadNorPreview() {
        val f = FakeReads()
        f.transition(f.admission.onHostStart())
        f.transition(f.admission.onUxAvailabilityChanged(false))
        assertEquals("unknown", f.bootstrapVehicle())
        f.preview()
        assertEquals(0, f.starts)
        assertEquals(0, f.vehicleReads)
        assertEquals(0, f.previews)
    }

    @Test fun duplicateStartAndUxSignalsDoNotRestartBridgeOrPolling() {
        val f = FakeReads()
        repeat(3) { f.transition(f.admission.onHostStart()) }
        repeat(3) { f.transition(f.admission.onUxAvailabilityChanged(true)) }
        assertEquals(1, f.starts)
        assertEquals("native", f.bootstrapVehicle())
        repeat(3) { f.transition(f.admission.onUxAvailabilityChanged(false)) }
        assertEquals(1, f.stops)
        assertNull(f.admission.capture())
    }

    @Test fun lateResultIsRejectedAfterUxLossWithoutClaimingNativeCancellation() =
        runBlocking {
            val f = FakeReads()
            f.admission.onHostStart()
            f.admission.onUxAvailabilityChanged(true)
            val token = requireNotNull(f.admission.capture())
            val response = CompletableDeferred<String>()
            val result =
                async {
                    val value = response.await()
                    if (f.admission.isCurrent(token)) value else "unknown"
                }
            f.admission.onUxAvailabilityChanged(false)
            response.complete("native") // The native operation is allowed to complete late.
            assertEquals("unknown", result.await())
            assertFalse(response.isCancelled)
        }

    @Test fun retainedViewModelStopStartRequiresFreshUxAndUsesANewReadEpoch() {
        val f = FakeReads()
        f.transition(f.admission.onHostStart())
        f.transition(f.admission.onUxAvailabilityChanged(true))
        val old = requireNotNull(f.admission.capture())
        assertEquals("native", f.bootstrapVehicle())
        f.transition(f.admission.onHostStop())
        f.transition(f.admission.onHostStart())
        assertNull(f.admission.capture())
        f.transition(f.admission.onUxAvailabilityChanged(true))
        val current = requireNotNull(f.admission.capture())
        assertNotSame(old, current)
        assertFalse(f.admission.isCurrent(old))
        assertTrue(f.admission.isCurrent(current))
        assertEquals("native", f.bootstrapVehicle())
        assertEquals(2, f.starts)
        assertEquals(1, f.stops)
        assertEquals(2, f.vehicleReads)
    }

    @Test fun queuedPreviewCannotUseAnOldEpochAfterStopAndReconnect() {
        val f = FakeReads()
        f.admission.onHostStart()
        f.admission.onUxAvailabilityChanged(true)
        val old = requireNotNull(f.admission.capture())
        f.admission.onHostStop()
        f.admission.onHostStart()
        f.admission.onUxAvailabilityChanged(true)
        if (f.admission.isCurrent(old)) f.previews++
        assertEquals(0, f.previews)
    }

    @Test fun lateOldBootstrapReadCannotEraseANewerFreshObservation() {
        val admission = CampObservationAdmission()
        admission.onHostStart()
        admission.onUxAvailabilityChanged(true)
        val old = requireNotNull(admission.capture())
        admission.onHostStop()
        admission.onHostStart()
        admission.onUxAvailabilityChanged(true)
        val current = requireNotNull(admission.capture())
        assertEquals("new fresh", admission.publication(old, "old late", "new fresh", "unknown"))
        assertEquals("current read", admission.publication(current, "current read", "previous", "unknown"))
        admission.onUxAvailabilityChanged(false)
        assertEquals("unknown", admission.publication(current, "late", "previous", "unknown"))
    }

    @Test fun disconnectedFallbackDoesNotPresentServerVehicleFactsAsNativeObservations() {
        val serverVehicle =
            Vehicle(
                soc = 78.0,
                measuredTemperature = 19.0,
                targetTemperature = 22.0,
                front = CabinZone(null, null, null),
                rear = CabinZone(null, null, null),
                doorsClosed = true,
                windowsClosed = true,
                motionState = "parked",
                sceneId = "server-scene",
                updatedAt = "2026-10-29T09:00:00Z",
                mode = "pleos",
                connectionConfirmed = true,
            )
        val unknown = serverVehicle.disconnected()
        assertNull(unknown.soc)
        assertNull(unknown.socObservation)
        assertFalse(unknown.isConnected)
        assertEquals("unknown", unknown.mode)
        assertEquals("unknown", unknown.motionState)
    }
}
