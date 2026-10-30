package camp.room.app

internal data class CampTripReadKey(
    val campsiteId: String,
    val checkIn: String,
    val checkOut: String,
    val guests: Int,
) {
    companion object {
        fun from(trip: Trip?): CampTripReadKey? =
            trip?.let {
                CampTripReadKey(it.campsiteId, it.checkIn, it.checkOut, it.guests)
            }
    }
}

/** Request identity also rejects an older read of the same trip. */
internal class CampTripReadRequests {
    internal class Request internal constructor(
        val key: CampTripReadKey,
    )

    private var latest: Request? = null

    @Synchronized fun begin(key: CampTripReadKey): Request = Request(key).also { latest = it }

    @Synchronized fun isCurrent(
        request: Request,
        current: CampTripReadKey?,
    ): Boolean = latest === request && request.key == current

    @Synchronized fun invalidate() {
        latest = null
    }
}
