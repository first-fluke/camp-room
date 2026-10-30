package camp.room.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ResourceContextCacheTest {
    private data class Settings(
        var scale: Int = 1,
        var night: Boolean = false,
    )

    @Test fun repeatedLabelsReuseOneContext() {
        val cache = ResourceContextCache<Settings, Any> { it.copy() }
        val settings = Settings()
        var creations = 0
        val contexts =
            List(500) {
                cache.get("ko", settings) {
                    creations++
                    Any()
                }
            }
        assertEquals(1, creations)
        contexts.forEach { assertSame(contexts.first(), it) }
    }

    @Test fun languageAndInPlaceConfigurationChangesInvalidateContext() {
        val cache = ResourceContextCache<Settings, Any> { it.copy() }
        val settings = Settings()
        val korean = cache.get("ko", settings) { Any() }
        val english = cache.get("en", settings) { Any() }
        assertNotSame(korean, english)
        settings.scale = 2
        val scaled = cache.get("en", settings) { Any() }
        assertNotSame(english, scaled)
        settings.night = true
        val night = cache.get("en", settings) { Any() }
        assertNotSame(scaled, night)
        assertSame(night, cache.get("en", settings.copy()) { Any() })
    }

    @Test fun applicationReinitializationDiscardsPreviousContext() {
        val cache = ResourceContextCache<Settings, Any> { it.copy() }
        val settings = Settings()
        val previous = cache.get("ko", settings) { Any() }
        cache.clear()
        assertNotSame(previous, cache.get("ko", settings) { Any() })
    }

    @Test fun configurationChangedDuringCreationDoesNotCacheAnOldContextUnderTheNewKey() {
        val cache = ResourceContextCache<Settings, Any> { it.copy() }
        val settings = Settings()
        val previous =
            cache.get("ko", settings) {
                settings.scale = 2
                Any()
            }
        assertNotSame(previous, cache.get("ko", settings) { Any() })
    }
}
