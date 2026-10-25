package camp.room.app

import android.content.Context
import org.json.JSONObject

/** Explicit emulator demo flavor; real Pleos flavor supplies local SDK implementation. */
class PlatformBridge(context: Context, private val repository: CampRepository) {
    private val assistant=AndroidAssistAdapter(context)
    suspend fun vehicle(): JSONObject = repository.request("/vehicle")
    suspend fun scene(sceneId: String): JSONObject = repository.request("/vehicle/scenes", "POST", JSONObject().put("sceneId", sceneId))
    suspend fun control(body: JSONObject): JSONObject = repository.request("/vehicle/controls", "POST", body)
    suspend fun navigationPreview(site: Campsite): JSONObject = repository.request("/navigation/preview", "POST", JSONObject().put("campsiteId", site.id))
    suspend fun navigationStart(site: Campsite): JSONObject = repository.request("/navigation/start", "POST", JSONObject().put("campsiteId", site.id))
    suspend fun location()=assistant.location()
    suspend fun arrivalWatch(site:Campsite)=assistant.arrivalWatch(site)
    suspend fun speak(text:String)=assistant.speak(text)
    suspend fun listen()=assistant.listen()
    fun assistStatus()=assistant.assistStatus()
    fun start()=assistant.start()
    fun stop()=assistant.stop()
    fun close()=assistant.close()
}
