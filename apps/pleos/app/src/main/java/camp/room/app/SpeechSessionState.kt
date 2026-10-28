package camp.room.app

import java.util.Locale

internal enum class SpeechKind { PLAYBACK, RECOGNITION }
internal enum class SpeechPhase { INITIALIZING, READY, STARTED, PROCESSING, COMPLETED, RECOGNIZED, FAILED, TIMED_OUT, CANCELLED }

internal data class SpeechOutcome(
    val phase: SpeechPhase,
    val errorCode: String? = null,
    val text: String? = null,
)

/** A request owns one terminal result. Old or duplicate callbacks cannot complete another request. */
internal class SpeechSessionState(private val kind: SpeechKind) {
    private var generation = 0L
    private var phase = SpeechPhase.CANCELLED
    private var outcome: SpeechOutcome? = null

    @Synchronized fun begin(): Long {
        generation++
        phase = SpeechPhase.INITIALIZING
        outcome = null
        return generation
    }

    @Synchronized fun isCurrent(token: Long) = token == generation && outcome == null
    @Synchronized fun ready(token: Long): Boolean {
        if (!isCurrent(token)) return false
        if (phase == SpeechPhase.INITIALIZING) phase = SpeechPhase.READY
        return true
    }
    @Synchronized fun started(token: Long): Boolean {
        if (!isCurrent(token)) return false
        phase = SpeechPhase.STARTED
        return true
    }
    @Synchronized fun processing(token: Long): Boolean {
        if (!isCurrent(token)) return false
        phase = SpeechPhase.PROCESSING
        return true
    }
    @Synchronized fun playbackDone(token: Long): SpeechOutcome? {
        if (kind != SpeechKind.PLAYBACK) return null
        return finish(token, SpeechOutcome(SpeechPhase.COMPLETED))
    }
    @Synchronized fun finalText(token: Long, text: String, completed: Boolean): SpeechOutcome? {
        if (kind != SpeechKind.RECOGNITION || !completed) return null
        return finish(token, if (text.isNotBlank()) SpeechOutcome(SpeechPhase.RECOGNIZED, text = text)
            else SpeechOutcome(SpeechPhase.FAILED, "EMPTY_RESULT"))
    }
    @Synchronized fun fail(token: Long, code: String) = finish(token, SpeechOutcome(SpeechPhase.FAILED, code))
    @Synchronized fun timeout(token: Long, code: String) = finish(token, SpeechOutcome(SpeechPhase.TIMED_OUT, code))
    @Synchronized fun cancel(token: Long) = finish(token, SpeechOutcome(SpeechPhase.CANCELLED, "CANCELLED"))

    private fun finish(token: Long, result: SpeechOutcome): SpeechOutcome? {
        if (!isCurrent(token)) return null
        outcome = result
        phase = result.phase
        return result
    }
}

/** The documented regional locale is needed even when the app's language preference is language-only. */
internal fun canonicalSpeechLocale(tag: String): Locale {
    val locale = Locale.forLanguageTag(tag)
    return when {
        locale.language == "ko" -> Locale.KOREA
        locale.language == "en" && locale.country.isEmpty() -> Locale.US
        else -> locale
    }
}

internal enum class LocationRegistrationReadiness { PERMISSION_REQUIRED, CLEANUP_REQUIRED, READY }

internal class SpeechCleanupState {
    private var stopped = false
    private var listenerRemoved = false
    private var released = false

    /** Never invoke an engine again after release; retain only unfinished cleanup steps. */
    @Synchronized fun clean(requested: Boolean, stop: () -> Unit, removeListener: () -> Unit, release: () -> Unit): Boolean {
        if (released) return true
        if (!stopped) try { if (requested) stop(); stopped = true } catch (_: Exception) { }
        if (!listenerRemoved) try { removeListener(); listenerRemoved = true } catch (_: Exception) { }
        if (stopped && listenerRemoved) try { release(); released = true } catch (_: Exception) { }
        return released
    }
}

internal class LocationWatchEpoch {
    private var generation = 0
    @Synchronized fun begin() = ++generation
    @Synchronized fun invalidate() { generation++ }
    @Synchronized fun isCurrent(token: Int) = token == generation
}

/** Failed cleanup retains the exact listener; permission denial never calls the SDK. */
internal class LocationListenerCleanup<L> {
    private val pending = linkedSetOf<L>()
    @Synchronized fun add(listener: L) { pending.add(listener) }
    @Synchronized fun isEmpty() = pending.isEmpty()
    @Synchronized fun isNotEmpty() = pending.isNotEmpty()
    @Synchronized fun prepare(permissionGranted: Boolean, unregister: (L) -> Unit): LocationRegistrationReadiness {
        if (!permissionGranted) return LocationRegistrationReadiness.PERMISSION_REQUIRED
        pending.toList().forEach { listener ->
            try { unregister(listener); pending.remove(listener) } catch (_: Exception) { /* Retry the same listener later. */ }
        }
        return if (pending.isEmpty()) LocationRegistrationReadiness.READY else LocationRegistrationReadiness.CLEANUP_REQUIRED
    }
}
