package camp.room.app

/** Invalidates callbacks on both host lifecycle changes and service rebinding. */
internal class CampUxCallbackEpoch {
    internal data class Binding(val session: Long, val binding: Long)
    private var session = 0L
    private var binding = 0L
    private var active = false

    fun start(): Long {
        active = true
        binding++
        return ++session
    }

    fun isActive(expectedSession: Long) = active && session == expectedSession
    fun captureBinding() = Binding(session, binding)
    fun isCurrent(expected: Binding) = active && expected == Binding(session, binding)
    fun invalidateBinding() { binding++ }
    fun stop() {
        active = false
        session++
        binding++
    }
}
