package camp.room.app

internal enum class RecognitionCallback(
    val traceName: String,
) {
    INITIALIZED("initialize.onSuccess"),
    INITIALIZE_FAILED("initialize.onFailure"),
    READY("onReady"),
    STARTED("onStartedRecognition"),
    ENDED("onEndedRecognition"),
    PARTIAL_RESULT("onUpdated.partial"),
    FINAL_RESULT("onUpdated.completed"),
    ERROR("onError"),
    RMS("onUpdatedRms"),
}

internal enum class RecognitionCallbackDisposition { CURRENT, INACTIVE_HOST, STALE_SESSION, TERMINAL_SESSION }

/** Keep callback ownership separate from diagnostics; neither partial text nor RMS completes a session. */
internal fun recognitionCallbackDisposition(
    hostActive: Boolean,
    sameRun: Boolean,
    sessionCurrent: Boolean,
): RecognitionCallbackDisposition =
    when {
        !hostActive -> RecognitionCallbackDisposition.INACTIVE_HOST
        !sameRun -> RecognitionCallbackDisposition.STALE_SESSION
        !sessionCurrent -> RecognitionCallbackDisposition.TERMINAL_SESSION
        else -> RecognitionCallbackDisposition.CURRENT
    }

internal data class RecognitionCallbackObservation(
    val disposition: RecognitionCallbackDisposition,
    val elapsedMillis: Long,
    val shouldTrace: Boolean,
) {
    val current get() = disposition == RecognitionCallbackDisposition.CURRENT
}

/**
 * Constant-size, text-free diagnostics for one microphone request. The 3.3.1 SDK documents RMS as
 * not dB, without a range/scale, and does not emit it for sendAudio. No RMS is unobserved input,
 * including when a final result arrives. Finite or zero values cannot establish silence, quality,
 * confidence, or recognition accuracy. All times below are monotonic offsets from this run's creation.
 */
