package camp.room.app.di

import camp.room.app.BuildConfig
import camp.room.client.KtorApiJson
import camp.room.generated.api.DefaultApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.Locale
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    @Provides @Singleton
    fun provideJson(): Json = KtorApiJson.create()

    @Provides @Singleton
    fun provideApi(codec: Json): DefaultApi =
        DefaultApi(
            baseUrl = BuildConfig.API_BASE_URL.trimEnd('/').removeSuffix("/api/v1"),
            httpClientEngine = OkHttp.create(),
            httpClientConfig = { config ->
                // Pinned upstream jvm-ktor templates expose this hook; no template fork is needed.
                config.install(ContentNegotiation) { json(codec) }
                config.install(HttpTimeout) {
                    requestTimeoutMillis = 20000
                    connectTimeoutMillis = 12000
                    socketTimeoutMillis = 20000
                }
                config.defaultRequest { header("Accept-Language", Locale.getDefault().toLanguageTag()) }
                config.expectSuccess = false
            },
        )
}
