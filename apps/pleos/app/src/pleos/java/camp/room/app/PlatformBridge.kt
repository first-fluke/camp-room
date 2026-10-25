package camp.room.app

import javax.inject.Inject
import javax.inject.Provider
import dagger.hilt.android.scopes.ViewModelScoped
import ai.pleos.playground.vehicle.Vehicle as SdkVehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.HvacTemperatureConfigArray
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RoutePointPoiType
import ai.pleos.playground.navi.constants.RoutePointType
import kotlinx.coroutines.CancellationException
import camp.room.pleos.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Real vehicle flavor: local Vehicle/NaviHelper SDK only for vehicle/navigation.
 * Backend remains responsible for campsite/weather/cart/order APIs.
 * Host stationary signal defaults UNKNOWN until a certified host supplies it.
 */
@ViewModelScoped
class PlatformBridge @Inject constructor(
    private val sdk:SdkVehicle,
    private val navigationFactory:Provider<NaviHelper>,
    private val assistant:PleosAssistAdapter,
    private val hostMotion:MotionProvider,
) {
    private val guard = Mutex()
    private val discoveryGuard = Mutex()
    private val topologyLock = Any()
    private data class NativeTarget(val capability: Capability, val seat: SeatArea? = null, val window: WindowArea? = null, val readSeat: SeatArea? = seat)
    private data class CapabilitySnapshot(val targets: CabinTargetIndex<NativeTarget> = CabinTargetIndex(), val queried: Set<Control> = emptySet())
    @Volatile private var capabilitySnapshot = CapabilitySnapshot()
    private val targets get() = capabilitySnapshot.targets
    private val queried get() = capabilitySnapshot.queried
    private val supportedControls = listOf(Control.TARGET_TEMPERATURE, Control.READING_LIGHT, Control.AMBIENT_BRIGHTNESS, Control.SMART_GLASS)
    @Volatile private var soc: Int? = null
    @Volatile private var socObservedAt: String? = null
    @Volatile private var diagnostic = ""
    @Volatile private var active = false
    @Volatile private var closed = false
    @Volatile private var connectionEpoch = 0
    private val adapter = PleosVehicleAdapter(sdk, { control, areaId -> targets.get(control.name, areaId)?.seat },
        { control, areaId -> targets.get(control.name, areaId)?.readSeat },
        { areaId -> targets.get(Control.SMART_GLASS.name, areaId)?.window },
        TemperatureUnit.CELSIUS, { if (it) LightSwitch.ON else LightSwitch.OFF },
        { when(it) { LightState.ON -> true; LightState.OFF -> false; else -> null } },
        CapabilityRegistry { control, areaId -> targets.get(control.name, areaId)?.capability }, hostMotion,
        { soc = it; socObservedAt = Instant.now().toString() }, { soc = null; socObservedAt = null; diagnostic = "차량 상태 조회: ${it.javaClass.simpleName}" })
    private var helper: NaviHelper? = null
    private var navigation: PleosNavigationAdapter? = null
    private var naviListener: NaviHelperEventListener? = null
    private var tripResult: ((TripInfo) -> Unit)? = null
    private var routeResult: (() -> Unit)? = null
    private var navigationError: (() -> Unit)? = null
    private var navigationGeneration = 0

    private suspend fun <T> read(call: ((T) -> Unit, (Exception) -> Unit) -> Unit): T? =
        withTimeoutOrNull(2200) {
            suspendCancellableCoroutine { continuation ->
                try { call({ if (continuation.isActive) continuation.resume(it) }, {
                    diagnostic = "차량 기능 조회: ${it.javaClass.simpleName}"
                    if (continuation.isActive) continuation.resume(null)
                }) } catch (error: Exception) {
                    diagnostic = "차량 연결 확인 필요: ${error.javaClass.simpleName}"
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    private fun readable(config: CapabilityConfig) = config.accessType.name in setOf("READ", "READ_WRITE")
    private fun writable(config: CapabilityConfig) = config.accessType.name in setOf("WRITE", "READ_WRITE")
    private fun descriptor(area: SeatArea) = CabinArea("seat", area.values.map { it.name })
    private fun descriptor(area: WindowArea) = CabinArea("window", area.values.map { it.name })
    private data class TemperatureCaps(val config: CapabilityConfig, val areas: Map<SeatArea, AreaBounds<Float>>, val steps: HvacTemperatureConfigArray?)
    private suspend fun ensure() {
        discoveryGuard.withLock {
            if (closed) return
            val epoch = connectionEpoch
            if (!active) {
                adapter.start()
                val accepted = synchronized(topologyLock) {
                    if (closed || epoch != connectionEpoch) false else { active = true; true }
                }
                if (!accepted) { adapter.stop(); return }
            }
            if (queried.isNotEmpty()) return
            fun current() = active && !closed && epoch == connectionEpoch
            val discovered = CabinTargetIndex<NativeTarget>()
            val discoveredQueries = mutableSetOf<Control>()
            fun add(control: Control, area: SeatArea, capability: Capability, readArea: SeatArea? = area) {
                discovered.put(control.name, descriptor(area), NativeTarget(capability, seat = area, readSeat = readArea))
            }
            val temperature = read<TemperatureCaps> { done, fail ->
                sdk.getHVAC().checkHvacTemperatureCapabilities({ config, areas, steps -> done(TemperatureCaps(config, areas, steps)) }, fail)
            }
            if (!current()) return
            if (temperature != null) discoveredQueries += Control.TARGET_TEMPERATURE
            temperature?.areas?.forEach { (area, bounds) ->
                add(Control.TARGET_TEMPERATURE, area, Capability(readable(temperature.config), writable(temperature.config),
                    minimum = bounds.minValue.toDouble(), maximum = bounds.maxValue.toDouble(), increment = temperature.steps?.incrementCelsius?.toDouble()))
            }
            val measured = read<Pair<CapabilityConfig, List<SeatArea>>> { done, fail ->
                sdk.getHVAC().checkHvacTemperatureCurrentCapabilities({ config, areas -> done(config to areas) }, fail)
            }
            if (!current()) return
            if (measured != null) discoveredQueries += Control.CURRENT_TEMPERATURE
            measured?.second?.forEach { area -> add(Control.CURRENT_TEMPERATURE, area, Capability(readable(measured.first), false)) }
            val state = read<Pair<CapabilityConfig, Map<SeatArea, List<LightState>>>> { done, fail ->
                sdk.getLight().checkReadingLightStateCapabilities({ config, areas -> done(config to areas) }, fail)
            }
            if (!current()) return
            val switches = read<Pair<CapabilityConfig, Map<SeatArea, List<LightSwitch>>>> { done, fail ->
                sdk.getLight().checkReadingLightSwitchCapabilities({ config, areas -> done(config to areas) }, fail)
            }
            if (!current()) return
            if (state != null || switches != null) discoveredQueries += Control.READING_LIGHT
            switches?.second?.forEach { (area, values) ->
                // Only the SDK map-key equality contract may pair state/switch areas.
                val readArea = state?.second?.keys?.firstOrNull { it == area }
                val canRead = state != null && readArea != null && readable(state.first)
                add(Control.READING_LIGHT, area, Capability(canRead, writable(switches.first),
                    allowedValues = buildSet { if (LightSwitch.OFF in values) add(0.0); if (LightSwitch.ON in values) add(1.0) }), readArea)
            }
            state?.second?.forEach { (area, _) ->
                if (discovered.all().none { it.feature == Control.READING_LIGHT.name && it.native.readSeat == area })
                    add(Control.READING_LIGHT, area, Capability(readable(state.first), false))
            }
            val ambient = read<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>> { done, fail ->
                sdk.getLight().checkAmbientLightBrightnessCapabilities({ config, areas -> done(config to areas) }, fail)
            }
            if (!current()) return
            if (ambient != null) discoveredQueries += Control.AMBIENT_BRIGHTNESS
            ambient?.second?.forEach { (area, bounds) ->
                add(Control.AMBIENT_BRIGHTNESS, area, Capability(readable(ambient.first), writable(ambient.first), minimum = bounds.minValue.toDouble(), maximum = bounds.maxValue.toDouble(), increment = 1.0))
            }
            val glass = read<Pair<CapabilityConfig, Map<WindowArea, AreaBounds<Int>>>> { done, fail ->
                sdk.getWindow().checkSmartGlassLevelCapabilities({ config, areas -> done(config to areas) }, fail)
            }
            if (!current()) return
            if (glass != null) discoveredQueries += Control.SMART_GLASS
            glass?.second?.forEach { (area, bounds) ->
                val descriptor = descriptor(area)
                discovered.put(Control.SMART_GLASS.name, descriptor,
                    NativeTarget(Capability(readable(glass.first), writable(glass.first), minimum = bounds.minValue.toDouble(), maximum = bounds.maxValue.toDouble(), increment = 1.0), window = area))
            }
            synchronized(topologyLock) {
                if (current()) capabilitySnapshot = CapabilitySnapshot(discovered, discoveredQueries.toSet())
            }
        }
    }

    private fun unknown() = JSONObject().put("mode", "pleos").put("motionState", hostMotion.current().name.lowercase())
        .put("soc", soc ?: JSONObject.NULL).put("socObservedAt", socObservedAt ?: JSONObject.NULL).put("cabinTemperature", JSONObject.NULL).put("targetTemperature", JSONObject.NULL)
        .put("front", JSONObject().put("readingLight", JSONObject.NULL).put("ambientBrightness", JSONObject.NULL).put("blindLevel", JSONObject.NULL))
        .put("rear", JSONObject().put("readingLight", JSONObject.NULL).put("ambientBrightness", JSONObject.NULL).put("blindLevel", JSONObject.NULL))
        .put("doorsClosed", JSONObject.NULL).put("windowsClosed", JSONObject.NULL).put("lastSceneId", JSONObject.NULL)
        .put("updatedAt", Instant.now().toString()).put("message", diagnostic)

    suspend fun vehicle(): JSONObject = guard.withLock {
        try { ensure() } catch(cancelled: CancellationException) { throw cancelled } catch(error: Exception) { soc=null; socObservedAt=null; diagnostic = "Pleos vehicle connection/permission: ${error.javaClass.simpleName}" }
        val epoch = connectionEpoch
        val result = unknown().put("layoutSource", "sdk_capabilities")
        val snapshot = capabilitySnapshot
        val groups = JSONArray()
        val observations = mutableMapOf<Pair<String, String>, Any?>()
        for (entry in snapshot.targets.all()) {
            val cap = entry.native.capability
            val control = Control.valueOf(entry.feature)
            val raw: Any? = if (!active || !cap.readable) null else when (control) {
                Control.CURRENT_TEMPERATURE -> entry.native.readSeat?.let { area -> read<Float?> { done, fail -> sdk.getHVAC().getHvacTemperatureCurrent(area, TemperatureUnit.CELSIUS, done, fail) } }
                Control.TARGET_TEMPERATURE -> entry.native.seat?.let { area -> read<Float?> { done, fail -> sdk.getHVAC().getHvacTemperature(area, TemperatureUnit.CELSIUS, done, fail) } }
                Control.READING_LIGHT -> entry.native.readSeat?.let { area ->
                    when (read<LightState?> { done, fail -> sdk.getLight().getCurrentReadingLightState(area, done, fail) }) { LightState.ON -> 1.0; LightState.OFF -> 0.0; else -> null }
                }
                Control.AMBIENT_BRIGHTNESS -> entry.native.seat?.let { area -> percent(read<Int?> { done, fail -> sdk.getLight().getAmbientLightBrightness(area, done, fail) }, cap) }
                Control.SMART_GLASS -> entry.native.window?.let { area -> percent(read<Int?> { done, fail -> sdk.getWindow().getCurrentSmartGlassLevel(area, done, fail) }, cap) }
                else -> null
            }
            if (!active || closed || epoch != connectionEpoch) return@withLock unknown()
                .put("layoutSource", "unknown").put("controlAreas", JSONArray())
                .put("unknownFeatures", JSONArray((supportedControls + Control.CURRENT_TEMPERATURE).map(::featureName)))
            val numeric = (raw as? Number)?.toDouble()?.takeIf { it.isFinite() }
            observations[entry.feature to entry.area.id] = numeric
            groups.put(JSONObject().put("areaId", entry.area.id).put("kind", entry.area.kind)
                .put("members", JSONArray(entry.area.members)).put("feature", featureName(control))
                .put("readable", cap.readable).put("writable", cap.writable).put("observed", numeric ?: JSONObject.NULL))
        }
        // Legacy zone summaries are consensus only. Mixed/unknown area samples stay null.
        fun consensus(control: Control, zone: String): Double? {
            val entries = snapshot.targets.targets(control.name, zone)
            val values = entries.map { observations[it.feature to it.area.id] as? Double }
            return if (values.isNotEmpty() && values.all { it != null } && values.distinct().size == 1) values.first() else null
        }
        for (zone in listOf("front", "rear")) {
            result.getJSONObject(zone).put("readingLight", consensus(Control.READING_LIGHT, zone)?.let { it == 1.0 } ?: JSONObject.NULL)
                .put("ambientBrightness", consensus(Control.AMBIENT_BRIGHTNESS, zone) ?: JSONObject.NULL)
                .put("blindLevel", consensus(Control.SMART_GLASS, zone) ?: JSONObject.NULL)
        }
        result.put("targetTemperature", consensus(Control.TARGET_TEMPERATURE, "front") ?: JSONObject.NULL)
            .put("cabinTemperature", consensus(Control.CURRENT_TEMPERATURE, "front") ?: JSONObject.NULL)
            .put("controlAreas", groups).put("unknownFeatures", JSONArray((supportedControls + Control.CURRENT_TEMPERATURE).filter { it !in snapshot.queried }.map(::featureName)))
            .put("message", diagnostic)
    }
    private fun featureName(control: Control) = when (control) {
        Control.CURRENT_TEMPERATURE -> "cabinTemperature"; Control.TARGET_TEMPERATURE -> "targetTemperature"; Control.READING_LIGHT -> "readingLight"
        Control.AMBIENT_BRIGHTNESS -> "ambientBrightness"; Control.SMART_GLASS -> "blindLevel"
        else -> control.name.lowercase()
    }
    private fun percent(value: Int?, cap: Capability?): Any {
        val low = cap?.minimum; val high = cap?.maximum
        return if (value == null || low == null || high == null || high <= low) JSONObject.NULL else ((value - low) / (high - low) * 100).roundToInt().coerceIn(0, 100)
    }
    private fun target(percent: Double, cap: Capability?): Double {
        val low = cap?.minimum ?: return percent
        val high = cap.maximum ?: return percent
        return (low + percent.coerceIn(0.0, 100.0) / 100 * (high - low)).roundToInt().toDouble()
    }
    private suspend fun command(control: Control, zone: Zone, areaId: String, value: Double): JSONObject {
        val cap = targets.get(control.name, areaId)?.capability
        var latest: CommandUpdate? = null
        val update = withTimeoutOrNull(2400) { suspendCancellableCoroutine<CommandUpdate> { continuation ->
            adapter.apply(Command(control, zone, value, areaId)) { event ->
                latest = event
                if (event.confirmation != Confirmation.ACKNOWLEDGED || cap?.readable != true)
                    if (continuation.isActive) continuation.resume(event)
            }
        } } ?: latest
        val kind = update?.confirmation
        return JSONObject().put("control", "${zone.name.lowercase()}.${featureName(control)}").put("areaId", areaId)
            .put("target", value.takeIf { it.isFinite() } ?: JSONObject.NULL).put("observed", update?.observed ?: JSONObject.NULL)
            .put("status", when(kind) { Confirmation.OBSERVED -> "applied"; Confirmation.BLOCKED -> "blocked"; Confirmation.FAILED -> "failed"; else -> "partial" })
            .put("confirmation", when(kind) { Confirmation.OBSERVED -> "observed"; Confirmation.ACKNOWLEDGED -> "acknowledged"; else -> "unverified" })
            .put("message", CampText.get(when (kind) {
                Confirmation.OBSERVED -> R.string.cabin_layout_confirmed
                Confirmation.OBSERVED_MISMATCH -> R.string.cabin_layout_mismatch
                Confirmation.BLOCKED -> R.string.cabin_layout_blocked
                Confirmation.FAILED -> R.string.cabin_layout_failed
                Confirmation.ACKNOWLEDGED -> R.string.cabin_layout_acknowledged
                else -> R.string.cabin_layout_unverified
            }))
    }
    private fun blocked(control: String, areaId: String, reason: Int) = JSONObject().put("control", control)
        .put("areaId", areaId).put("status", "blocked").put("confirmation", "unverified").put("message", CampText.get(reason))
    private suspend fun controlResults(body: JSONObject): List<JSONObject> {
        try { ensure() } catch(cancelled: CancellationException) { throw cancelled } catch(error: Exception) { diagnostic = error.javaClass.simpleName }
        val zone = if (body.optString("zone") == "rear") Zone.REAR else Zone.FRONT
        val control = supportedControls.firstOrNull { featureName(it) == body.optString("control") }
            ?: return listOf(blocked(body.optString("control"), "", R.string.cabin_layout_unavailable))
        val requested = targets.targets(control.name, zone.name.lowercase()).filter { it.native.capability.writable }
        val ambiguous = targets.ambiguous(control.name).filter { it.native.capability.writable }
        val raw = if (body.opt("value") is Boolean) if(body.optBoolean("value")) 1.0 else 0.0 else body.optDouble("value", Double.NaN)
        if (!raw.isFinite()) return listOf(blocked("${zone.name.lowercase()}.${featureName(control)}", "", R.string.cabin_layout_invalid))
        val results = mutableListOf<JSONObject>()
        for (entry in requested) {
            val value = if (control in setOf(Control.AMBIENT_BRIGHTNESS, Control.SMART_GLASS)) target(raw, entry.native.capability) else raw
            results += command(control, zone, entry.area.id, value)
        }
        for (entry in ambiguous) results += blocked("${zone.name.lowercase()}.${featureName(control)}", entry.area.id, R.string.cabin_layout_ambiguous)
        if (results.isEmpty()) results += blocked("${zone.name.lowercase()}.${featureName(control)}", "", R.string.cabin_layout_unavailable)
        return results
    }
    suspend fun control(body: JSONObject): JSONObject {
        val results = controlResults(body)
        return JSONObject().put("vehicle",vehicle()).put("results",JSONArray(results)).put("status",if(results.all { it.optString("status")=="applied" }) "completed" else "partial")
    }
    suspend fun scene(sceneId: String): JSONObject {
        val results = mutableListOf<JSONObject>()
        val values = when(sceneId) { "load" -> listOf(22,1,1,75,75,0,0); "together" -> listOf(22,1,1,45,45,40,40); "sleep" -> listOf(20,0,0,5,5,95,95); "departure" -> listOf(22,0,0,0,0,0,0); else -> listOf(22,1,0,65,15,35,80) }
        for (item in listOf(Triple("targetTemperature",Zone.FRONT,values[0]),Triple("readingLight",Zone.FRONT,values[1]),Triple("readingLight",Zone.REAR,values[2]),Triple("ambientBrightness",Zone.FRONT,values[3]),Triple("ambientBrightness",Zone.REAR,values[4]),Triple("blindLevel",Zone.FRONT,values[5]),Triple("blindLevel",Zone.REAR,values[6]))) {
            val body=JSONObject().put("control",item.first).put("zone",item.second.name.lowercase()).put("value",if(item.first=="readingLight")item.third!=0 else item.third)
            results += controlResults(body)
        }
        return JSONObject().put("vehicle",vehicle()).put("results",JSONArray(results)).put("status",if(results.all { it.optString("status")=="applied" }) "completed" else "partial")
    }
    private suspend fun connectNavigation(): Boolean {
        if (navigation != null) return true
        val newHelper=navigationFactory.get()
        val newAdapter=PleosNavigationAdapter(newHelper)
        val connected=try { withTimeoutOrNull(4000) { suspendCancellableCoroutine<Boolean> { continuation ->
            newAdapter.connect({ if(continuation.isActive)continuation.resume(true) }, { if(continuation.isActive)continuation.resume(false) })
        } } == true } catch(cancelled: CancellationException) { newAdapter.close(); throw cancelled }
        if (!connected || closed) { newAdapter.close(); return false }
        val epoch = ++navigationGeneration
        val listener=object: NaviHelperEventListener {
            override fun onTripInfo(tripInfo:TripInfo) { if(!closed && navigationGeneration == epoch) tripResult?.invoke(tripInfo) }
            override fun onNavigationStatus(navigationStatus:NavigationStatus) { if(!closed && navigationGeneration == epoch && navigationStatus==NavigationStatus.ROUTE_DRIVE) routeResult?.invoke() }
            override fun onError(naviErrorCode:NaviErrorCode) { if(!closed && navigationGeneration == epoch) navigationError?.invoke() }
        }
        newHelper.addListener(listener)
        helper=newHelper;navigation=newAdapter;naviListener=listener
        return true
    }
    suspend fun navigationPreview(site:Campsite):JSONObject {
        val unavailable=JSONObject().put("mode","pleos").put("distanceKm",JSONObject.NULL).put("durationMinutes",JSONObject.NULL).put("consumptionKwh",JSONObject.NULL).put("arrivalSoc",JSONObject.NULL).put("chargingStop",false).put("message","Pleos 내비 연결 또는 이동 예상 확인이 필요합니다.")
        if(site.latitude==null||site.longitude==null||!connectNavigation())return unavailable
        val info=withTimeoutOrNull(5000) { suspendCancellableCoroutine<TripInfo?> { continuation ->
            tripResult={ if(continuation.isActive)continuation.resume(it) };navigationError={ if(continuation.isActive)continuation.resume(null) }
            navigation!!.preview(TripRequest(site.latitude,site.longitude,site.id,site.name,null))
        } }
        tripResult=null;navigationError=null
        if(info==null){disconnectNavigation();return unavailable}
        return unavailable.put("distanceKm",info.distance/1000.0).put("durationMinutes",JSONObject.NULL).put("etaSeconds",info.eta).put("etaBasis","unspecified_by_supplied_contract").put("consumptionKwh",info.energyConsumption/1000.0).put("arrivalSoc",info.estimatedSocAtDest).put("chargingStop",info.summary.routePoints.any { it.poiType == RoutePointPoiType.EV_CHARGING && it.pointType == RoutePointType.WAYPOINT }).put("message","NaviHelper onTripInfo 실제 응답")
    }
    suspend fun navigationStart(site:Campsite):JSONObject {
        val response=JSONObject().put("started",false).put("mode","pleos").put("message","실제 내비 경로 시작을 확인하지 못했습니다.")
        if(site.latitude==null||site.longitude==null||!connectNavigation())return response
        val started=withTimeoutOrNull(5000) { suspendCancellableCoroutine<Boolean> { continuation ->
            routeResult={ if(continuation.isActive)continuation.resume(true) };navigationError={if(continuation.isActive)continuation.resume(false)}
            navigation!!.start(RouteInfo(site.latitude,site.longitude,poiName=site.name,poiId=site.id,address=site.address,poiSubId=""))
        } } == true
        routeResult=null;navigationError=null
        if(!started)disconnectNavigation()
        return response.put("started",started).put("message",if(started)"NaviHelper ROUTE_DRIVE 실제 주행 안내 상태를 확인했습니다." else "실제 내비 경로 시작을 확인하지 못했습니다.")
    }
    private fun disconnectNavigation(){navigationGeneration++;tripResult=null;routeResult=null;navigationError=null;try{naviListener?.let{helper?.removeListener(it)}}finally{navigation?.close();navigation=null;helper=null;naviListener=null}}
    suspend fun location()=assistant.location()
    suspend fun arrivalWatch(site:Campsite)=assistant.arrivalWatch(site)
    suspend fun speak(text:String)=assistant.speak(text)
    suspend fun listen()=assistant.listen()
    fun setLanguage(tag:String)=assistant.setLanguage(tag)
    fun assistStatus()=assistant.assistStatus()
    fun start(){if(!closed){synchronized(topologyLock){connectionEpoch++;active=false;capabilitySnapshot=CapabilitySnapshot()};runCatching{assistant.start()}}}
    fun stop(){synchronized(topologyLock){connectionEpoch++;active=false;capabilitySnapshot=CapabilitySnapshot()};try{adapter.stop()}finally{soc=null;socObservedAt=null;disconnectNavigation();assistant.stop()}}
    fun close(){if(closed)return;closed=true;try{stop()}finally{try{adapter.close()}finally{assistant.close()}}}
}
