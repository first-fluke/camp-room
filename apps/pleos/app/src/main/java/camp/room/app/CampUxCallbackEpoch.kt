package camp.room.app

/** Invalidates callbacks on both host lifecycle changes and service rebinding. */
internal class CampUxCallbackEpoch {
    internal data class Binding(
        val session: Long,
        val binding: Long,
    )

    internal class InitialReadToken internal constructor(
        val binding: Binding,
        internal val revision: Long,
    )

    private var session = 0L
    private var binding = 0L
    private var active = false
    private var eventRevision = 0L
    private var initialReadCaptured = false
    private var pendingInitialRead: InitialReadToken? = null

    @Synchronized fun start(): Long {
        active = true
        advanceBinding()
        return ++session
    }

    @Synchronized fun isActive(expectedSession: Long) = active && session == expectedSession

    @Synchronized fun captureBinding() = Binding(session, binding)

    @Synchronized fun isCurrent(expected: Binding) = active && expected == Binding(session, binding)

    @Synchronized fun invalidateBinding() {
        advanceBinding()
    }

    /** Reserve before registering a listener or issuing the initial getter. */
    @Synchronized fun captureInitialRead(expected: Binding): InitialReadToken? {
        if (!isCurrent(expected) || initialReadCaptured || eventRevision != 0L) return null
        initialReadCaptured = true
        return InitialReadToken(expected, eventRevision).also { pendingInitialRead = it }
    }

    /** Every current event permanently supersedes the initial getter for this binding. */
    @Synchronized fun publishEvent(
        expected: Binding,
        action: () -> Unit,
    ): Boolean {
        if (!isCurrent(expected)) return false
        eventRevision++
        pendingInitialRead = null
        action()
        return true
    }

    @Synchronized fun publishInitial(
        token: InitialReadToken,
        action: () -> Unit,
    ): Boolean {
        if (!isCurrent(token.binding) || pendingInitialRead !== token || eventRevision != token.revision) return false
        pendingInitialRead = null
        action()
        return true
    }

    @Synchronized fun withActive(
        expectedSession: Long,
        action: () -> Unit,
    ): Boolean {
        if (!isActive(expectedSession)) return false
        action()
        return true
    }

    @Synchronized fun withCurrent(
        expected: Binding,
        action: () -> Unit,
    ): Boolean {
        if (!isCurrent(expected)) return false
        action()
        return true
    }

    @Synchronized fun stop() {
        active = false
        session++
        advanceBinding()
    }

    private fun advanceBinding() {
        binding++
        eventRevision = 0L
        initialReadCaptured = false
        pendingInitialRead = null
    }
}
