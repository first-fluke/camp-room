package camp.room.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationResultObserverTest {
    private class Clock(var now: Long = 100_000L) {
        var onNextRead: (() -> Unit)? = null

        fun read(): Long {
            val action = onNextRead
            onNextRead = null
            action?.invoke()
            return now
        }
    }

    private val request = SdkDestinationRequest("requested-destination", 37.5, 127.0)
    private val epoch = 7

    private fun assertPublishableUntilOwnerFinishes(state: NavigationRequestState, token: NavigationRequestToken) {
        assertTrue(state.canPublishClaimedResult(token, epoch))
        state.finish(token)
        assertFalse(state.canPublishClaimedResult(token, epoch))
    }

    @Test
    fun precompletedAcceptedResultKeepsItsClaimWithoutAnObservationWindow() = runBlocking {
        val clock = Clock()
        val state = NavigationRequestState(clock::read)
        val token = state.begin(NavigationRequestKind.PREVIEW, request, epoch)
        val result = CompletableDeferred<String?>()
        val lock = Any()
        synchronized(lock) {
            clock.now += 1_000L
            assertEquals(NavigationCallbackAdmission.CURRENT, state.claim(token, epoch))
            assertTrue(result.complete("already received"))
        }
        // Timely receipt stays eligible even when observation resumes after the deadline.
        clock.now += 20_000L
        var windows = 0
        var extensions = 0
        val observer = NavigationResultObserver(result, token, state, lock, { epoch },
            onExtended = { _, _ -> extensions++ },
            awaitWindow = { _, _ -> windows++ })

        assertEquals("already received", observer.await())
        assertEquals(0, windows)
        assertEquals(0, extensions)
        assertPublishableUntilOwnerFinishes(state, token)
    }

    @Test
    fun previewExtendsTheSamePendingResultAndAcceptsItsCallbackAtFiveAndAHalfSeconds() = runBlocking {
        val clock = Clock()
        val state = NavigationRequestState(clock::read)
        val token = state.begin(NavigationRequestKind.PREVIEW, request, epoch)
        val result = CompletableDeferred<String?>()
        val lock = Any()
        val windows = mutableListOf<Long>()
        val observedResults = mutableListOf<CompletableDeferred<String?>>()
        val extensions = mutableListOf<Pair<Long?, Long>>()
        val observer = NavigationResultObserver(result, token, state, lock, { epoch },
            onExtended = { elapsed, remaining -> extensions += elapsed to remaining },
            awaitWindow = { observed, millis ->
                windows += millis
                observedResults += observed
                when (windows.size) {
                    1 -> {
                        clock.now += 5_000L
                        assertFalse(observed.isCompleted)
                    }
                    2 -> synchronized(lock) {
                        clock.now += 500L
                        assertEquals(NavigationCallbackAdmission.CURRENT, state.claim(token, epoch))
                        assertTrue(observed.complete("accepted trip"))
                    }
                    else -> throw AssertionError("A request must have at most two observation windows")
                }
            })

        assertEquals("accepted trip", observer.await())
        assertEquals(listOf(5_000L, 10_000L), windows)
        assertEquals(listOf(5_000L to 10_000L), extensions)
        assertEquals(2, observedResults.size)
        observedResults.forEach { assertSame(result, it) }
        assertEquals(NavigationCallbackAdmission.TERMINAL, state.admission(token, epoch))
        assertPublishableUntilOwnerFinishes(state, token)
    }

    @Test
    fun completedClaimBetweenTheInitialWaitAndRemainingCalculationIsRecovered() = runBlocking {
        val clock = Clock()
        val state = NavigationRequestState(clock::read)
        val token = state.begin(NavigationRequestKind.PREVIEW, request, epoch)
        val result = CompletableDeferred<String?>()
        val lock = Any()
        val windows = mutableListOf<Long>()
        var extensions = 0
        var racedCallback = false
        val observer = NavigationResultObserver(result, token, state, lock, { epoch },
            onExtended = { _, _ -> extensions++ },
            awaitWindow = { observed, millis ->
                windows += millis
                assertSame(result, observed)
                assertEquals(1, windows.size)
                clock.now += 5_000L
                // The next window reads the injected clock after the initial wait returns.
                // Clear the one-shot hook before claim re-enters the same clock/state guards.
                clock.onNextRead = {
                    synchronized(lock) {
                        racedCallback = true
                        assertEquals(NavigationCallbackAdmission.CURRENT, state.claim(token, epoch))
                        assertTrue(result.complete("claimed between windows"))
                    }
                }
            })

        assertEquals("claimed between windows", observer.await())
        assertTrue(racedCallback)
        assertEquals(listOf(5_000L), windows)
        assertEquals(0, extensions)
        assertPublishableUntilOwnerFinishes(state, token)
    }

    @Test
    fun finalWaitTimeoutCannotDiscardAResultClaimedBeforeTheHardDeadline() = runBlocking {
        val clock = Clock()
        val state = NavigationRequestState(clock::read)
        val token = state.begin(NavigationRequestKind.PREVIEW, request, epoch)
        val result = CompletableDeferred<String?>()
        val lock = Any()
        val windows = mutableListOf<Long>()
        val observer = NavigationResultObserver(result, token, state, lock, { epoch },
            awaitWindow = { observed, millis ->
                windows += millis
                assertSame(result, observed)
                when (windows.size) {
                    1 -> clock.now += 5_000L
                    2 -> {
                        synchronized(lock) {
                            clock.now += 9_999L
                            assertEquals(NavigationCallbackAdmission.CURRENT, state.claim(token, epoch))
                            assertTrue(observed.complete("claimed before deadline"))
                        }
                        // Returning without awaiting models a timeout that won dispatch of await's resume.
                        clock.now++
                    }
                    else -> throw AssertionError("Unexpected additional observation window")
                }
            })

        assertEquals("claimed before deadline", observer.await())
        assertEquals(listOf(5_000L, 10_000L), windows)
        assertEquals(15_000L, state.elapsedMillis(token))
        assertEquals(NavigationCallbackAdmission.TERMINAL, state.admission(token, epoch))
        assertPublishableUntilOwnerFinishes(state, token)
    }

    @Test
    fun actualCoroutineCancellationPropagatesEvenWhenAResultHasCompleted() = runBlocking {
        val clock = Clock()
        val state = NavigationRequestState(clock::read)
        val token = state.begin(NavigationRequestKind.PREVIEW, request, epoch)
        val result = CompletableDeferred<String?>()
        val lock = Any()
        var windows = 0
        var extensions = 0
        var returned = false
        val observer = NavigationResultObserver(result, token, state, lock, { epoch },
            onExtended = { _, _ -> extensions++ },
            awaitWindow = { observed, _ ->
                windows++
                synchronized(lock) {
                    clock.now += 1_000L
                    assertEquals(NavigationCallbackAdmission.CURRENT, state.claim(token, epoch))
                    observed.complete("must not mask cancellation")
                }
                currentCoroutineContext().cancel(CancellationException("cancel observation"))
            })
        val operation = async { observer.await().also { returned = true } }

        try {
            operation.await()
            throw AssertionError("Cancellation must propagate")
        } catch (cancelled: CancellationException) {
            assertEquals("cancel observation", cancelled.message)
        }
        assertTrue(operation.isCancelled)
        assertFalse(returned)
        assertEquals(1, windows)
        assertEquals(0, extensions)
        assertEquals(NavigationCallbackAdmission.TERMINAL, state.claim(token, epoch))
        assertFalse(state.canPublishClaimedResult(token, epoch))
    }

    @Test
    fun cancellingAPendingObservationClosesCallbackAdmission() = runBlocking {
        val clock = Clock()
        val state = NavigationRequestState(clock::read)
        val token = state.begin(NavigationRequestKind.PREVIEW, request, epoch)
        val result = CompletableDeferred<String?>()
        val lock = Any()
        var extensions = 0
        var returned = false
        val observer = NavigationResultObserver(result, token, state, lock, { epoch },
            onExtended = { _, _ -> extensions++ },
            awaitWindow = { _, _ ->
                currentCoroutineContext().cancel(CancellationException("cancel pending observation"))
            })
        val operation = async { observer.await().also { returned = true } }

        try {
            operation.await()
            throw AssertionError("Cancellation must propagate")
        } catch (cancelled: CancellationException) {
            assertEquals("cancel pending observation", cancelled.message)
        }
        assertTrue(operation.isCancelled)
        assertFalse(returned)
        assertFalse(result.isCompleted)
        assertEquals(0, extensions)
        assertEquals(NavigationCallbackAdmission.TERMINAL, state.claim(token, epoch))
        assertFalse(state.canPublishClaimedResult(token, epoch))
    }

    @Test
    fun exceptionalExitRevokesAClaimEvenWhenTheResultCompletedBeforeTheError() = runBlocking {
        val clock = Clock()
        val state = NavigationRequestState(clock::read)
        val token = state.begin(NavigationRequestKind.PREVIEW, request, epoch)
        val result = CompletableDeferred<String?>()
        val lock = Any()
        val failure = IllegalStateException("observation failed")
        var extensions = 0
        val observer = NavigationResultObserver(result, token, state, lock, { epoch },
            onExtended = { _, _ -> extensions++ },
            awaitWindow = { observed, _ ->
                synchronized(lock) {
                    clock.now += 1_000L
                    assertEquals(NavigationCallbackAdmission.CURRENT, state.claim(token, epoch))
                    observed.complete("must not survive an exceptional exit")
                }
                throw failure
            })

        try {
            observer.await()
            throw AssertionError("The observation error must propagate")
        } catch (error: IllegalStateException) {
            assertSame(failure, error)
        }
        assertTrue(result.isCompleted)
        assertEquals(0, extensions)
        assertEquals(NavigationCallbackAdmission.TERMINAL, state.claim(token, epoch))
        assertFalse(state.canPublishClaimedResult(token, epoch))
    }

    @Test
    fun completedNullServiceFailureStopsWithoutAnExtension() = runBlocking {
        val clock = Clock()
        val state = NavigationRequestState(clock::read)
        val token = state.begin(NavigationRequestKind.PREVIEW, request, epoch)
        val result = CompletableDeferred<String?>()
        val lock = Any()
        var windows = 0
        var extensions = 0
        val observer = NavigationResultObserver(result, token, state, lock, { epoch },
            onExtended = { _, _ -> extensions++ },
            awaitWindow = { observed, millis ->
                windows++
                assertEquals(5_000L, millis)
                synchronized(lock) {
                    clock.now += 1_000L
                    assertEquals(NavigationCallbackAdmission.CURRENT, state.claim(token, epoch))
                    assertTrue(observed.complete(null))
                }
            })

        assertNull(observer.await())
        assertTrue(result.isCompleted)
        assertEquals(1, windows)
        assertEquals(0, extensions)
        assertPublishableUntilOwnerFinishes(state, token)
    }

    @Test
    fun previewBeyondFifteenSecondsRejectsTheCallbackAndNeverAddsAThirdWindow() = runBlocking {
        val clock = Clock()
        val state = NavigationRequestState(clock::read)
        val token = state.begin(NavigationRequestKind.PREVIEW, request, epoch)
        val result = CompletableDeferred<String?>()
        val lock = Any()
        val windows = mutableListOf<Long>()
        var extensions = 0
        var lateAdmission: NavigationCallbackAdmission? = null
        val observer = NavigationResultObserver(result, token, state, lock, { epoch },
            onExtended = { _, _ -> extensions++ },
            awaitWindow = { observed, millis ->
                windows += millis
                assertSame(result, observed)
                when (windows.size) {
                    1 -> clock.now += 5_000L
                    2 -> synchronized(lock) {
                        clock.now += 10_001L
                        lateAdmission = state.claim(token, epoch)
                        if (lateAdmission == NavigationCallbackAdmission.CURRENT) observed.complete("too late")
                    }
                    else -> throw AssertionError("A request must not be retried or observed a third time")
                }
            })

        assertNull(observer.await())
        assertEquals(NavigationCallbackAdmission.EXPIRED, lateAdmission)
        assertFalse(result.isCompleted)
        assertEquals(listOf(5_000L, 10_000L), windows)
        assertEquals(1, extensions)
        assertFalse(state.canPublishClaimedResult(token, epoch))
    }

    @Test
    fun startStopsAtFiveSecondsWithoutUsingThePreviewExtension() = runBlocking {
        val clock = Clock()
        val state = NavigationRequestState(clock::read)
        val token = state.begin(NavigationRequestKind.START, request, epoch)
        val result = CompletableDeferred<Boolean?>()
        val lock = Any()
        val windows = mutableListOf<Long>()
        var extensions = 0
        val observer = NavigationResultObserver(result, token, state, lock, { epoch },
            onExtended = { _, _ -> extensions++ },
            awaitWindow = { observed, millis ->
                windows += millis
                assertSame(result, observed)
                clock.now += millis
                assertEquals(NavigationCallbackAdmission.EXPIRED, state.claim(token, epoch))
            })

        assertNull(observer.await())
        assertEquals(listOf(5_000L), windows)
        assertEquals(0, extensions)
        assertEquals(NavigationCallbackAdmission.TERMINAL, state.claim(token, epoch))
        assertFalse(result.isCompleted)
        assertFalse(state.canPublishClaimedResult(token, epoch))
    }
}
