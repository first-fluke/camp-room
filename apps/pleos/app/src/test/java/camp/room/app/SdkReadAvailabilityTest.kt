package camp.room.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkReadAvailabilityTest {
    private class Clock(var now: Long = 0) {
        fun advance(millis: Long) { now += millis }
    }

    private val target = SdkReadKey(SdkReadOperation.GET_HVAC_TARGET, "0")
    private val current = SdkReadKey(SdkReadOperation.GET_HVAC_CURRENT, "0")

    private fun allowed(gate: SdkReadAvailability, key: SdkReadKey = target, epoch: Int = 0): SdkReadAttempt {
        val admission = gate.begin(key, epoch)
        assertTrue("Expected admission, received $admission", admission is SdkReadAdmission.Allowed)
        return (admission as SdkReadAdmission.Allowed).attempt
    }

    @Test
    fun failureOfOneOperationDoesNotSuppressAnother() {
        val clock = Clock()
        val gate = SdkReadAvailability({ clock.now })
        assertTrue(gate.finish(allowed(gate), SdkReadOutcome.CALLBACK_ERROR))

        assertEquals(SdkReadAdmission.Deferred("backoff", 2_000), gate.begin(target, 0))
        assertTrue(gate.begin(current, 0) is SdkReadAdmission.Allowed)
    }

    @Test
    fun failureOfOneAreaDoesNotSuppressAnotherAreaOfTheSameOperation() {
        val gate = SdkReadAvailability({ 0 })
        gate.finish(allowed(gate), SdkReadOutcome.EMPTY)

        val anotherArea = target.copy(context = "1")
        assertTrue(gate.begin(anotherArea, 0) is SdkReadAdmission.Allowed)
    }

    @Test
    fun temporaryEmptyResponseRetriesAndRecoversWithoutPermanentUnsupportedState() {
        val clock = Clock()
        val gate = SdkReadAvailability({ clock.now })
        gate.finish(allowed(gate), SdkReadOutcome.EMPTY)
        assertEquals(SdkReadOutcome.EMPTY, gate.state(target, 0)?.lastOutcome)
        assertEquals(1, gate.state(target, 0)?.consecutiveFailures)

        clock.advance(1_999)
        assertEquals(SdkReadAdmission.Deferred("backoff", 1), gate.begin(target, 0))
        clock.advance(1)
        assertTrue(gate.finish(allowed(gate), SdkReadOutcome.SUCCESS))
        assertEquals(SdkReadState(SdkReadOutcome.SUCCESS, 0, 0, false), gate.state(target, 0))
        assertTrue(gate.begin(target, 0) is SdkReadAdmission.Allowed)
    }

    @Test
    fun invalidValueIsNotSuccessAndDoesNotSuppressAnotherKey() {
        val gate = SdkReadAvailability({ 0 })
        gate.finish(allowed(gate), SdkReadOutcome.INVALID_VALUE)

        assertEquals(SdkReadState(SdkReadOutcome.INVALID_VALUE, 1, 2_000, false), gate.state(target, 0))
        assertTrue(gate.begin(current, 0) is SdkReadAdmission.Allowed)
    }

    @Test
    fun backoffGrowsExponentiallyButRemainsCappedAndRetryable() {
        val clock = Clock()
        val gate = SdkReadAvailability({ clock.now })
        for (delay in listOf(2_000L, 4_000L, 8_000L, 15_000L, 15_000L)) {
            assertTrue(gate.finish(allowed(gate), SdkReadOutcome.TIMEOUT))
            assertEquals(delay, gate.state(target, 0)?.retryAfterMillis)
            clock.advance(delay)
        }
        assertTrue(gate.finish(allowed(gate), SdkReadOutcome.SUCCESS))
        assertEquals(0, gate.state(target, 0)?.consecutiveFailures)
    }

    @Test
    fun successResetsTheNextFailureToBaseBackoff() {
        val clock = Clock()
        val gate = SdkReadAvailability({ clock.now }, baseBackoffMillis = 50, maxBackoffMillis = 150)
        gate.finish(allowed(gate), SdkReadOutcome.CALLBACK_ERROR)
        clock.advance(50)
        gate.finish(allowed(gate), SdkReadOutcome.SYNCHRONOUS_ERROR)
        clock.advance(100)
        gate.finish(allowed(gate), SdkReadOutcome.SUCCESS)
        gate.finish(allowed(gate), SdkReadOutcome.EMPTY)

        assertEquals(50L, gate.state(target, 0)?.retryAfterMillis)
        assertEquals(1, gate.state(target, 0)?.consecutiveFailures)
    }

    @Test
    fun allUnavailableOutcomesProduceTemporaryBackoff() {
        for (outcome in listOf(
            SdkReadOutcome.EMPTY,
            SdkReadOutcome.INVALID_VALUE,
            SdkReadOutcome.CALLBACK_ERROR,
            SdkReadOutcome.SYNCHRONOUS_ERROR,
            SdkReadOutcome.TIMEOUT,
        )) {
            val clock = Clock()
            val gate = SdkReadAvailability({ clock.now })
            assertTrue(gate.finish(allowed(gate), outcome))
            assertEquals(SdkReadAdmission.Deferred("backoff", 2_000), gate.begin(target, 0))
            clock.advance(2_000)
            assertTrue(gate.begin(target, 0) is SdkReadAdmission.Allowed)
        }
    }

    @Test
    fun duplicateAndSupersededCompletionsCannotChangeTheCurrentAttempt() {
        val gate = SdkReadAvailability({ 0 })
        val first = allowed(gate)
        assertEquals(SdkReadAdmission.Deferred("in_flight", 0), gate.begin(target, 0))
        assertTrue(gate.finish(first, SdkReadOutcome.SUCCESS))
        assertFalse(gate.finish(first, SdkReadOutcome.CALLBACK_ERROR))
        val second = allowed(gate)

        assertNotEquals(first.id, second.id)
        assertFalse(gate.finish(first, SdkReadOutcome.EMPTY))
        assertTrue(gate.state(target, 0)?.inFlight == true)
        assertTrue(gate.finish(second, SdkReadOutcome.SUCCESS))
        assertEquals(0, gate.state(target, 0)?.consecutiveFailures)
    }

    @Test
    fun resetRejectsOldEpochCompletionsAndAllowsTheNewEpoch() {
        val gate = SdkReadAvailability({ 0 })
        val old = allowed(gate)
        gate.reset(1)
        val fresh = allowed(gate, epoch = 1)

        assertFalse(gate.finish(old, SdkReadOutcome.CALLBACK_ERROR))
        assertEquals(SdkReadAdmission.Deferred("stale_epoch", 0), gate.begin(current, 0))
        assertNull(gate.state(target, 0))
        assertTrue(gate.finish(fresh, SdkReadOutcome.SUCCESS))
        assertEquals(SdkReadOutcome.SUCCESS, gate.state(target, 1)?.lastOutcome)
    }

    @Test
    fun resettingTheSameEpochStillInvalidatesOutstandingAttempts() {
        val gate = SdkReadAvailability({ 0 })
        val old = allowed(gate)
        gate.reset(0)
        val fresh = allowed(gate)

        assertNotEquals(old.id, fresh.id)
        assertFalse(gate.finish(old, SdkReadOutcome.CALLBACK_ERROR))
        assertTrue(gate.state(target, 0)?.inFlight == true)
        assertTrue(gate.finish(fresh, SdkReadOutcome.SUCCESS))
    }

    @Test
    fun cancellationDoesNotCountAsFailureOrDelayTheNextRead() {
        val gate = SdkReadAvailability({ 0 })
        val attempt = allowed(gate)
        assertTrue(gate.finish(attempt, SdkReadOutcome.CANCELLED))

        assertEquals(SdkReadState(SdkReadOutcome.CANCELLED, 0, 0, false), gate.state(target, 0))
        assertTrue(gate.begin(target, 0) is SdkReadAdmission.Allowed)
        assertFalse(gate.finish(attempt, SdkReadOutcome.CALLBACK_ERROR))
    }

    @Test
    fun cancellationDoesNotIncreaseAnExistingFailureCount() {
        val clock = Clock()
        val gate = SdkReadAvailability({ clock.now })
        gate.finish(allowed(gate), SdkReadOutcome.EMPTY)
        clock.advance(2_000)
        gate.finish(allowed(gate), SdkReadOutcome.CANCELLED)

        assertEquals(SdkReadState(SdkReadOutcome.CANCELLED, 1, 0, false), gate.state(target, 0))
        assertTrue(gate.begin(target, 0) is SdkReadAdmission.Allowed)
    }

    @Test
    fun concurrentBeginsAdmitOnlyOneAttemptForTheSameKey() {
        val gate = SdkReadAvailability({ 0 })
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = (1..8).map {
                executor.submit<SdkReadAdmission> {
                    check(start.await(5, TimeUnit.SECONDS))
                    gate.begin(target, 0)
                }
            }
            start.countDown()
            val admissions = results.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, admissions.count { it is SdkReadAdmission.Allowed })
            assertEquals(7, admissions.count { it == SdkReadAdmission.Deferred("in_flight", 0) })
        } finally {
            executor.shutdownNow()
        }
    }
}

