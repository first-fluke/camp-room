package camp.room.app

internal data class SocSnapshotTrace(
    val epoch: Int,
    val requestId: Long,
    val reason: String,
    val eventRevision: Long,
    val current: Boolean? = null,
    val accepted: Boolean? = null,
    val supersededByEvent: Boolean? = null,
    val value: Int? = null,
    val errorClass: String? = null,
)

/** Both clocks and the native epoch belong to the accepted sample, not a later poll. */
internal data class SocObservedSample(
    val value: Int,
    val epoch: Int,
    val sampledAtElapsedMillis: Long,
    val observedAtEpochMillis: Long,
)

/**
 * Fresh snapshots for an existing SOC subscription. Metadata/publication share
 * the adapter's state lock; native reads always run outside it.
 */
internal class SocSnapshotReader<L : Any>(
    private val lock: Any = Any(),
    private val read: ((Int?) -> Unit, (Exception) -> Unit) -> Unit,
    private val onValue: (Int?) -> Unit,
    private val onError: (Exception) -> Unit,
    private val trace: (String, SocSnapshotTrace) -> Unit = { _, _ -> },
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
) {
    private class Binding<L>(
        val epoch: Int,
        val listener: L,
    ) {
        var revision = 0L
        var request: Request<L>? = null
        var sample: SocObservedSample? = null
    }

    private class Request<L>(
        val binding: Binding<L>,
        val id: Long,
        val reason: String,
        val revision: Long,
    )

    private var binding: Binding<L>? = null
    private var nextRequest = 0L

    fun bind(
        epoch: Int,
        listener: L,
    ) = synchronized(lock) {
        binding = Binding(epoch, listener)
    }

    fun invalidate(
        epoch: Int? = null,
        listener: L? = null,
    ) = synchronized(lock) {
        val current = binding
        if (current != null && (epoch == null || current.epoch == epoch) &&
            (listener == null || current.listener === listener)
        ) {
            binding = null
        }
    }

    fun currentSample(
        epoch: Int,
        listener: L?,
    ): SocObservedSample? =
        synchronized(lock) {
            binding?.takeIf { listener != null && it.epoch == epoch && it.listener === listener }?.sample
        }

    private fun observe(
        current: Binding<L>,
        value: Int?,
    ) {
        current.sample =
            value?.takeIf { it in 0..100 }?.let {
                SocObservedSample(it, current.epoch, clockMillis(), wallClockMillis())
            }
        onValue(value?.takeIf { it in 0..100 })
    }

    /** Production start branch: reuse refreshes only, never initializes or registers again. */
    fun startOrRefresh(
        epoch: Int,
        alreadyStarted: Boolean,
        listener: L?,
        initializeAndDiscover: () -> Unit,
    ) {
        if (!alreadyStarted) {
            initializeAndDiscover()
        } else if (listener != null) {
            refresh(epoch, listener, "active_reuse")
        }
    }

    fun onEvent(
        epoch: Int,
        listener: L,
        value: Int?,
    ): Boolean =
        synchronized(lock) {
            val current = binding
            if (current == null || current.epoch != epoch || current.listener !== listener) {
                false
            } else {
                current.revision++
                observe(current, value)
                true
            }
        }

    fun refresh(
        epoch: Int,
        listener: L,
        reason: String,
    ): Boolean {
        val request =
            synchronized(lock) {
                val current = binding
                if (current == null || current.epoch != epoch || current.listener !== listener) return false
                Request(current, ++nextRequest, reason, current.revision).also { current.request = it }
            }
        trace("soc.snapshot.request", sample(request))
        try {
            read({ value -> complete(request, value, null) }, { error -> complete(request, null, error) })
        } catch (error: Exception) {
            complete(request, null, error)
        }
        return true
    }

    private fun sample(
        request: Request<L>,
        current: Boolean? = null,
        accepted: Boolean? = null,
        superseded: Boolean? = null,
        value: Int? = null,
        error: Exception? = null,
    ) = SocSnapshotTrace(
        request.binding.epoch,
        request.id,
        request.reason,
        request.revision,
        current,
        accepted,
        superseded,
        value,
        error?.javaClass?.simpleName,
    )

    private fun complete(
        request: Request<L>,
        value: Int?,
        error: Exception?,
    ) = synchronized(lock) {
        val current = binding === request.binding && request.binding.request === request
        val superseded = request.binding.revision != request.revision
        val accepted = current && !superseded
        if (current) request.binding.request = null
        trace(
            if (error != null) {
                "soc.snapshot.error"
            } else if (request.reason == "initial") {
                "soc.initialRead"
            } else {
                "soc.refreshRead"
            },
            sample(request, current, accepted, superseded, value, error),
        )
        if (accepted) {
            if (error == null) {
                observe(request.binding, value)
            } else {
                request.binding.sample = null
                onError(error)
            }
        }
    }
}
