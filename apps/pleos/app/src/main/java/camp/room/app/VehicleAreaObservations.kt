package camp.room.app

enum class VehicleAreaKind { DOOR, WINDOW }

/** The native area remains one identity even when it represents several members. */
data class VehicleAreaKey(
    val kind: VehicleAreaKind,
    val nativeAreaId: String,
)

data class VehicleAreaConfig(
    val key: VehicleAreaKey,
    val members: Set<String>,
    val minPosition: Int?,
    val maxPosition: Int?,
    val access: String,
    val readable: Boolean = true,
)

enum class VehicleAreaStatus {
    UNKNOWN,
    OBSERVED,
    EMPTY,
    INVALID_BOUNDS,
    OUT_OF_RANGE,
    UNAVAILABLE,
    ERROR,
    STALE,
}

data class VehicleAreaObservation(
    val key: VehicleAreaKey,
    val members: Set<String>,
    val rawPosition: Int?,
    val minPosition: Int?,
    val maxPosition: Int?,
    val access: String,
    val status: VehicleAreaStatus,
    val reason: String?,
    /** Monotonic sample time, not a wall-clock date or a successful read claim. */
    val sampledAtMillis: Long?,
    val updatedAtMillis: Long,
    val epoch: Int,
    /** Numeric endpoint semantics are unverified; minimum does not mean closed. */
    val closed: Boolean? = null,
) {
    val kind: VehicleAreaKind get() = key.kind
    val nativeAreaId: String get() = key.nativeAreaId
}

data class VehicleAreaReadToken internal constructor(
    val key: VehicleAreaKey,
    val epoch: Int,
    val id: Long,
    val startedAtMillis: Long,
    internal val generation: Long,
)

/**
 * Read-only bookkeeping with no SDK calls or endpoint-to-closure assumptions.
 *
 * Supplied sample times must use [clockMillis]'s monotonic time base. The caller
 * must capture its lifecycle epoch in subscription callbacks and change it on
 * reconnect/stop; reset also invalidates outstanding getter tokens when the
 * supplied epoch happens to be unchanged.
 */