class SdkDestinationMatcherTest {
    private val request = SdkDestinationRequest("requested-destination", 37.5, 127.0)
    private val destination = SdkRouteDestinationPoint(request.poiId, request.latitude, request.longitude, true)
    private val origin = SdkDestinationRequest("origin", 0.0, 0.0)

    private fun northOfOrigin(metres: Double) = SdkRouteDestinationPoint(
        origin.poiId, metres / 6_371_008.8 * 180.0 / kotlin.math.PI, 0.0, true,
    )

    private fun match(
        points: List<SdkRouteDestinationPoint>,
        requested: SdkDestinationRequest = request,
        requestEpoch: Int = 3,
        responseEpoch: Int = 3,
    ): SdkDestinationMatch = SdkDestinationMatcher.match(requested, points, requestEpoch, responseEpoch)

    @Test
    fun exactDestinationMatchesEvenWithUnrelatedWaypoints() {
        val waypoint = SdkRouteDestinationPoint("waypoint", 35.0, 129.0, false)
        assertEquals(SdkDestinationMatch.MATCH, match(listOf(waypoint, destination)))
    }

    @Test
    fun latitudeDeltasFromOneToElevenMetresMatch() {
        for (latitudeDelta in listOf(1e-5, 5e-5, 1e-4)) {
            val nearby = destination.copy(latitude = request.latitude + latitudeDelta)
            val distance = requireNotNull(SdkDestinationMatcher.distanceMeters(request, nearby))
            assertTrue(distance in 1.0..12.0)
            assertEquals(SdkDestinationMatch.MATCH, match(listOf(nearby)))
        }
    }

