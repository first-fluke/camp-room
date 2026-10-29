package camp.room.app

/** Native binding ownership is cleared only after release returns, never before its IPC. */
internal class SdkReleaseLease<R : Any>(private val resource: R, private val lock: Any = Any()) {
    private class Binding { var requested = false; var inFlight = false }
    private var binding: Binding? = null

    /** Called before initialize, because initialize may bind and then throw. */
    fun markAcquired() = synchronized(lock) {
        check(binding == null) { "Previous native binding has not been released" }
        binding = Binding()
    }

    fun requestRelease() = synchronized(lock) { binding?.requested = true }
    fun hasResource(): Boolean = synchronized(lock) { binding != null }

    /** Returns true only for this invocation's successful native release. */
    fun releaseIfRequested(release: (R) -> Unit): Boolean {
        val exact = synchronized(lock) {
            binding?.takeIf { it.requested && !it.inFlight }?.also { it.inFlight = true }
        } ?: return false
        try { release(resource) }
        catch (error: Exception) {
            synchronized(lock) { if (binding === exact) exact.inFlight = false }
            throw error
        }
        synchronized(lock) { if (binding === exact) binding = null }
        return true
    }
}

/**
 * Process-owned exact resource cleanup. A retired owner remains strongly reachable until its
 * cleanup returns successfully, even after its ViewModel has gone away. Native calls never run
 * under the registry monitor. Admission is identity-based, not equals-based.
 */
internal class SdkCleanupQuarantine<R : Any>(private val automaticAttempts: Int = 5) {
    private class Entry<R>(
        val resource: R,
        val owner: Any,
        val cleanup: () -> Unit,
        val retry: (Long, () -> Unit) -> Unit,
        val failure: (Exception) -> Unit,
        val explicitRetry: () -> Unit,
    ) {
        var attempts = 0
        var inFlight = false
        var retryScheduled = false
    }

    private val lock = Any()
    private val entries = mutableListOf<Entry<R>>()

    /** Coalesces duplicate close calls for the same exact owner, never replacing its resource. */
    fun retain(resource: R, owner: Any, cleanup: () -> Unit,
               retry: (Long, () -> Unit) -> Unit, onFailure: (Exception) -> Unit,
               onExplicitRetry: () -> Unit = {}) {
        synchronized(lock) {
            if (entries.none { it.resource === resource && it.owner === owner })
                entries += Entry(resource, owner, cleanup, retry, onFailure, onExplicitRetry)
        }
    }

    fun hasPending(resource: R): Boolean = synchronized(lock) { entries.any { it.resource === resource } }

    /** One bounded synchronous pass; exhausted automatic attempts remain eligible for admission. */
    fun drain(resource: R): Boolean {
        val exact = synchronized(lock) { entries.filter { it.resource === resource } }
        exact.forEach { attempt(it, explicit = true) }
        return !hasPending(resource)
    }

    private fun attempt(entry: Entry<R>, explicit: Boolean = false) {
        synchronized(lock) {
            if (entries.none { it === entry } || entry.inFlight) return
            entry.inFlight = true
            entry.attempts++
        }
        var failed: Exception? = null
        try {
            if (explicit) entry.explicitRetry()
            entry.cleanup()
        } catch (error: Exception) { failed = error }
        val retryDelay = synchronized(lock) {
            entry.inFlight = false
            if (failed == null) {
                entries.removeAll { it === entry }
                null
            } else if (entry.attempts < automaticAttempts && !entry.retryScheduled) {
                entry.retryScheduled = true
                longArrayOf(2000, 4000, 8000, 15000)[(entry.attempts - 1).coerceAtMost(3)]
            } else null
        }
        // Diagnostic exceptions must not discard cleanup or its retry.
        failed?.let { try { entry.failure(it) } catch (_: Exception) { } }
        if (retryDelay != null) try {
            entry.retry(retryDelay) {
                val due = synchronized(lock) {
                    entry.retryScheduled = false
                    entries.any { it === entry } && entry.attempts < automaticAttempts
                }
                if (due) attempt(entry)
            }
        } catch (error: Exception) {
            synchronized(lock) { entry.retryScheduled = false }
            try { entry.failure(error) } catch (_: Exception) { }
        }
    }
}