class VehicleAreaObservations(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxAgeMillis: Long = 15_000,
) {
    init {
        require(maxAgeMillis > 0) { "Maximum sample age must be positive" }
    }

    private data class Entry(
        val config: VehicleAreaConfig,
        var observation: VehicleAreaObservation,
        var read: VehicleAreaReadToken? = null,
        var lastOrderedSampleAtMillis: Long? = null,
    )

    private var currentEpoch = 0
    private var generation = 0L
    private var nextReadId = 0L
    private val entries = linkedMapOf<VehicleAreaKey, Entry>()

    @Synchronized
    fun reset(epoch: Int) {
        invalidate()
        currentEpoch = epoch
    }

    /** Replaces the complete capability set; an ambiguous set leaves no old values. */
    @Synchronized
    fun configure(
        configs: List<VehicleAreaConfig>,
        epoch: Int,
    ): Boolean {
        if (epoch != currentEpoch) return false
        invalidate()
        if (configs.map { it.key }.toSet().size != configs.size) return false
        val now = clockMillis()
        for (supplied in configs) {
            val config = supplied.copy(members = supplied.members.toSet())
            val unavailable = unavailableReason(config)
            entries[config.key] =
                Entry(
                    config,
                    VehicleAreaObservation(
                        key = config.key,
                        members = config.members.toSet(),
                        rawPosition = null,
                        minPosition = config.minPosition,
                        maxPosition = config.maxPosition,
                        access = config.access,
                        status = if (unavailable != null) VehicleAreaStatus.UNAVAILABLE else VehicleAreaStatus.UNKNOWN,
                        reason = unavailable ?: "not_observed",
                        sampledAtMillis = null,
                        updatedAtMillis = now,
                        epoch = epoch,
                    ),
                )
        }
        return true
    }

    /** A second concurrent getter for the same area is not admitted. */
    @Synchronized
    fun beginRead(
        key: VehicleAreaKey,
        epoch: Int,
    ): VehicleAreaReadToken? {
        if (epoch != currentEpoch) return null
        val entry = entries[key] ?: return null
        if (entry.read != null || unavailableReason(entry.config) != null) return null
        check(nextReadId < Long.MAX_VALUE) { "Area read identifiers exhausted" }
        return VehicleAreaReadToken(key, epoch, ++nextReadId, clockMillis(), generation).also {
            entry.read = it
        }
    }

    /** Returns false for a consumed token, reconfiguration, or a newer event. */
    @Synchronized
    fun completeRead(
        token: VehicleAreaReadToken,
        rawPosition: Int?,
        sampledAtMillis: Long = clockMillis(),
    ): Boolean {
        val entry = currentEntry(token) ?: return false
        entry.read = null
        return observe(entry, rawPosition, sampledAtMillis)
    }

    /** Cancellation releases only this read; it is not a failed SDK observation. */
    @Synchronized
    fun cancelRead(token: VehicleAreaReadToken): Boolean {
        val entry = currentEntry(token) ?: return false
        entry.read = null
        return true
    }

    /** A current event supersedes the outstanding getter for this area only. */
    @Synchronized
    fun onEvent(
        key: VehicleAreaKey,
        epoch: Int,
        rawPosition: Int?,
        sampledAtMillis: Long = clockMillis(),
    ): Boolean {
        if (epoch != currentEpoch) return false
        val entry = entries[key] ?: return false
        if (!observe(entry, rawPosition, sampledAtMillis)) return false
        entry.read = null
        return true
    }

    /** Keeps the last raw evidence and its original sample time, but marks failure. */
    @Synchronized
    fun fail(
        token: VehicleAreaReadToken,
        reason: String,
    ): Boolean {
        val entry = currentEntry(token) ?: return false
        entry.read = null
        markFailure(entry, reason)
        return true
    }

    /** Subscription/service failure, distinct from a getter's token-bound failure. */
    @Synchronized
    fun failArea(
        key: VehicleAreaKey,
        epoch: Int,
        reason: String,
    ): Boolean {
        if (epoch != currentEpoch) return false
        val entry = entries[key] ?: return false
        entry.read = null
        markFailure(entry, reason)
        return true
    }

    @Synchronized
    fun snapshot(epoch: Int): List<VehicleAreaObservation> {
        if (epoch != currentEpoch) return emptyList()
        val now = clockMillis()
        return entries.values.map { entry ->
            val sample = entry.observation
            val sampledAt = sample.sampledAtMillis
            val displayed =
                if (sample.status == VehicleAreaStatus.OBSERVED && sampledAt != null) {
                    when {
                        now < sampledAt -> sample.copy(status = VehicleAreaStatus.STALE, reason = "clock_moved_backwards", closed = null)

                        ageExceedsLimit(
                            now,
                            sampledAt,
                        ) -> sample.copy(status = VehicleAreaStatus.STALE, reason = "stale_sample", closed = null)

                        else -> sample
                    }
                } else {
                    sample
                }
            displayed.copy(members = displayed.members.toSet())
        }
    }

    /** Empty, partial, invalid, old, or semantically unverified data is unknown. */
    @Synchronized
    fun allClosed(
        kind: VehicleAreaKind,
        epoch: Int,
    ): Boolean? {
        val areas = snapshot(epoch).filter { it.kind == kind }
        if (areas.isEmpty() || areas.any { it.status != VehicleAreaStatus.OBSERVED || it.closed == null }) return null
        return areas.all { it.closed == true }
    }

    private fun invalidate() {
        check(generation < Long.MAX_VALUE) { "Area observation generations exhausted" }
        generation++
        entries.clear()
    }

    private fun currentEntry(token: VehicleAreaReadToken): Entry? {
        if (token.epoch != currentEpoch || token.generation != generation) return null
        return entries[token.key]?.takeIf { it.read == token }
    }

    private fun markFailure(
        entry: Entry,
        reason: String,
    ) {
        entry.observation =
            entry.observation.copy(
                status = VehicleAreaStatus.ERROR,
                reason = reason.ifBlank { "read_failed" },
                updatedAtMillis = clockMillis(),
                closed = null,
            )
    }

    private fun observe(
        entry: Entry,
        rawPosition: Int?,
        sampledAtMillis: Long,
    ): Boolean {
        val previousAt = entry.lastOrderedSampleAtMillis
        if (previousAt != null && sampledAtMillis < previousAt) return false
        val now = clockMillis()
        val config = entry.config
        val unavailable = unavailableReason(config)
        val min = config.minPosition
        val max = config.maxPosition
        val (status, reason) =
            when {
                sampledAtMillis > now -> VehicleAreaStatus.ERROR to "future_sample_time"
                unavailable != null -> VehicleAreaStatus.UNAVAILABLE to unavailable
                rawPosition == null -> VehicleAreaStatus.EMPTY to "null_position"
                min == null || max == null -> VehicleAreaStatus.INVALID_BOUNDS to "missing_bounds"
                min >= max -> VehicleAreaStatus.INVALID_BOUNDS to "invalid_bounds"
                rawPosition < min || rawPosition > max -> VehicleAreaStatus.OUT_OF_RANGE to "position_out_of_range"
                ageExceedsLimit(now, sampledAtMillis) -> VehicleAreaStatus.STALE to "stale_sample"
                else -> VehicleAreaStatus.OBSERVED to null
            }
        if (sampledAtMillis <= now) entry.lastOrderedSampleAtMillis = sampledAtMillis
        entry.observation =
            entry.observation.copy(
                rawPosition = rawPosition,
                status = status,
                reason = reason,
                sampledAtMillis = sampledAtMillis,
                updatedAtMillis = now,
                closed = null,
            )
        return true
    }

    private fun unavailableReason(config: VehicleAreaConfig): String? =
        when {
            config.key.nativeAreaId.isBlank() -> "missing_native_area_id"
            config.members.isEmpty() || config.members.any { it.isBlank() } -> "missing_area_members"
            !config.readable -> "read_not_supported"
            else -> null
        }

    private fun ageExceedsLimit(
        now: Long,
        sampledAtMillis: Long,
    ): Boolean {
        val age = now - sampledAtMillis
        return age < 0 || age > maxAgeMillis
    }
}
