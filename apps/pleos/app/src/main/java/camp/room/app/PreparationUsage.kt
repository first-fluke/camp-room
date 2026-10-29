package camp.room.app

import kotlinx.serialization.Serializable

enum class PreparationUsageAction { CHECKED, PHASE_CHANGED, TEMPLATE_IMPORTED }

/** Optional local totals only; no trip, vehicle, user or event-level data. */
@Serializable
data class PreparationUsage(
    val optedIn: Boolean = false,
    val checkedItems: Int = 0,
    val phaseChanges: Int = 0,
    val templateImports: Int = 0,
) {
    /** Consent transitions start a new empty total; repeated settings preserve it. */
    fun withOptIn(enabled: Boolean): PreparationUsage = if (enabled == optedIn) this else PreparationUsage(optedIn = enabled)

    fun record(action: PreparationUsageAction): PreparationUsage {
        if (!optedIn) return this
        return when (action) {
            PreparationUsageAction.CHECKED -> copy(checkedItems = increment(checkedItems))
            PreparationUsageAction.PHASE_CHANGED -> copy(phaseChanges = increment(phaseChanges))
            PreparationUsageAction.TEMPLATE_IMPORTED -> copy(templateImports = increment(templateImports))
        }
    }

    private fun increment(value: Int): Int = if (value == Int.MAX_VALUE) value else value + 1
}