internal class SpeechRecognitionDiagnostics(
    private val sessionEpoch: Long,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val createdAt = clockMillis()
    private var elapsedMillis = 0L
    private val traced = mutableSetOf<Pair<RecognitionCallback, RecognitionCallbackDisposition>>()
    private val firstCallbacks = mutableMapOf<RecognitionCallback, Long>()
    private var requestAtMillis: Long? = null
    private var terminalAtMillis: Long? = null
    private var terminalPhase: SpeechPhase? = null
    private var callbacks = 0L
    private var currentCallbacks = 0L
    private var inactiveCallbacks = 0L
    private var staleCallbacks = 0L
    private var terminalCallbacks = 0L
    private var partialResults = 0L
    private var finalResults = 0L
    private var currentPartialResults = 0L
    private var currentFinalResults = 0L
    private var blankFinalResults = 0L
    private var ignoredFinalResults = 0L
    private var partialCharacterCount: Int? = null
    private var finalCharacterCount: Int? = null
    private var finalBlank: Boolean? = null
    private var rmsCallbacks = 0L
    private var finiteRms = 0L
    private var nonfiniteRms = 0L
    private var rmsMin: Double? = null
    private var rmsMax: Double? = null
    private var lastRmsAtMillis: Long? = null

    @Synchronized fun requested() {
        if (requestAtMillis == null && terminalPhase == null) requestAtMillis = now()
    }

    @Synchronized fun callback(
        callback: RecognitionCallback,
        disposition: RecognitionCallbackDisposition,
    ): RecognitionCallbackObservation = observe(callback, disposition)

    /** Text is deliberately not an argument; only UTF-16 length and blankness leave the listener. */
    @Synchronized fun result(
        characterCount: Int,
        blank: Boolean,
        completed: Boolean,
        disposition: RecognitionCallbackDisposition,
    ): RecognitionCallbackObservation {
        val observation = observe(if (completed) RecognitionCallback.FINAL_RESULT else RecognitionCallback.PARTIAL_RESULT, disposition)
        if (completed) {
            finalResults = increment(finalResults)
            if (observation.current) {
                currentFinalResults = increment(currentFinalResults)
                if (blank) blankFinalResults = increment(blankFinalResults)
                if (finalCharacterCount == null) {
                    finalCharacterCount = characterCount.coerceAtLeast(0)
                    finalBlank = blank
                }
            } else {
                ignoredFinalResults = increment(ignoredFinalResults)
            }
        } else {
            partialResults = increment(partialResults)
            if (observation.current) {
                currentPartialResults = increment(currentPartialResults)
                partialCharacterCount = characterCount.coerceAtLeast(0)
            }
        }
        return observation
    }

    @Synchronized fun rms(
        value: Double,
        disposition: RecognitionCallbackDisposition,
    ) {
        val observation = observe(RecognitionCallback.RMS, disposition)
        rmsCallbacks = increment(rmsCallbacks)
        if (!observation.current) return
        lastRmsAtMillis = observation.elapsedMillis
        if (value.isFinite()) {
            finiteRms = increment(finiteRms)
            rmsMin = rmsMin?.let { minOf(it, value) } ?: value
            rmsMax = rmsMax?.let { maxOf(it, value) } ?: value
        } else {
            nonfiniteRms = increment(nonfiniteRms)
        }
    }

    @Synchronized fun terminal(phase: SpeechPhase) {
        if (terminalPhase != null) return
        terminalPhase = phase
        terminalAtMillis = now()
    }

    /** A fixed set of scalar fields: no audio, transcript, callback history, or inferred scores. */
    @Synchronized fun fields(): Array<Pair<String, Any?>> =
        arrayOf(
            "sessionEpoch" to sessionEpoch,
            "elapsedMillis" to now(),
            "requestAtMillis" to requestAtMillis,
            "initializedAtMillis" to firstCallbacks[RecognitionCallback.INITIALIZED],
            "readyAtMillis" to firstCallbacks[RecognitionCallback.READY],
            "startedAtMillis" to firstCallbacks[RecognitionCallback.STARTED],
            "endedAtMillis" to firstCallbacks[RecognitionCallback.ENDED],
            "firstPartialAtMillis" to firstCallbacks[RecognitionCallback.PARTIAL_RESULT],
            "firstFinalAtMillis" to firstCallbacks[RecognitionCallback.FINAL_RESULT],
            "terminalAtMillis" to terminalAtMillis,
            "terminalPhase" to terminalPhase?.name,
            "callbackCount" to callbacks,
            "currentCallbackCount" to currentCallbacks,
            "inactiveCallbackCount" to inactiveCallbacks,
            "staleCallbackCount" to staleCallbacks,
            "afterTerminalCallbackCount" to terminalCallbacks,
            "partialResultCount" to partialResults,
            "finalResultCount" to finalResults,
            "currentPartialResultCount" to currentPartialResults,
            "currentFinalResultCount" to currentFinalResults,
            "blankFinalResultCount" to blankFinalResults,
            "ignoredFinalResultCount" to ignoredFinalResults,
            "lastPartialCharacterCount" to partialCharacterCount,
            "finalCharacterCount" to finalCharacterCount,
            "finalBlank" to finalBlank,
            "characterCountUnit" to "UTF16_CODE_UNITS",
            "rmsCallbackCount" to rmsCallbacks,
            "finiteRmsSampleCount" to finiteRms,
            "nonfiniteRmsSampleCount" to nonfiniteRms,
            "rmsMin" to rmsMin,
            "rmsMax" to rmsMax,
            "firstRmsAtMillis" to firstCallbacks[RecognitionCallback.RMS],
            "lastRmsAtMillis" to lastRmsAtMillis,
            "rmsUnit" to "SDK_RAW_UNSPECIFIED",
            "rmsObservation" to
                when {
                    finiteRms > 0L -> "FINITE_OBSERVED"
                    nonfiniteRms > 0L -> "NONFINITE_ONLY"
                    else -> "NO_CURRENT_CALLBACKS"
                },
            "accuracyAssessment" to "NOT_AVAILABLE",
        )

    private fun observe(
        callback: RecognitionCallback,
        disposition: RecognitionCallbackDisposition,
    ): RecognitionCallbackObservation {
        // Defensive guard: a diagnostic call cannot overwrite terminal facts even with a stale caller check.
        val actual =
            if (disposition == RecognitionCallbackDisposition.CURRENT && terminalPhase != null) {
                RecognitionCallbackDisposition.TERMINAL_SESSION
            } else {
                disposition
            }
        val elapsed = now()
        callbacks = increment(callbacks)
        when (actual) {
            RecognitionCallbackDisposition.CURRENT -> {
                currentCallbacks = increment(currentCallbacks)
                if (!firstCallbacks.containsKey(callback)) firstCallbacks[callback] = elapsed
            }

            RecognitionCallbackDisposition.INACTIVE_HOST -> {
                inactiveCallbacks = increment(inactiveCallbacks)
            }

            RecognitionCallbackDisposition.STALE_SESSION -> {
                staleCallbacks = increment(staleCallbacks)
            }

            RecognitionCallbackDisposition.TERMINAL_SESSION -> {
                terminalCallbacks = increment(terminalCallbacks)
            }
        }
        // Each callback/rejection pair can log only once per run; RMS is aggregated without sample logs.
        val shouldTrace = callback != RecognitionCallback.RMS && traced.add(callback to actual)
        return RecognitionCallbackObservation(actual, elapsed, shouldTrace)
    }

    private fun now(): Long {
        elapsedMillis = maxOf(elapsedMillis, (clockMillis() - createdAt).coerceAtLeast(0L))
        return elapsedMillis
    }

    private fun increment(value: Long) = if (value == Long.MAX_VALUE) value else value + 1L
}
