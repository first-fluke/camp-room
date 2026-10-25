package camp.room.app

import javax.inject.Inject
import dagger.hilt.android.scopes.ViewModelScoped
import org.json.JSONObject

/** Explicit emulator demo flavor; real Pleos flavor supplies local SDK implementation. */
@ViewModelScoped
class PlatformBridge @Inject constructor(private val repository: CampRepository, private val assistant:AndroidAssistAdapter) {
    suspend fun vehicle(): JSONObject = repository.demoVehicle()
    suspend fun scene(sceneId: String): JSONObject = repository.demoScene(sceneId)
    suspend fun control(body: JSONObject): JSONObject = repository.demoControl(body)
    suspend fun navigationPreview(site: Campsite): JSONObject = repository.demoNavigationPreview(site.id)
    suspend fun navigationStart(site: Campsite): JSONObject = repository.demoNavigationStart(site.id)
    suspend fun location()=assistant.location()
    suspend fun arrivalWatch(site:Campsite)=assistant.arrivalWatch(site)
    suspend fun speak(text:String)=assistant.speak(text)
    suspend fun listen()=assistant.listen()
    fun setLanguage(tag:String)=assistant.setLanguage(tag)
    fun assistStatus()=assistant.assistStatus()
    fun start()=assistant.start()
    fun stop()=assistant.stop()
    fun close()=assistant.close()
}
