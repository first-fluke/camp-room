package camp.room.app

/** UI availability is independent of the separate vehicle-actuation motion contract. */
data class CampUxState(
    val observed: Boolean = false,
    val requiresOptimization: Boolean = true,
    val activeRestrictions: Int = 0,
) {
    // This application does not declare distraction optimization. Keep its planning,
    // shopping, and cabin-editing surfaces unavailable whenever the host restricts UI.
    val allowsInteraction: Boolean
        get() = observed && !requiresOptimization
}
