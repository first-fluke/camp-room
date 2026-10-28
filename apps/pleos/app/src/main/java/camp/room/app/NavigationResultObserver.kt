package camp.room.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Observes one SDK request and one deferred result. Preview can keep observing after the initial
 * five seconds, within the same fifteen-second deadline; start has only the initial window.
 * Callbacks must claim and complete under [lock], so a completed accepted result wins over a
 * concurrent observation timeout. The owner still validates lifecycle/epoch before publishing it.
 */
internal class NavigationResultObserver<T>(
    private val result: CompletableDeferred<T?>,
    private val token: NavigationRequestToken,
    private val state: NavigationRequestState,
    private val lock: Any,
    private val currentEpoch: () -> Int,
    private val onExtended: (elapsedMillis: Long?, remainingMillis: Long) -> Unit = { _, _ -> },
    private val awaitWindow: suspend (CompletableDeferred<T?>, Long) -> Unit = { deferred, millis ->
        withTimeoutOrNull(millis) { deferred.await() }
        Unit
    },
) {
    private data class Window(val completed: Boolean, val elapsedMillis: Long?, val waitMillis: Long?)

    suspend fun await(): T? {
        var returnedCompleted = false
        try {
            currentCoroutineContext().ensureActive()
            val initial = window(initial = true, allowWait = true)
            if (initial.completed) return result.await().also { returnedCompleted = true }
            val firstWait = initial.waitMillis ?: return null
            if (firstWait > 0L) awaitWindow(result, firstWait)

            currentCoroutineContext().ensureActive()
            val extended = window(initial = false, allowWait = token.kind == NavigationRequestKind.PREVIEW)
            if (extended.completed) return result.await().also { returnedCompleted = true }
            val remaining = extended.waitMillis ?: return null
            onExtended(extended.elapsedMillis, remaining)
            awaitWindow(result, remaining)

            currentCoroutineContext().ensureActive()
            // A timeout may beat await's dispatched resume after a valid callback completed.
            // Recover that result before closing callback admission, under the same callback lock.
            val terminal = window(initial = false, allowWait = false)
            return if (terminal.completed) result.await().also { returnedCompleted = true } else null
        } finally {
            // The owner keeps an accepted result's claim until publication and its own cleanup.
            // Pending timeouts, cancellation, and exceptional exits revoke that claim immediately.
            if (!returnedCompleted) synchronized(lock) { state.finish(token) }
        }
    }

    private fun window(initial: Boolean, allowWait: Boolean): Window = synchronized(lock) {
        val elapsed = state.elapsedMillis(token)
        val remaining = state.remainingMillis(token, currentEpoch())
        // remaining is zero for a successful claim because it is terminal. Inspect completion
        // AFTER computing remaining, so a callback accepted during that calculation is recovered.
        if (result.isCompleted) return@synchronized Window(true, elapsed, null)
        if (remaining <= 0L || !allowWait) {
            state.finish(token)
            return@synchronized Window(false, elapsed, null)
        }
        val millis = if (initial) minOf(
            (NavigationRequestState.INITIAL_OBSERVATION_MS - (elapsed ?: 0L)).coerceAtLeast(0L), remaining,
        ) else remaining
        Window(false, elapsed, millis)
    }
}
