package camp.room.app

import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import javax.inject.Inject

/** Device services remain available; an emulator never claims a real vehicle connection. */
@ViewModelScoped
class PlatformBridge
    @Inject
    constructor(
        private val assistant: AndroidAssistAdapter,
    ) {
        suspend fun vehicle(admitted: () -> Boolean = { true }): JSONObject {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            return if (admitted()) {
                JSONObject().put("mode", "demo").put("connected", false)
            } else {
                JSONObject().put("mode", "unknown").put("connected", false)
            }
        }

        private fun unavailable() =
            JSONObject()
                .put("status", "unsupported")
                .put("mode", "demo")
                .put("started", false)
                .put("message", CampText.get(R.string.vehicle_control_connection_needed))

        suspend fun scene(sceneId: String): JSONObject = unavailable()

        suspend fun control(body: JSONObject): JSONObject = unavailable()

        suspend fun navigationPreview(
            site: Campsite,
            admitted: () -> Boolean = { true },
        ): JSONObject {
            if (!observationStillAdmitted(admitted)) return unavailable().put("reason", "stale_admission")
            return unavailable()
        }

        suspend fun navigationStart(site: Campsite): JSONObject = unavailable()

        suspend fun location() = assistant.location()

        suspend fun arrivalWatch(site: Campsite) = assistant.arrivalWatch(site)

        suspend fun speak(text: String) = assistant.speak(text)

        suspend fun listen() = assistant.listen()

        fun setLanguage(tag: String) = assistant.setLanguage(tag)

        fun assistStatus() = assistant.assistStatus()

        fun start() = assistant.start()

        fun stop() = assistant.stop()

        fun close() = assistant.close()
    }
