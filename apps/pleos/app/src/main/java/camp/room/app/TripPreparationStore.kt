package camp.room.app

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** App-private, local-only user preparation state. No telemetry/export port. */
internal class TripPreparationStore(
    context: Context,
) {
    private companion object {
        val lock = Any()
    }

    val preferences = context.applicationContext.getSharedPreferences("camp_room_trip_preparation_v1", Context.MODE_PRIVATE)
    private val codec =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private fun key(tripKey: String) = "trip." + preparationTripToken(tripKey)

    fun snapshot(tripKey: String): TripPreparationSnapshot =
        synchronized(lock) {
            val encoded = preferences.getString(key(tripKey), null) ?: return@synchronized TripPreparationSnapshot()
            runCatching { codec.decodeFromString<TripPreparationSnapshot>(encoded) }.getOrDefault(TripPreparationSnapshot())
        }

    /** User transformations always apply to the latest stored record, not a captured composition. */
    fun update(
        tripKey: String,
        transform: (TripPreparation) -> TripPreparation,
    ): TripPreparationSnapshot =
        synchronized(lock) {
            val current = snapshot(tripKey)
            val next = transform(current.record)
            if (next == current.record || current.revision == Long.MAX_VALUE) return@synchronized current
            TripPreparationSnapshot(current.revision + 1, next).also { write(tripKey, it) }
        }

    fun reconcile(
        tripKey: String,
        expected: TripPreparationSnapshot,
        desired: TripPreparation,
    ): TripPreparationSnapshot? =
        synchronized(lock) {
            TripPreparationRules.reconcileIfCurrent(snapshot(tripKey), expected, desired)?.also { write(tripKey, it) }
        }

    private fun write(
        tripKey: String,
        value: TripPreparationSnapshot,
    ) {
        preferences.edit().putString(key(tripKey), codec.encodeToString(value)).apply()
    }

    fun template(): NextTripPreparation? {
        val encoded = preferences.getString("next_trip", null) ?: return null
        return runCatching { codec.decodeFromString<NextTripPreparation>(encoded) }.getOrNull()
    }

    fun saveTemplate(value: NextTripPreparation) {
        preferences.edit().putString("next_trip", codec.encodeToString(value)).apply()
    }

    fun usage(): PreparationUsage {
        val encoded = preferences.getString("local_usage", null) ?: return PreparationUsage()
        return runCatching { codec.decodeFromString<PreparationUsage>(encoded) }.getOrDefault(PreparationUsage())
    }

    fun saveUsage(value: PreparationUsage) {
        preferences.edit().putString("local_usage", codec.encodeToString(value)).apply()
    }
}
