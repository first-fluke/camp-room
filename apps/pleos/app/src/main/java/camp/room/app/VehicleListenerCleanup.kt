package camp.room.app

/** Retains exact native listener identities until unregister succeeds; never assumes failure is cleanup. */
internal class VehicleListenerCleanup<L : Any>(
    private val maxAttempts: Int = 5,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    init { require(maxAttempts > 0) }
    private class Entry<L>(val listener: L) {
        var attempts = 0
        var nextAttempt = 0L
        var inFlight = false
    }
    private val lock = Any()
    private val entries = mutableListOf<Entry<L>>()

    fun retain(listener: L) = synchronized(lock) {
        if (entries.none { it.listener === listener }) entries += Entry(listener)
    }
    fun hasPending(): Boolean = synchronized(lock) { entries.isNotEmpty() }

    /** Explicit lifecycle admission can restart a completed burst, never an in-flight call. */
    fun rearmExhausted() = synchronized(lock) {
        entries.filter { !it.inFlight && it.attempts >= maxAttempts }.forEach {
            it.attempts=0;it.nextAttempt=nowMs()
        }
    }

    /** One bounded pass. Called on the vendor lane; native calls never run under the state lock. */
    fun drain(unregister: (L) -> Unit, onFailure: (Exception) -> Unit): Boolean {
        val due = synchronized(lock) {
            entries.filter { !it.inFlight && it.attempts < maxAttempts && nowMs() >= it.nextAttempt }.onEach {
                it.inFlight = true
                it.attempts++
            }
        }
        for (entry in due) {
            try {
                unregister(entry.listener)
                synchronized(lock) { entries.remove(entry) }
            } catch (error: Exception) {
                synchronized(lock) {
                    entry.inFlight = false
                    entry.nextAttempt = nowMs() + longArrayOf(2000, 4000, 8000, 15000, 15000)[(entry.attempts - 1).coerceAtMost(4)]
                }
                // Diagnostics must not strand the remaining reserved entries in flight.
                runCatching { onFailure(error) }
            }
        }
        return !hasPending()
    }
}
