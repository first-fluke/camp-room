package camp.room.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class CampRoomApplication : Application() {
    @javax.inject.Inject lateinit var telemetry: camp.room.app.observability.Telemetry

    override fun onCreate() {
        super.onCreate()
        CampText.initialize(this)
        telemetry.initialize()
    }
}