    @Test
    fun theTwentyMetreBoundaryIsInclusive() {
        val boundary = northOfOrigin(20.0)
        val distance = requireNotNull(SdkDestinationMatcher.distanceMeters(origin, boundary))
        assertEquals(20.0, distance, 1e-9)
        assertTrue(SdkDestinationMatcher.isWithinDestinationDistance(distance))
        assertEquals(SdkDestinationMatch.MATCH, match(listOf(boundary), requested = origin))
    }

    @Test
    fun distancePolicyAllowsOnlyTheNumericBoundaryEpsilon() {
        assertEquals(20.0, SdkDestinationMatcher.MAX_DESTINATION_DISTANCE_METERS, 0.0)
        assertTrue(SdkDestinationMatcher.isWithinDestinationDistance(0.0))
        assertTrue(SdkDestinationMatcher.isWithinDestinationDistance(20.0))
        assertTrue(SdkDestinationMatcher.isWithinDestinationDistance(20.0 + 0.5e-6))
        assertFalse(SdkDestinationMatcher.isWithinDestinationDistance(20.0 + 2e-6))
        for (invalid in listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFalse(SdkDestinationMatcher.isWithinDestinationDistance(invalid))
        }
    }

    @Test
    fun aDifferentPoiIdDoesNotMatchIdenticalCoordinates() {
        assertEquals(SdkDestinationMatch.MISMATCH, match(listOf(destination.copy(poiId = "different-destination"))))
        assertEquals(SdkDestinationMatch.MISMATCH, match(listOf(destination.copy(poiId = request.poiId.uppercase()))))
    }

    @Test
    fun aDestinationJustOutsideTwentyMetresIsRejected() {
        val outside = northOfOrigin(20.001)
        val distance = requireNotNull(SdkDestinationMatcher.distanceMeters(origin, outside))
        assertFalse(SdkDestinationMatcher.isWithinDestinationDistance(distance))
        assertEquals(SdkDestinationMatch.MISMATCH, match(listOf(outside), requested = origin))
    }

