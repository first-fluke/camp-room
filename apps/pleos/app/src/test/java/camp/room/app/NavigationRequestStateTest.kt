package camp.room.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationRequestStateTest {
    private class Clock(var now: Long = 100_000L)

    private val request = SdkDestinationRequest("requested-destination", 37.5, 127.0)
    private val epoch = 7

    @Test
    fun previewHasOneFifteenSecondDeadlineAcrossTheInitialObservationBoundary() {
        val clock = Clock()
        val state = NavigationRequestState { clock.now }
        val token = state.begin(NavigationRequestKind.PREVIEW, request, epoch)

        assertEquals(5_000L, NavigationRequestState.INITIAL_OBSERVATION_MS)
        assertEquals(15_000L, NavigationRequestState.PREVIEW_MAX_OBSERVATION_MS)
        assertSame(request, token.request)
        assertEquals(epoch, token.connectionEpoch)
        assertEquals(NavigationRequestKind.PREVIEW, token.kind)
        assertEquals(15_000L, state.remainingMillis(token, epoch))
        clock.now += 5_000L
        assertEquals(NavigationCallbackAdmission.CURRENT, state.admission(token, epoch))
        assertEquals(5_000L, state.elapsedMillis(token))
        assertEquals(10_000L, state.remainingMillis(token, epoch))
        clock.now += 9_999L
        assertEquals(NavigationCallbackAdmission.CURRENT, state.admission(token, epoch))
        assertEquals(1L, state.remainingMillis(token, epoch))
        clock.now++
        assertEquals(NavigationCallbackAdmission.EXPIRED, state.admission(token, epoch))
        assertEquals(NavigationCallbackAdmission.EXPIRED, state.claim(token, epoch))
        assertEquals(0L, state.remainingMillis(token, epoch))
    }

    @Test
    fun previewCanClaimTheSameRequestAtFiveAndAHalfSeconds() {
        val clock = Clock()
        val state = NavigationRequestState { clock.now }
        val token = state.begin(NavigationRequestKind.PREVIEW, request, epoch)

        clock.now += 5_500L

        assertEquals(9_500L, state.remainingMillis(token, epoch))
        assertEquals(NavigationCallbackAdmission.CURRENT, state.claim(token, epoch))
        assertEquals(NavigationCallbackAdmission.TERMINAL, state.admission(token, epoch))
        assertEquals(5_500L, state.elapsedMillis(token))
        assertEquals(0L, state.remainingMillis(token, epoch))
    }

    @Test
    fun startExpiresAtExactlyFiveSecondsAndCannotBeClaimedLater() {
        val clock = Clock()
        val state = NavigationRequestState { clock.now }
        val token = state.begin(NavigationRequestKind.START, request, epoch)

        clock.now += 4_999L
        assertEquals(NavigationCallbackAdmission.CURRENT, state.admission(token, epoch))
        assertEquals(1L, state.remainingMillis(token, epoch))
        clock.now++
        assertEquals(NavigationCallbackAdmission.EXPIRED, state.admission(token, epoch))
        assertEquals(0L, state.remainingMillis(token, epoch))
        clock.now += 500L
        assertEquals(NavigationCallbackAdmission.EXPIRED, state.claim(token, epoch))
    }

    @Test
    fun aPreviewCallbackBeyondFifteenSecondsCannotCompleteTheRequest() {
        val clock = Clock()
        val state = NavigationRequestState { clock.now }
        val token = state.begin(NavigationRequestKind.PREVIEW, request, epoch)

        clock.now += 15_001L

        assertEquals(NavigationCallbackAdmission.EXPIRED, state.claim(token, epoch))
        assertEquals(15_001L, state.elapsedMillis(token))
        assertEquals(0L, state.remainingMillis(token, epoch))
    }

    @Test
    fun aSupersededCallbackForTheSamePoiAndEpochCannotFinishItsReplacement() {
        val state = NavigationRequestState { 0L }
        val old = state.begin(NavigationRequestKind.PREVIEW, request, epoch)
        val current = state.begin(NavigationRequestKind.PREVIEW, request.copy(), epoch)

        assertNotEquals(old.id, current.id)
        assertEquals(NavigationCallbackAdmission.STALE, state.admission(old, epoch))
        assertEquals(NavigationCallbackAdmission.STALE, state.claim(old, epoch))
        assertEquals(0L, state.remainingMillis(old, epoch))
        assertNull(state.elapsedMillis(old))
        state.finish(old)
        assertEquals(NavigationCallbackAdmission.CURRENT, state.claim(current, epoch))
    }

    @Test
    fun anEpochMismatchCannotClaimAnOtherwiseMatchingToken() {
        val state = NavigationRequestState { 0L }
        val token = state.begin(NavigationRequestKind.START, request, epoch)

        assertEquals(NavigationCallbackAdmission.STALE, state.admission(token, epoch + 1))
        assertEquals(NavigationCallbackAdmission.STALE, state.claim(token, epoch + 1))
        assertEquals(0L, state.remainingMillis(token, epoch + 1))
        assertEquals(NavigationCallbackAdmission.CURRENT, state.admission(token, epoch))
    }

    @Test
    fun onlyOneConcurrentCallbackCanClaimATerminalResult() {
        val state = NavigationRequestState { 0L }
        val token = state.begin(NavigationRequestKind.PREVIEW, request, epoch)
        val executor = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val claims = (1..8).map {
                executor.submit<NavigationCallbackAdmission> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    state.claim(token, epoch)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val results = claims.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it == NavigationCallbackAdmission.CURRENT })
            assertEquals(7, results.count { it == NavigationCallbackAdmission.TERMINAL })
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun finishIsIdempotentAndDuplicateCallbacksRemainTerminal() {
        val state = NavigationRequestState { 0L }
        val token = state.begin(NavigationRequestKind.START, request, epoch)

        state.finish(token)
        state.finish(token)

        assertEquals(NavigationCallbackAdmission.TERMINAL, state.admission(token, epoch))
        assertEquals(NavigationCallbackAdmission.TERMINAL, state.claim(token, epoch))
        assertEquals(0L, state.remainingMillis(token, epoch))
    }

    @Test
    fun claimedResultsCannotPublishAfterEpochChangeSupersessionOrInvalidation() {
        val state = NavigationRequestState { 0L }
        val old = state.begin(NavigationRequestKind.PREVIEW, request, epoch)
        assertEquals(NavigationCallbackAdmission.CURRENT, state.claim(old, epoch))
        assertTrue(state.canPublishClaimedResult(old, epoch))
        assertFalse(state.canPublishClaimedResult(old, epoch + 1))

        val current = state.begin(NavigationRequestKind.PREVIEW, request, epoch + 1)
        assertFalse(state.canPublishClaimedResult(old, epoch))
        assertEquals(NavigationCallbackAdmission.CURRENT, state.claim(current, epoch + 1))
        assertTrue(state.canPublishClaimedResult(current, epoch + 1))

        state.invalidate()
        assertFalse(state.canPublishClaimedResult(current, epoch + 1))
    }

    @Test
    fun finishingAfterAClaimRevokesItsPublicationPermission() {
        val state = NavigationRequestState { 0L }
        val token = state.begin(NavigationRequestKind.START, request, epoch)
        assertEquals(NavigationCallbackAdmission.CURRENT, state.claim(token, epoch))
        assertTrue(state.canPublishClaimedResult(token, epoch))

        state.finish(token)

        assertFalse(state.canPublishClaimedResult(token, epoch))
        assertEquals(NavigationCallbackAdmission.TERMINAL, state.admission(token, epoch))
    }

    @Test
    fun aPreviewClaimAdmittedBeforeItsDeadlineCanPublishAfterTheDeadline() {
        val clock = Clock()
        val state = NavigationRequestState { clock.now }
        val token = state.begin(NavigationRequestKind.PREVIEW, request, epoch)
        clock.now += 14_999L
        assertEquals(NavigationCallbackAdmission.CURRENT, state.claim(token, epoch))

        clock.now += 1_001L

        assertTrue(state.canPublishClaimedResult(token, epoch))
        assertEquals(NavigationCallbackAdmission.TERMINAL, state.admission(token, epoch))
        assertEquals(0L, state.remainingMillis(token, epoch))
    }

    @Test
    fun finishingWithoutAClaimDoesNotGrantPublicationPermission() {
        val state = NavigationRequestState { 0L }
        val token = state.begin(NavigationRequestKind.PREVIEW, request, epoch)
        assertFalse(state.canPublishClaimedResult(token, epoch))

        state.finish(token)

        assertFalse(state.canPublishClaimedResult(token, epoch))
        assertEquals(NavigationCallbackAdmission.TERMINAL, state.claim(token, epoch))
    }

    @Test
    fun invalidatingCancellationRejectsOldCallbacksAndOldFinishCannotEndANewRequest() {
        val state = NavigationRequestState { 0L }
        val cancelled = state.begin(NavigationRequestKind.PREVIEW, request, epoch)

        state.invalidate()
        state.invalidate()
        assertEquals(NavigationCallbackAdmission.STALE, state.admission(cancelled, epoch))
        assertEquals(NavigationCallbackAdmission.STALE, state.claim(cancelled, epoch))
        assertEquals(0L, state.remainingMillis(cancelled, epoch))
        assertNull(state.elapsedMillis(cancelled))

        val current = state.begin(NavigationRequestKind.START, request, epoch + 1)
        assertTrue(current.id > cancelled.id)
        state.finish(cancelled)
        assertEquals(NavigationCallbackAdmission.CURRENT, state.admission(current, epoch + 1))
    }

    @Test
    fun copyingAnOpaqueTokenCannotClaimOrFinishTheActiveRequest() {
        val state = NavigationRequestState { 0L }
        val token = state.begin(NavigationRequestKind.START, request, epoch)
        val copy = token.copy()

        assertEquals(token, copy)
        assertEquals(NavigationCallbackAdmission.STALE, state.claim(copy, epoch))
        state.finish(copy)
        assertNull(state.elapsedMillis(copy))
        assertEquals(0L, state.remainingMillis(copy, epoch))
        assertEquals(NavigationCallbackAdmission.CURRENT, state.claim(token, epoch))
    }

    @Test
    fun invalidDestinationValidationRemainsTheSharedMatchersResponsibility() {
        val state = NavigationRequestState { 0L }
        val invalid = request.copy(poiId = "", latitude = Double.NaN)
        val token = state.begin(NavigationRequestKind.PREVIEW, invalid, epoch)

        assertEquals(NavigationCallbackAdmission.CURRENT, state.admission(token, epoch))
        assertEquals(SdkDestinationMatch.INVALID_REQUEST,
            SdkDestinationMatcher.match(token.request, emptyList(), token.connectionEpoch, epoch))
    }

    @Test
    fun elapsedTimeClampsClockRegressionAndSaturatesOverflow() {
        val clock = Clock(10L)
        val state = NavigationRequestState { clock.now }
        val token = state.begin(NavigationRequestKind.PREVIEW, request, epoch)
        clock.now = 9L
        assertEquals(0L, state.elapsedMillis(token))
        assertEquals(15_000L, state.remainingMillis(token, epoch))

        clock.now = Long.MIN_VALUE
        val extreme = state.begin(NavigationRequestKind.START, request, epoch)
        clock.now = Long.MAX_VALUE
        assertEquals(Long.MAX_VALUE, state.elapsedMillis(extreme))
        assertEquals(NavigationCallbackAdmission.EXPIRED, state.admission(extreme, epoch))
        assertEquals(0L, state.remainingMillis(extreme, epoch))
    }

    private fun latitudeOffset(metres: Double): SdkRouteDestinationPoint = SdkRouteDestinationPoint(
        request.poiId,
        request.latitude + metres / 6_371_008.8 * 180.0 / kotlin.math.PI,
        request.longitude,
        true,
    )

    private fun match(kind: NavigationRequestKind, point: SdkRouteDestinationPoint): SdkDestinationMatch {
        val state = NavigationRequestState { 0L }
        val token = state.begin(kind, request, epoch)
        assertEquals(NavigationCallbackAdmission.CURRENT, state.admission(token, epoch))
        return SdkDestinationMatcher.match(token.request, listOf(point), token.connectionEpoch, epoch)
    }

    @Test
    fun previewAndStartBothMatchTheSamePoiAtPlusOrMinusFiveMetres() {
        for (kind in NavigationRequestKind.values()) {
            for (metres in listOf(-5.0, 5.0)) {
                assertEquals(SdkDestinationMatch.MATCH, match(kind, latitudeOffset(metres)))
            }
        }
    }

    @Test
    fun previewAndStartBothRejectADifferentPoiAtIdenticalCoordinates() {
        val otherPoi = latitudeOffset(0.0).copy(poiId = "other-destination")
        for (kind in NavigationRequestKind.values()) {
            assertEquals(SdkDestinationMatch.MISMATCH, match(kind, otherPoi))
        }
    }

    @Test
    fun previewAndStartBothRejectTheSamePoiBeyondTwentyMetres() {
        for (kind in NavigationRequestKind.values()) {
            for (metres in listOf(-20.01, 20.01, 100.0)) {
                assertEquals(SdkDestinationMatch.MISMATCH, match(kind, latitudeOffset(metres)))
            }
        }
    }
}
