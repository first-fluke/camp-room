package camp.room.app

/** Exact Binder resources survive failed cleanup. No native call holds the metadata monitor. */
internal class CampUxResourceCleanup<C : Any, M : Any, L : Any> {
    private val lock = Any()
    private var car: C? = null

    private class Binding<M, L>(
        val manager: M,
        val listener: L,
    )

    private var binding: Binding<M, L>? = null

    fun attachCar(value: C) =
        synchronized(lock) {
            check(car == null || car === value) { "Previous Car resource cleanup is unresolved" }
            car = value
        }

    fun car(): C? = synchronized(lock) { car }

    fun attachBinding(
        manager: M,
        listener: L,
    ) = synchronized(lock) {
        check(binding == null) { "Previous CarUX listener cleanup is unresolved" }
        binding = Binding(manager, listener)
    }

    fun hasBinding(): Boolean = synchronized(lock) { binding != null }

    fun hasResources(): Boolean = synchronized(lock) { binding != null || car != null }

    fun cleanupBinding(unregister: (M, L) -> Unit): Boolean {
        val captured = synchronized(lock) { binding } ?: return true
        unregister(captured.manager, captured.listener)
        synchronized(lock) { if (binding === captured) binding = null }
        return !hasBinding()
    }

    /** Disconnect is attempted only once listener cleanup actually returned successfully. */
    fun cleanup(
        unregister: (M, L) -> Unit,
        disconnect: (C) -> Unit,
    ): Boolean {
        if (!cleanupBinding(unregister)) return false
        val captured = synchronized(lock) { car } ?: return true
        disconnect(captured)
        synchronized(lock) { if (car === captured) car = null }
        return !hasResources()
    }
}

/** Finite automatic wakeups; lifecycle requests can still retry the retained exact resources. */
internal class CampUxCleanupRetryBudget {
    var attempts = 0
        private set

    fun nextDelayMillis(): Long? {
        val delays = longArrayOf(1000, 2000, 4000, 8000, 15000)
        if (attempts >= delays.size) return null
        return delays[attempts++]
    }

    fun reset() {
        attempts = 0
    }
}
