package camp.room.app

/** Connection metadata never shares a monitor with initialize/release or client callbacks. */
internal class NavigationConnectionState {
    private data class Pending(
        val success: () -> Unit,
        val failure: (Throwable) -> Unit,
    )

    private val lock = Any()
    private var connected = false
    private var connecting = false
    private var closed = false
    private var releaseComplete = false
    private var generation = 0
    private val pending = mutableListOf<Pending>()

    fun connect(
        onConnected: () -> Unit,
        onFailed: (Throwable) -> Unit,
        initialize: (() -> Unit, (Throwable) -> Unit) -> Unit,
    ) {
        var ready = false
        val epoch =
            synchronized(lock) {
                check(!closed) { "Adapter is closed" }
                if (connected) {
                    ready = true
                    null
                } else {
                    check(pending.size < 16) { "Navigation connect callback capacity exceeded" }
                    pending += Pending(onConnected, onFailed)
                    if (connecting) {
                        null
                    } else {
                        connecting = true
                        ++generation
                    }
                }
            }
        if (ready) {
            onConnected()
            return
        }
        if (epoch == null) return

        fun finish(
            success: Boolean,
            error: Throwable? = null,
        ) {
            val callbacks =
                synchronized(lock) {
                    if (closed || generation != epoch || !connecting) {
                        emptyList()
                    } else {
                        connected = success
                        connecting = false
                        pending.toList().also { pending.clear() }
                    }
                }
            callbacks.forEach { if (success) it.success() else it.failure(error ?: IllegalStateException("Navigation connection failed")) }
        }
        try {
            initialize({ finish(true) }, { finish(false, it) })
        } catch (error: Exception) {
            finish(false, error)
            throw error
        }
    }

    fun requireConnected() = synchronized(lock) { check(connected && !closed) { "Navigation session unavailable" } }

    /** A thrown native release stays pending. A later cleanup really retries it. */
    fun close(release: () -> Unit) {
        val needed =
            synchronized(lock) {
                if (!closed) {
                    closed = true
                    connected = false
                    connecting = false
                    generation++
                    pending.clear()
                }
                !releaseComplete
            }
        if (!needed) return
        release()
        synchronized(lock) { releaseComplete = true }
    }
}
