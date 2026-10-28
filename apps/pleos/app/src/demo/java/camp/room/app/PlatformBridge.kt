package camp.room.app

import javax.inject.Inject
import dagger.hilt.android.scopes.ViewModelScoped
import org.json.JSONObject

/** Device services remain available; an emulator never claims a real vehicle connection. */
@ViewModelScoped
class PlatformBridge @Inject constructor(private val assistant: AndroidAssistAdapter) {
    suspend fun vehicle(): JSONObject = JSONObject().put("mode", "demo").put("connected", false)

    private fun unavailable() = JSONObject()
        .put("status", "unsupported")
        .put("mode", "demo")
        .put("started", false)
        .put("message", CampText.get(R.string.vehicle_control_connection_needed))

    suspend fun scene(sceneId: String): JSONObject = unavailable()
    suspend fun control(body: JSONObject): JSONObject = unavailable()
    suspend fun navigationPreview(site: Campsite): JSONObject = unavailable()
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
