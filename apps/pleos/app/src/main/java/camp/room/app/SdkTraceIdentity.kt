package camp.room.app

import java.util.concurrent.atomic.AtomicLong

/** Ephemeral process-local diagnostic identity, never a user or installation identifier. */
internal data class SdkTraceOwner(
    val id: String,
    val kind: String,
)

internal data class SdkTraceStamp(
    val processId: String,
    val sequence: Long,
    val elapsedMillis: Long,
)

/** Immutable context keeps delayed/duplicate callbacks attached to their original owner. */
internal data class SdkTraceContext(
    val owner: SdkTraceOwner,
    val sessionEpoch: Int?,
    val requestId: Long?,
    val requestNamespace: String = "unscoped",
) {
    val requestKey: String?
        get() = if (sessionEpoch != null && requestId != null) "${owner.id}:$sessionEpoch:$requestNamespace:$requestId" else null

    fun fields(): Array<Pair<String, Any?>> =
        arrayOf(
            "ownerId" to owner.id,
            "ownerKind" to owner.kind,
            "sessionEpoch" to sessionEpoch,
            "requestId" to requestId,
            "requestNamespace" to requestNamespace,
            "requestKey" to requestKey,
        )
}

internal class SdkTraceIdentity(
    private val processId: String,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val owners = AtomicLong()
    private val events = AtomicLong()

    init {
        require(processId.isNotBlank())
    }

    fun owner(kind: String): SdkTraceOwner {
        require(kind.matches(Regex("[a-z][a-z0-9_.-]{0,47}")))
        return SdkTraceOwner("$processId:$kind:${next(owners)}", kind)
    }

    fun stamp() = SdkTraceStamp(processId, next(events), clockMillis())

    private fun next(counter: AtomicLong): Long {
        while (true) {
            val current = counter.get()
            check(current < Long.MAX_VALUE) { "SDK diagnostic identifiers exhausted" }
            if (counter.compareAndSet(current, current + 1)) return current + 1
        }
    }
}
