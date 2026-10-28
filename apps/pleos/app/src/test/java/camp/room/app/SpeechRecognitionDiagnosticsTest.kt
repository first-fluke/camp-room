package camp.room.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRecognitionDiagnosticsTest {
    private val current = RecognitionCallbackDisposition.CURRENT

    @Test fun missingRmsAndMissingProgressRemainUnobservedEvenWithFinalText() {
        val diagnostics = SpeechRecognitionDiagnostics(11) { 100L }
        diagnostics.result(8, blank = false, completed = true, current)
        diagnostics.terminal(SpeechPhase.RECOGNIZED)
        val fields = diagnostics.fields().toMap()
        assertEquals(11L, fields["sessionEpoch"])
        assertEquals("NO_CURRENT_CALLBACKS", fields["rmsObservation"])
        assertEquals(0L, fields["finiteRmsSampleCount"])
        assertNull(fields["rmsMin"])
        assertNull(fields["rmsMax"])
        assertNull(fields["firstRmsAtMillis"])
        assertNull(fields["startedAtMillis"])
        assertNull(fields["endedAtMillis"])
        assertEquals("NOT_AVAILABLE", fields["accuracyAssessment"])
        assertEquals("RECOGNIZED", fields["terminalPhase"])
    }

    @Test fun zeroAndNegativeFiniteRmsStayRawWithoutSilenceOrQualityClassification() {
        val diagnostics = SpeechRecognitionDiagnostics(1)
        diagnostics.rms(0.0, current)
        diagnostics.rms(-4.5, current)
        diagnostics.rms(2.75, current)
        val fields = diagnostics.fields().toMap()
        assertEquals(3L, fields["rmsCallbackCount"])
        assertEquals(3L, fields["finiteRmsSampleCount"])
        assertEquals(-4.5, fields["rmsMin"] as Double, 0.0)
        assertEquals(2.75, fields["rmsMax"] as Double, 0.0)
        assertEquals("SDK_RAW_UNSPECIFIED", fields["rmsUnit"])
        assertEquals("FINITE_OBSERVED", fields["rmsObservation"])
        assertFalse(fields.keys.any { it.contains("silence", true) || it.contains("score", true) || it.contains("confidence", true) })
    }

    @Test fun nonfiniteRmsIsCountedWithoutLeakingInvalidJsonNumbersOrFakeExtrema() {
        val diagnostics = SpeechRecognitionDiagnostics(1)
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { diagnostics.rms(it, current) }
        val first = diagnostics.fields().toMap()
        assertEquals(0L, first["finiteRmsSampleCount"])
        assertEquals(3L, first["nonfiniteRmsSampleCount"])
        assertEquals("NONFINITE_ONLY", first["rmsObservation"])
        assertNull(first["rmsMin"])
        assertNull(first["rmsMax"])
        diagnostics.rms(0.0, current)
        val next = diagnostics.fields().toMap()
        assertEquals("FINITE_OBSERVED", next["rmsObservation"])
        assertEquals(0.0, next["rmsMin"] as Double, 0.0)
        assertTrue(next.values.filterIsInstance<Double>().all { it.isFinite() })
    }

    @Test fun lifecycleUsesMonotonicOffsetsAndDoesNotInventMissingCallbacks() {
        var now = 100L
        val diagnostics = SpeechRecognitionDiagnostics(3) { now }
        now = 105L; diagnostics.callback(RecognitionCallback.INITIALIZED, current)
        now = 110L; diagnostics.requested()
        now = 120L; diagnostics.callback(RecognitionCallback.STARTED, current)
        now = 125L; diagnostics.rms(3.0, current)
        now = 130L; diagnostics.result(2, blank = false, completed = false, current)
        now = 140L; diagnostics.result(6, blank = false, completed = false, current)
        now = 150L; diagnostics.result(7, blank = false, completed = true, current)
        now = 151L; diagnostics.terminal(SpeechPhase.RECOGNIZED)
        now = 90L // A diagnostic clock regression cannot make elapsed times negative or go backwards.
        val fields = diagnostics.fields().toMap()
        assertEquals(5L, fields["initializedAtMillis"])
        assertEquals(10L, fields["requestAtMillis"])
        assertEquals(20L, fields["startedAtMillis"])
        assertEquals(25L, fields["firstRmsAtMillis"])
        assertEquals(25L, fields["lastRmsAtMillis"])
        assertEquals(30L, fields["firstPartialAtMillis"])
        assertEquals(50L, fields["firstFinalAtMillis"])
        assertEquals(51L, fields["terminalAtMillis"])
        assertEquals(51L, fields["elapsedMillis"])
        assertNull(fields["readyAtMillis"])
        assertNull(fields["endedAtMillis"])
        assertEquals(2L, fields["partialResultCount"])
        assertEquals(6, fields["lastPartialCharacterCount"])
        assertEquals(7, fields["finalCharacterCount"])
    }

    @Test fun partialAndBlankFinalKeepSpeechSessionTerminalRules() {
        val session = SpeechSessionState(SpeechKind.RECOGNITION)
        val token = session.begin()
        val diagnostics = SpeechRecognitionDiagnostics(token)
        val partial = diagnostics.result(4, blank = false, completed = false, current)
        assertTrue(partial.current)
        assertNull(session.finalText(token, "part", false))
        assertTrue(session.isCurrent(token))
        diagnostics.result(2, blank = true, completed = true, current)
        val outcome = session.finalText(token, "  ", true)!!
        diagnostics.terminal(outcome.phase)
        assertEquals(SpeechPhase.FAILED, outcome.phase)
        assertEquals("EMPTY_RESULT", outcome.errorCode)
        val fields = diagnostics.fields().toMap()
        assertEquals(1L, fields["currentPartialResultCount"])
        assertEquals(1L, fields["currentFinalResultCount"])
        assertEquals(1L, fields["blankFinalResultCount"])
        assertEquals(true, fields["finalBlank"])
        assertEquals("FAILED", fields["terminalPhase"])
    }

    @Test fun staleAndInactiveCallbacksCannotSupplyInputOrResultFacts() {
        val diagnostics = SpeechRecognitionDiagnostics(1)
        val stale = recognitionCallbackDisposition(hostActive = true, sameRun = false, sessionCurrent = false)
        val inactive = recognitionCallbackDisposition(hostActive = false, sameRun = true, sessionCurrent = true)
        assertEquals(RecognitionCallbackDisposition.STALE_SESSION, stale)
        assertEquals(RecognitionCallbackDisposition.INACTIVE_HOST, inactive)
        assertFalse(diagnostics.result(20, blank = false, completed = true, stale).current)
        assertFalse(diagnostics.result(30, blank = false, completed = true, inactive).current)
        diagnostics.rms(1000.0, stale)
        diagnostics.rms(1.0, inactive)
        diagnostics.callback(RecognitionCallback.STARTED, inactive)
        val fields = diagnostics.fields().toMap()
        assertEquals(2L, fields["staleCallbackCount"])
        assertEquals(3L, fields["inactiveCallbackCount"])
        assertEquals(2L, fields["ignoredFinalResultCount"])
        assertEquals(0L, fields["currentFinalResultCount"])
        assertEquals(0L, fields["currentCallbackCount"])
        assertNull(fields["finalCharacterCount"])
        assertNull(fields["firstFinalAtMillis"])
        assertNull(fields["startedAtMillis"])
        assertNull(fields["rmsMin"])
        assertEquals("NO_CURRENT_CALLBACKS", fields["rmsObservation"])
    }

    @Test fun lateAndDuplicateFinalResultsCannotOverwriteTerminalFacts() {
        val diagnostics = SpeechRecognitionDiagnostics(1)
        diagnostics.result(9, blank = false, completed = true, current)
        diagnostics.terminal(SpeechPhase.RECOGNIZED)
        val late = recognitionCallbackDisposition(hostActive = true, sameRun = true, sessionCurrent = false)
        assertEquals(RecognitionCallbackDisposition.TERMINAL_SESSION, late)
        assertFalse(diagnostics.result(0, blank = true, completed = true, late).current)
        // Even an incorrectly current caller is defensively rejected after terminal completion.
        assertFalse(diagnostics.result(99, blank = false, completed = true, current).current)
        diagnostics.rms(999.0, current)
        diagnostics.terminal(SpeechPhase.FAILED)
        val fields = diagnostics.fields().toMap()
        assertEquals(3L, fields["finalResultCount"])
        assertEquals(1L, fields["currentFinalResultCount"])
        assertEquals(2L, fields["ignoredFinalResultCount"])
        assertEquals(3L, fields["afterTerminalCallbackCount"])
        assertEquals(0L, fields["blankFinalResultCount"])
        assertEquals(9, fields["finalCharacterCount"])
        assertEquals(false, fields["finalBlank"])
        assertEquals("RECOGNIZED", fields["terminalPhase"])
        assertNull(fields["rmsMax"])
    }

    @Test fun timeoutAndCancellationAreNotReplacedByLateNonblankFinals() {
        listOf(SpeechPhase.TIMED_OUT, SpeechPhase.CANCELLED, SpeechPhase.FAILED).forEach { phase ->
            val diagnostics = SpeechRecognitionDiagnostics(1)
            diagnostics.terminal(phase)
            assertFalse(diagnostics.result(8, blank = false, completed = true, current).current)
            val fields = diagnostics.fields().toMap()
            assertEquals(phase.name, fields["terminalPhase"])
            assertNull(fields["finalCharacterCount"])
            assertEquals(1L, fields["ignoredFinalResultCount"])
        }
    }

    @Test fun repeatedCallbacksAndPartialsHaveBoundedTraceVolumeAndNoRmsSampleLogs() {
        val diagnostics = SpeechRecognitionDiagnostics(1)
        var traces = 0
        repeat(1000) {
            if (diagnostics.callback(RecognitionCallback.READY, current).shouldTrace) traces++
            if (diagnostics.result(it, blank = false, completed = false, current).shouldTrace) traces++
            diagnostics.rms(it.toDouble(), current)
        }
        assertEquals(2, traces)
        assertFalse(diagnostics.callback(RecognitionCallback.RMS, current).shouldTrace)
        assertTrue(diagnostics.result(5, blank = false, completed = true, current).shouldTrace)
        diagnostics.terminal(SpeechPhase.RECOGNIZED)
        assertTrue(diagnostics.result(6, blank = false, completed = true, current).shouldTrace)
        assertFalse(diagnostics.result(7, blank = false, completed = true, current).shouldTrace)
        val fields = diagnostics.fields().toMap()
        assertEquals(1000L, fields["partialResultCount"])
        assertEquals(1000L, fields["finiteRmsSampleCount"])
        assertEquals(999, fields["lastPartialCharacterCount"])
    }
}
