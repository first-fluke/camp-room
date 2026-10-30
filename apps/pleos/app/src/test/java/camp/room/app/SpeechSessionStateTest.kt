package camp.room.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class SpeechSessionStateTest {
    @Test fun playbackStartIsPendingAndLaterEngineErrorCannotBecomeSuccess() {
        val state = SpeechSessionState(SpeechKind.PLAYBACK)
        val token = state.begin()
        state.ready(token)
        state.started(token)
        assertTrue(state.isCurrent(token))
        assertEquals("ON_DEVICE_ENGINE_ERROR", state.fail(token, "ON_DEVICE_ENGINE_ERROR")?.errorCode)
        assertNull(state.playbackDone(token))
        assertFalse(state.isCurrent(token))
    }

    @Test fun playbackRequiresDoneAndTimeoutDoesNotReportCompletion() {
        val state = SpeechSessionState(SpeechKind.PLAYBACK)
        val first = state.begin()
        state.started(first)
        assertEquals(SpeechPhase.COMPLETED, state.playbackDone(first)?.phase)
        val second = state.begin()
        state.started(second)
        assertEquals(SpeechPhase.TIMED_OUT, state.timeout(second, "COMPLETION_TIMEOUT")?.phase)
        assertNull(state.playbackDone(second))
    }

    @Test fun documentedDoneIsCompletionEvenWhenProgressEventWasNotDelivered() {
        val state = SpeechSessionState(SpeechKind.PLAYBACK)
        assertEquals(SpeechPhase.COMPLETED, state.playbackDone(state.begin())?.phase)
    }

    @Test fun recognitionNeedsNonblankFinalText() {
        val state = SpeechSessionState(SpeechKind.RECOGNITION)
        val first = state.begin()
        state.ready(first)
        state.started(first)
        state.processing(first)
        assertNull(state.finalText(first, "interim", false))
        assertTrue(state.isCurrent(first))
        assertEquals("EMPTY_RESULT", state.finalText(first, "  ", true)?.errorCode)
        val second = state.begin()
        val result = state.finalText(second, "내 여행 보기", true)
        assertEquals(SpeechPhase.RECOGNIZED, result?.phase)
        assertEquals("내 여행 보기", result?.text)
    }

    @Test fun cancelledAndLateInitializationCannotAffectTheNextRequest() {
        val state = SpeechSessionState(SpeechKind.PLAYBACK)
        val first = state.begin()
        assertEquals(SpeechPhase.CANCELLED, state.cancel(first)?.phase)
        val second = state.begin()
        assertFalse(state.ready(first))
        assertFalse(state.started(first))
        assertNull(state.fail(first, "LATE_ERROR"))
        assertNull(state.cancel(first))
        assertTrue(state.isCurrent(second))
        state.started(second)
        assertEquals(SpeechPhase.COMPLETED, state.playbackDone(second)?.phase)
    }

    @Test fun racingTerminalCallbacksCompleteOnlyOnce() {
        val state = SpeechSessionState(SpeechKind.PLAYBACK)
        val token = state.begin()
        state.started(token)
        val start = CountDownLatch(1)
        val accepted = AtomicInteger()
        val threads =
            listOf(
                Thread {
                    start.await()
                    if (state.playbackDone(token) != null) accepted.incrementAndGet()
                },
                Thread {
                    start.await()
                    if (state.fail(token, "ENGINE_ERROR") != null) accepted.incrementAndGet()
                },
            )
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, accepted.get())
    }

    @Test fun deniedPermissionDoesNotTouchSdkAndGrantRetriesTheSameListener() {
        val cleanup = LocationListenerCleanup<Any>()
        val listener = Any()
        cleanup.add(listener)
        var calls = 0
        assertEquals(LocationRegistrationReadiness.PERMISSION_REQUIRED, cleanup.prepare(false) { calls++ })
        assertEquals(0, calls)
        assertEquals(
            LocationRegistrationReadiness.CLEANUP_REQUIRED,
            cleanup.prepare(true) {
                calls++
                assertSame(listener, it)
                throw IllegalStateException("service unavailable")
            },
        )
        assertTrue(cleanup.isNotEmpty())
        assertEquals(
            LocationRegistrationReadiness.READY,
            cleanup.prepare(true) {
                calls++
                assertSame(listener, it)
            },
        )
        assertEquals(2, calls)
        assertTrue(cleanup.isEmpty())
    }

    @Test fun koreanAndLanguageOnlyEnglishUseDocumentedRegionalLocales() {
        assertEquals("ko-KR", canonicalSpeechLocale("ko").toLanguageTag())
        assertEquals("ko-KR", canonicalSpeechLocale("ko-KR").toLanguageTag())
        assertEquals("en-US", canonicalSpeechLocale("en").toLanguageTag())
    }

    @Test fun partialCleanupRetriesOnlyUnfinishedStepsAndNeverReusesReleasedEngine() {
        val cleanup = SpeechCleanupState()
        var stops = 0
        var removes = 0
        var releases = 0
        assertFalse(
            cleanup.clean(true, {
                stops++
                throw IllegalStateException("busy")
            }, { removes++ }, { releases++ }),
        )
        assertEquals(0, releases)
        assertTrue(cleanup.clean(true, { stops++ }, { removes++ }, { releases++ }))
        assertEquals(2, stops)
        assertEquals(1, removes)
        assertEquals(1, releases)
        assertTrue(
            cleanup.clean(
                true,
                { throw AssertionError("released engine reused") },
                { throw AssertionError("released engine reused") },
                { throw AssertionError("released engine reused") },
            ),
        )
    }

    @Test fun revokedPermissionInvalidatesPendingArrivalCallbacksBeforeNextRegistration() {
        val watch = LocationWatchEpoch()
        val old = watch.begin()
        watch.invalidate()
        assertFalse(watch.isCurrent(old))
        val next = watch.begin()
        assertFalse(watch.isCurrent(old))
        assertTrue(watch.isCurrent(next))
    }
}
