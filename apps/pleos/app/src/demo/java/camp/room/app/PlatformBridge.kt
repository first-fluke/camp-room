package camp.room.app

import javax.inject.Inject
import dagger.hilt.android.scopes.ViewModelScoped
import org.json.JSONObject
import org.json.JSONArray

/** Explicit emulator demo flavor; real Pleos flavor supplies local SDK implementation. */
@ViewModelScoped
class PlatformBridge @Inject constructor(private val repository: CampRepository, private val assistant:AndroidAssistAdapter) {
    private fun withSyntheticLayout(vehicle: JSONObject): JSONObject {
        val groups = JSONArray()
        for (zone in listOf("front", "rear")) {
            val state = vehicle.optJSONObject(zone) ?: JSONObject()
            for (feature in listOf("readingLight", "ambientBrightness", "blindLevel")) {
                val raw = state.opt(feature)
                val observed = if (raw is Boolean) if (raw) 1.0 else 0.0 else (raw as? Number)?.toDouble()
                groups.put(JSONObject().put("areaId", "demo:$zone").put("kind", "demo").put("members", JSONArray(listOf("DEMO_${zone.uppercase()}_ZONE")))
                    .put("feature", feature).put("readable", true).put("writable", true).put("observed", observed ?: JSONObject.NULL))
            }
        }
        groups.put(JSONObject().put("areaId", "demo:front").put("kind", "demo").put("members", JSONArray(listOf("DEMO_FRONT_ZONE")))
            .put("feature", "targetTemperature").put("readable", true).put("writable", true).put("observed", vehicle.opt("targetTemperature")))
        return vehicle.put("layoutSource", "demo_synthetic").put("controlAreas", groups).put("unknownFeatures", JSONArray())
    }
    suspend fun vehicle(): JSONObject = withSyntheticLayout(repository.demoVehicle())
    suspend fun scene(sceneId: String): JSONObject = repository.demoScene(sceneId).also { it.put("vehicle", withSyntheticLayout(it.getJSONObject("vehicle"))) }
    suspend fun control(body: JSONObject): JSONObject = repository.demoControl(body).also { it.put("vehicle", withSyntheticLayout(it.getJSONObject("vehicle"))) }
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
