package camp.room.app.di

import camp.room.app.observability.CampTelemetry
import camp.room.app.observability.Telemetry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TelemetryModule {
    @Binds @Singleton abstract fun bindTelemetry(value:CampTelemetry):Telemetry
}