    @Test
    fun aDestinationOneHundredMetresAwayIsRejected() {
        val distant = northOfOrigin(100.0)
        val distance = requireNotNull(SdkDestinationMatcher.distanceMeters(origin, distant))
        assertEquals(100.0, distance, 1e-9)
        assertFalse(SdkDestinationMatcher.isWithinDestinationDistance(distance))
        assertEquals(SdkDestinationMatch.MISMATCH, match(listOf(distant), requested = origin))
    }

    @Test
    fun swappedLatitudeAndLongitudeDoNotMatch() {
        val ordered = SdkDestinationRequest("ordered-coordinates", 37.5, 27.0)
        val swapped = SdkRouteDestinationPoint(ordered.poiId, ordered.longitude, ordered.latitude, true)
        assertEquals(SdkDestinationMatch.MISMATCH, match(listOf(swapped), requested = ordered))
    }

    @Test
    fun nearbyPointsAcrossTheAntimeridianUseTheShortGeodesicDistance() {
        val east = SdkDestinationRequest("antimeridian", 0.0, 179.99995)
        val west = SdkRouteDestinationPoint(east.poiId, 0.0, -179.99995, true)
        val distance = requireNotNull(SdkDestinationMatcher.distanceMeters(east, west))
        assertTrue(distance in 11.0..12.0)
        assertEquals(SdkDestinationMatch.MATCH, match(listOf(west), requested = east))
    }

    @Test
    fun matchingOnlyAWaypointDoesNotConfirmTheDestination() {
        assertEquals(SdkDestinationMatch.MISSING_DESTINATION, match(listOf(destination.copy(isDestination = false))))
        assertEquals(SdkDestinationMatch.MISSING_DESTINATION, match(emptyList()))
    }

    @Test
    fun aMatchingWaypointCannotHideAMismatchingDestination() {
        val waypoint = destination.copy(isDestination = false)
        val actual = destination.copy(poiId = "other-destination")
        assertEquals(SdkDestinationMatch.MISMATCH, match(listOf(waypoint, actual)))
    }

    @Test
    fun multipleDestinationPointsAreRejectedEvenWhenBothMatch() {
        assertEquals(SdkDestinationMatch.AMBIGUOUS_DESTINATION, match(listOf(destination, destination)))
    }

    @Test
    fun aStaleResponseCannotConfirmAnOtherwiseMatchingDestination() {
        assertEquals(SdkDestinationMatch.STALE, match(listOf(destination), requestEpoch = 4, responseEpoch = 3))
    }

    @Test
    fun invalidRequestsAreRejectedBeforeDestinationMatching() {
        val invalid = listOf(
            request.copy(poiId = "  "),
            request.copy(latitude = Double.NaN),
            request.copy(latitude = 90.01),
            request.copy(longitude = Double.POSITIVE_INFINITY),
            request.copy(longitude = -180.01),
        )
        for (candidate in invalid) {
            assertEquals(SdkDestinationMatch.INVALID_REQUEST, match(listOf(destination), requested = candidate))
        }
    }

    @Test
    fun invalidReturnedCoordinatesAreMismatches() {
        val invalid = listOf(
            destination.copy(latitude = Double.NaN),
            destination.copy(longitude = Double.NEGATIVE_INFINITY),
            destination.copy(latitude = -90.01),
            destination.copy(longitude = 180.01),
        )
        for (candidate in invalid) {
            assertNull(SdkDestinationMatcher.distanceMeters(request, candidate))
            assertEquals(SdkDestinationMatch.MISMATCH, match(listOf(candidate)))
        }
    }

    @Test
    fun distanceIsUnavailableForInvalidRequestCoordinates() {
        val invalid = listOf(
            request.copy(latitude = Double.NaN),
            request.copy(latitude = 90.01),
            request.copy(longitude = Double.POSITIVE_INFINITY),
            request.copy(longitude = -180.01),
        )
        for (candidate in invalid) assertNull(SdkDestinationMatcher.distanceMeters(candidate, destination))
    }

    @Test
    fun aStaleInvalidRequestIsReportedAsStale() {
        assertEquals(SdkDestinationMatch.STALE, match(emptyList(), request.copy(poiId = ""), requestEpoch = 2, responseEpoch = 3))
    }
}
