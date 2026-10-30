package camp.room.app

import kotlin.math.abs

enum class SdkReadOperation {
    CAP_HVAC_TARGET,
    CAP_HVAC_CURRENT,
    CAP_LIGHT_STATE,
    CAP_LIGHT_SWITCH,
    CAP_AMBIENT,
    CAP_SMART_GLASS,
    CAP_DOOR_POSITION,
    CAP_WINDOW_POSITION,
    GET_HVAC_TARGET,
    GET_HVAC_CURRENT,
    GET_LIGHT_STATE,
    GET_AMBIENT,
    GET_SMART_GLASS,
    GET_DOOR_POSITION,
    GET_WINDOW_POSITION,
}

data class SdkReadKey(
    val operation: SdkReadOperation,
    val context: String,
)

enum class SdkReadOutcome {
    SUCCESS,
    EMPTY,
    INVALID_VALUE,
    CALLBACK_ERROR,
    SYNCHRONOUS_ERROR,
    TIMEOUT,
    CANCELLED,
}

data class SdkReadAttempt(
    val id: Long,
    val key: SdkReadKey,
    val epoch: Int,
    val startedAtMillis: Long,
)

sealed class SdkReadAdmission {
    data class Allowed(
        val attempt: SdkReadAttempt,
    ) : SdkReadAdmission()

    data class Deferred(
        val reason: String,
        val retryAfterMillis: Long,
    ) : SdkReadAdmission()
}

data class SdkReadState(
    val lastOutcome: SdkReadOutcome?,
    val consecutiveFailures: Int,
    val retryAfterMillis: Long,
    val inFlight: Boolean,
)

enum class SdkReadCompletionAdmission { ACCEPTED, STALE_EPOCH, RESET, REPLACED, DUPLICATE }

/** Temporary per-key availability, using a monotonic clock and no SDK types. */
class SdkReadAvailability(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val baseBackoffMillis: Long = 2_000,
    private val maxBackoffMillis: Long = 15_000,
) {
    init {
        require(baseBackoffMillis > 0) { "Base backoff must be positive" }
        require(maxBackoffMillis >= baseBackoffMillis) { "Maximum backoff must cover the base" }
    }

    private data class Entry(
        var attempt: SdkReadAttempt? = null,
        var lastOutcome: SdkReadOutcome? = null,
        var consecutiveFailures: Int = 0,
        var retryAtMillis: Long? = null,
        var completedAttemptId: Long? = null,
    )

    private var currentEpoch = 0
    private var nextAttemptId = 0L
    private val entries = mutableMapOf<SdkReadKey, Entry>()

    /** Also invalidates outstanding attempts when the supplied epoch is unchanged. */
    @Synchronized
    fun reset(epoch: Int) {
        currentEpoch = epoch
        entries.clear()
    }

    @Synchronized
    fun begin(
        key: SdkReadKey,
        epoch: Int,
    ): SdkReadAdmission {
        if (epoch != currentEpoch) return SdkReadAdmission.Deferred("stale_epoch", 0)
        val entry = entries.getOrPut(key) { Entry() }
        if (entry.attempt != null) return SdkReadAdmission.Deferred("in_flight", 0)
        val now = clockMillis()
        val remaining = remainingBackoff(entry, now)
        if (remaining > 0) return SdkReadAdmission.Deferred("backoff", remaining)
        check(nextAttemptId < Long.MAX_VALUE) { "Read attempt identifiers exhausted" }
        val attempt = SdkReadAttempt(++nextAttemptId, key, epoch, now)
        entry.attempt = attempt
        entry.retryAtMillis = null
        return SdkReadAdmission.Allowed(attempt)
    }

    /** Returns false for an old epoch, a reset, or an already completed/replaced attempt. */
    @Synchronized
    fun finish(
        attempt: SdkReadAttempt,
        outcome: SdkReadOutcome,
    ): Boolean = complete(attempt, outcome) == SdkReadCompletionAdmission.ACCEPTED

    /** Classification and state mutation are one operation, so logging cannot race a new request. */
    @Synchronized
    fun complete(
        attempt: SdkReadAttempt,
        outcome: SdkReadOutcome,
    ): SdkReadCompletionAdmission {
        if (attempt.epoch != currentEpoch) return SdkReadCompletionAdmission.STALE_EPOCH
        val entry = entries[attempt.key] ?: return SdkReadCompletionAdmission.RESET
        if (entry.attempt != attempt) {
            return if (entry.attempt == null && entry.completedAttemptId == attempt.id) {
                SdkReadCompletionAdmission.DUPLICATE
            } else {
                SdkReadCompletionAdmission.REPLACED
            }
        }
        entry.attempt = null
        entry.completedAttemptId = attempt.id
        entry.lastOutcome = outcome
        entry.retryAtMillis = null
        when (outcome) {
            SdkReadOutcome.SUCCESS -> {
                entry.consecutiveFailures = 0
            }

            SdkReadOutcome.CANCELLED -> {
                Unit
            }

            SdkReadOutcome.EMPTY,
            SdkReadOutcome.INVALID_VALUE,
            SdkReadOutcome.CALLBACK_ERROR,
            SdkReadOutcome.SYNCHRONOUS_ERROR,
            SdkReadOutcome.TIMEOUT,
            -> {
                if (entry.consecutiveFailures < Int.MAX_VALUE) entry.consecutiveFailures++
                val delay = backoffMillis(entry.consecutiveFailures)
                val now = clockMillis()
                entry.retryAtMillis = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
            }
        }
        return SdkReadCompletionAdmission.ACCEPTED
    }

    @Synchronized
    fun state(
        key: SdkReadKey,
        epoch: Int,
    ): SdkReadState? {
        if (epoch != currentEpoch) return null
        val entry = entries[key] ?: return null
        return SdkReadState(
            entry.lastOutcome,
            entry.consecutiveFailures,
            remainingBackoff(entry, clockMillis()),
            entry.attempt != null,
        )
    }

    private fun remainingBackoff(
        entry: Entry,
        now: Long,
    ): Long {
        val retryAt = entry.retryAtMillis ?: return 0
        if (now >= retryAt) return 0
        val difference = retryAt - now
        return if (difference < 0) Long.MAX_VALUE else difference
    }

    private fun backoffMillis(failures: Int): Long {
        var delay = baseBackoffMillis
        var doublings = failures - 1
        while (doublings > 0 && delay < maxBackoffMillis) {
            delay = if (delay > maxBackoffMillis / 2) maxBackoffMillis else delay * 2
            doublings--
        }
        return delay
    }
}

