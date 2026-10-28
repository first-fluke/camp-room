package camp.room.app

internal enum class NavigationRequestKind { PREVIEW, START }
internal enum class NavigationCallbackAdmission { CURRENT, STALE, EXPIRED, TERMINAL }

/** Only the exact token returned by begin can own a callback. */
internal data class NavigationRequestToken internal constructor(
    val id: Long,
    val connectionEpoch: Int,
    val kind: NavigationRequestKind,
    val request: SdkDestinationRequest,
) {
    override fun toString(): String =
        "NavigationRequestToken(id=$id, connectionEpoch=$connectionEpoch, kind=$kind)"
}

/** Bounds observation of one SDK request without creating or retrying SDK requests. */
internal class NavigationRequestState(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    companion object {
        const val INITIAL_OBSERVATION_MS = 5_000L
        const val PREVIEW_MAX_OBSERVATION_MS = 15_000L
    }

    private data class Entry(
        val token: NavigationRequestToken,
        val startedAtMillis: Long,
        var terminal: Boolean = false,
        var claimed: Boolean = false,
    )

    private var nextId = 0L
    private var active: Entry? = null

    @Synchronized
    fun begin(kind: NavigationRequestKind, request: SdkDestinationRequest, epoch: Int): NavigationRequestToken {
        check(nextId < Long.MAX_VALUE) { "Navigation request identifiers exhausted" }
        val startedAtMillis = clockMillis()
        val token = NavigationRequestToken(++nextId, epoch, kind, request)
        active = Entry(token, startedAtMillis)
        return token
    }

    @Synchronized
    fun admission(token: NavigationRequestToken, currentEpoch: Int): NavigationCallbackAdmission =
        admissionAt(token, currentEpoch, clockMillis())

    /** The successful claimant owns the only terminal result for this request. */
    @Synchronized
    fun claim(token: NavigationRequestToken, currentEpoch: Int): NavigationCallbackAdmission {
        val result = admissionAt(token, currentEpoch, clockMillis())
        if (result == NavigationCallbackAdmission.CURRENT) active?.let {
            it.terminal = true
            it.claimed = true
        }
        return result
    }

    /** Receipt before the deadline remains eligible until the request or lifecycle ends. */
    @Synchronized
    fun canPublishClaimedResult(token: NavigationRequestToken, currentEpoch: Int): Boolean =
        active?.let { it.token === token && token.connectionEpoch == currentEpoch && it.claimed } == true

    @Synchronized
    fun finish(token: NavigationRequestToken) {
        active?.takeIf { it.token === token }?.let {
            it.terminal = true
            it.claimed = false
        }
    }

    @Synchronized
    fun invalidate() {
        active = null
    }

    /** Retains elapsed time for terminal/expired requests until superseded or invalidated. */
    @Synchronized
    fun elapsedMillis(token: NavigationRequestToken): Long? =
        active?.takeIf { it.token === token }?.let { elapsed(it, clockMillis()) }

    @Synchronized
    fun remainingMillis(token: NavigationRequestToken, currentEpoch: Int): Long {
        val now = clockMillis()
        if (admissionAt(token, currentEpoch, now) != NavigationCallbackAdmission.CURRENT) return 0
        val entry = active ?: return 0
        return observationLimit(token.kind) - elapsed(entry, now)
    }

    private fun admissionAt(token: NavigationRequestToken, currentEpoch: Int, now: Long): NavigationCallbackAdmission {
        val entry = active
        return when {
            entry == null || entry.token !== token || token.connectionEpoch != currentEpoch -> NavigationCallbackAdmission.STALE
            entry.terminal -> NavigationCallbackAdmission.TERMINAL
            elapsed(entry, now) >= observationLimit(token.kind) -> NavigationCallbackAdmission.EXPIRED
            else -> NavigationCallbackAdmission.CURRENT
        }
    }

    private fun observationLimit(kind: NavigationRequestKind): Long = when (kind) {
        NavigationRequestKind.PREVIEW -> PREVIEW_MAX_OBSERVATION_MS
        NavigationRequestKind.START -> INITIAL_OBSERVATION_MS
    }

    private fun elapsed(entry: Entry, now: Long): Long {
        if (now <= entry.startedAtMillis) return 0
        val difference = now - entry.startedAtMillis
        return if (difference < 0) Long.MAX_VALUE else difference
    }
}
