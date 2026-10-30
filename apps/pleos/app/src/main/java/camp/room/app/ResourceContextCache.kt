package camp.room.app

internal class ResourceContextCache<C : Any, V : Any>(
    private val copyConfiguration: (C) -> C,
) {
    private var language: String? = null
    private var configuration: C? = null
    private var value: V? = null

    @Synchronized
    fun get(
        language: String,
        configuration: C,
        create: () -> V,
    ): V {
        val cached = value
        if (cached != null && this.language == language && this.configuration == configuration) return cached
        // Snapshot before creation so a concurrent system update cannot label an
        // older context with the newer configuration.
        val nextConfiguration = copyConfiguration(configuration)
        val next = create()
        this.language = language
        this.configuration = nextConfiguration
        value = next
        return next
    }

    @Synchronized
    fun clear() {
        language = null
        configuration = null
        value = null
    }
}
