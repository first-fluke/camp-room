package camp.room.app

/** Process-shared ownership fence; a claim retains its exact native binding token strongly. */
internal class PositionSubscriptionQuarantine<K> {
    private val lock = Any()
    private val claims = linkedMapOf<K, Any>()

    fun claim(
        key: K,
        token: Any,
    ): Boolean =
        synchronized(lock) {
            if (key in claims) {
                false
            } else {
                claims[key] = token
                true
            }
        }

    fun release(
        key: K,
        token: Any,
    ): Boolean =
        synchronized(lock) {
            if (claims[key] !== token) {
                false
            } else {
                claims.remove(key)
                true
            }
        }
}

/**
 * Owns native position bindings until their exact unregister call succeeds.
 * The caller serializes register/unregister IPC; this lock protects metadata only.
 */
internal class PositionSubscriptionLedger<K, B : Any>(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val quarantine: PositionSubscriptionQuarantine<K> = PositionSubscriptionQuarantine(),
) {
    /** Preserve the existing clock-only/trailing-lambda construction. */
    constructor(nowMs: () -> Long) : this(nowMs, PositionSubscriptionQuarantine())

    private class Entry<K, B>(
        val key: K,
        val epoch: Int,
        val binding: B,
        val identity: Any,
    ) {
        var nativeInvocationStarted = false
        var attempts = 0
        var nextAttemptAt = 0L
        var inFlight = false
    }

    private val lock = Any()
    private val active = linkedMapOf<K, Entry<K, B>>()
    private val pending = mutableListOf<Entry<K, B>>()

    /** Reserve before native registration, including SDKs that callback synchronously. */
    fun reserve(
        key: K,
        epoch: Int,
        binding: B,
        identity: Any,
    ): Boolean =
        synchronized(lock) {
            if (key in active || pending.any { it.key == key }) {
                false
            } else {
                val entry = Entry(key, epoch, binding, identity)
                if (!quarantine.claim(key, entry)) {
                    false
                } else {
                    active[key] = entry
                    true
                }
            }
        }

    fun isCurrent(
        key: K,
        epoch: Int,
        identity: Any,
    ): Boolean =
        synchronized(lock) {
            active[key]?.let { it.epoch == epoch && it.identity === identity } == true
        }

    /** Commit to invoking register while the caller still holds its current-generation guard. */
    fun beginRegistration(
        key: K,
        epoch: Int,
        identity: Any,
    ): Boolean =
        synchronized(lock) {
            val entry = active[key]
            if (entry == null || entry.epoch != epoch || entry.identity !== identity || entry.nativeInvocationStarted) {
                false
            } else {
                entry.nativeInvocationStarted = true
                true
            }
        }

    fun hasActive(
        key: K,
        epoch: Int,
    ): Boolean =
        synchronized(lock) {
            active[key]?.epoch == epoch
        }

    fun activeKeys(epoch: Int): Set<K> =
        synchronized(lock) {
            active.values.filter { it.epoch == epoch }.mapTo(linkedSetOf()) { it.key }
        }

    /** Invalidate callbacks immediately, but retain every native binding for cleanup. */
    fun detachAll(): Int =
        synchronized(lock) {
            val count = active.size
            pending += active.values
            active.clear()
            count
        }

    /** Reconcile an updated topology without churning healthy current bindings. */
    fun detachExcept(
        keys: Set<K>,
        epoch: Int,
    ): Int =
        synchronized(lock) {
            val retired = active.values.filter { it.epoch != epoch || it.key !in keys }
            for (entry in retired) active.remove(entry.key)
            pending += retired
            retired.size
        }

    /** Release an exact never-invoked reservation, even if stop already moved it to pending. */
    fun discardUnregistered(
        key: K,
        identity: Any,
    ): Boolean =
        synchronized(lock) {
            val entry =
                active[key]?.takeIf { it.identity === identity }
                    ?: pending.firstOrNull { it.key == key && it.identity === identity }
            if (entry == null || entry.nativeInvocationStarted || entry.inFlight) {
                false
            } else {
                if (active[key] === entry) active.remove(key)
                pending.remove(entry)
                quarantine.release(key, entry)
                true
            }
        }

    /** A throw does not prove that native registration failed before installing its listener. */
    fun registrationFailed(
        key: K,
        identity: Any,
    ): Boolean =
        synchronized(lock) {
            val entry = active[key]
            if (entry == null || entry.identity !== identity) {
                pending.firstOrNull { it.key == key && it.identity === identity }?.nativeInvocationStarted = true
                false
            } else {
                entry.nativeInvocationStarted = true
                active.remove(key)
                pending += entry
                true
            }
        }

    fun hasPending(): Boolean = synchronized(lock) { pending.isNotEmpty() }

    fun pendingCount(): Int = synchronized(lock) { pending.size }

    /** Null means no pending entry can be retried, including exhausted or in-flight entries. */
    fun nextRetryAfterMillis(): Long? =
        synchronized(lock) {
            val now = nowMs()
            pending
                .filter { !it.inFlight && it.attempts < 5 }
                .minOfOrNull { (it.nextAttemptAt - now).coerceAtLeast(0L) }
        }

    /**
     * One bounded retry pass. Native calls and diagnostic callbacks run outside the lock.
     * Exhausted identities stay pending and keep their area blocked, rather than being lost.
     */
    fun drain(
        unregister: (K, Int, B) -> Unit,
        onFailure: (K, Int, Exception) -> Unit,
    ): Boolean {
        val candidates = synchronized(lock) { pending.toList() }
        for (entry in candidates) {
            val claimed =
                synchronized(lock) {
                    if (entry !in pending || entry.inFlight || entry.attempts >= 5 || nowMs() < entry.nextAttemptAt) {
                        false
                    } else {
                        entry.inFlight = true
                        entry.attempts++
                        true
                    }
                }
            if (!claimed) continue
            try {
                unregister(entry.key, entry.epoch, entry.binding)
                synchronized(lock) {
                    if (pending.remove(entry)) quarantine.release(entry.key, entry)
                }
            } catch (error: Exception) {
                synchronized(lock) {
                    entry.inFlight = false
                    entry.nextAttemptAt = nowMs() + longArrayOf(2000, 4000, 8000, 15000, 15000)[entry.attempts - 1]
                }
                onFailure(entry.key, entry.epoch, error)
            }
        }
        return !hasPending()
    }
}
