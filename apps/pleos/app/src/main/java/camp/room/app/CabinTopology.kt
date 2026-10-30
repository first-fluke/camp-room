package camp.room.app

/** Coordinates returned by one feature, never a seat inventory or a synthesized SDK area. */
data class CabinArea(
    val kind: String,
    val members: List<String>,
    val targetId: String? = null,
) {
    val id: String get() = targetId ?: (kind + ":" + members.joinToString("+").ifEmpty { "<unspecified>" })
    val zone: String? get() =
        when {
            members.isNotEmpty() && members.all { it.startsWith("ROW_1_") } -> "front"
            members.isNotEmpty() && members.all { it.startsWith("ROW_2_") || it.startsWith("ROW_3_") } -> "rear"
            else -> null
        }
}

data class ExactCabinTarget<T>(
    val feature: String,
    val area: CabinArea,
    val native: T,
)

/** The original native area object remains attached to each feature/area key. */
class CabinTargetIndex<T> {
    private val entries = linkedMapOf<Pair<String, String>, ExactCabinTarget<T>>()

    fun put(
        feature: String,
        area: CabinArea,
        native: T,
    ) {
        val base = area.id + "|" + feature
        var id = base
        var occurrence = 1
        while (entries.containsKey(feature to id)) {
            occurrence++
            id = "$base#$occurrence"
        }
        entries[feature to id] = ExactCabinTarget(feature, area.copy(targetId = id), native)
    }

    fun get(
        feature: String,
        areaId: String,
    ): T? = entries[feature to areaId]?.native

    fun clear() {
        entries.clear()
    }

    fun all(): List<ExactCabinTarget<T>> = entries.values.toList()

    fun targets(
        feature: String,
        zone: String,
    ): List<ExactCabinTarget<T>> = all().filter { it.feature == feature && it.area.zone == zone }

    fun ambiguous(feature: String): List<ExactCabinTarget<T>> = all().filter { it.feature == feature && it.area.zone == null }
}

data class CabinControlGroup(
    val area: CabinArea,
    val feature: String,
    val readable: Boolean,
    val writable: Boolean,
    val observed: Double?,
)
