package camp.room.app

/** Pure admission state. Never execute native SDK code while its monitor is held. */
internal class CampObservationAdmission {
    enum class Transition { START, STOP, NONE }

    internal class Token internal constructor(
        val epoch: Long,
    )

    private var hostStarted = false
    private var uxAllowed = false
    private var epoch = 0L
    private var token: Token? = null

    @Synchronized fun onHostStart(): Transition {
        hostStarted = true
        return reconcile()
    }

    @Synchronized fun onHostStop(): Transition {
        hostStarted = false
        uxAllowed = false // A later host START needs a fresh UX signal.
        return reconcile()
    }

    @Synchronized fun onUxAvailabilityChanged(allowed: Boolean): Transition {
        uxAllowed = allowed
        return reconcile()
    }

    @Synchronized fun capture(): Token? = token

    @Synchronized fun isCurrent(expected: Token): Boolean = token === expected

    /** Pure value selection: an old load must not erase a newer observation. */
    @Synchronized fun <T> publication(
        expected: Token?,
        observed: T,
        previous: T,
        unknown: T,
    ): T =
        when {
            token == null -> unknown
            token === expected -> observed
            else -> previous
        }

    private fun reconcile(): Transition {
        val allowed = hostStarted && uxAllowed
        if (allowed == (token != null)) return Transition.NONE
        epoch = if (epoch == Long.MAX_VALUE) Long.MIN_VALUE else epoch + 1
        token = if (allowed) Token(epoch) else null
        return if (allowed) Transition.START else Transition.STOP
    }
}