data class SdkDestinationRequest(
    val poiId: String,
    val latitude: Double,
    val longitude: Double,
)

data class SdkRouteDestinationPoint(
    val poiId: String,
    val latitude: Double,
    val longitude: Double,
    val isDestination: Boolean,
)

enum class SdkDestinationMatch {
    MATCH,
    INVALID_REQUEST,
    STALE,
    MISSING_DESTINATION,
    AMBIGUOUS_DESTINATION,
    MISMATCH,
}

/** Matches a projected destination without logging coordinates or SDK objects. */
object SdkDestinationMatcher {
    const val MAX_DESTINATION_DISTANCE_METERS = 20.0
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    // Application policy; this epsilon only accommodates numeric boundary rounding.
    private const val DISTANCE_NUMERIC_EPSILON_METERS = 1e-6

    fun match(
        request: SdkDestinationRequest,
        points: List<SdkRouteDestinationPoint>,
        requestEpoch: Int,
        responseEpoch: Int,
    ): SdkDestinationMatch {
        if (requestEpoch != responseEpoch) return SdkDestinationMatch.STALE
        if (request.poiId.isBlank() || !validCoordinates(request.latitude, request.longitude)) {
            return SdkDestinationMatch.INVALID_REQUEST
        }
        val destinations = points.filter { it.isDestination }
        if (destinations.isEmpty()) return SdkDestinationMatch.MISSING_DESTINATION
        if (destinations.size != 1) return SdkDestinationMatch.AMBIGUOUS_DESTINATION
        val destination = destinations.single()
        return if (
            destination.poiId == request.poiId &&
            isWithinDestinationDistance(distanceMeters(request, destination))
        ) {
            SdkDestinationMatch.MATCH
        } else {
            SdkDestinationMatch.MISMATCH
        }
    }

    fun distanceMeters(
        request: SdkDestinationRequest,
        destination: SdkRouteDestinationPoint,
    ): Double? {
        if (!validCoordinates(request.latitude, request.longitude) ||
            !validCoordinates(destination.latitude, destination.longitude)
        ) {
            return null
        }
        val radiansPerDegree = kotlin.math.PI / 180.0
        val latitude = request.latitude * radiansPerDegree
        val destinationLatitude = destination.latitude * radiansPerDegree
        val halfLatitudeSine = kotlin.math.sin(abs(destination.latitude - request.latitude) * radiansPerDegree / 2.0)
        val halfLongitudeSine = kotlin.math.sin(abs(destination.longitude - request.longitude) * radiansPerDegree / 2.0)
        val haversine =
            (
                halfLatitudeSine * halfLatitudeSine +
                    kotlin.math.cos(latitude) * kotlin.math.cos(destinationLatitude) *
                    halfLongitudeSine * halfLongitudeSine
            ).coerceIn(0.0, 1.0)
        return 2.0 * EARTH_RADIUS_METERS *
            kotlin.math.atan2(
                kotlin.math.sqrt(haversine),
                kotlin.math.sqrt(1.0 - haversine),
            )
    }

    fun isWithinDestinationDistance(distanceMeters: Double?): Boolean =
        distanceMeters != null && distanceMeters.isFinite() && distanceMeters >= 0.0 &&
            distanceMeters <= MAX_DESTINATION_DISTANCE_METERS + DISTANCE_NUMERIC_EPSILON_METERS

    private fun validCoordinates(
        latitude: Double,
        longitude: Double,
    ): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
}
