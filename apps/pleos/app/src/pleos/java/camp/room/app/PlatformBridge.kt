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
    private val caps = mutableMapOf<Pair<Control, Zone>, Capability>()
    private val seats = mutableMapOf<Pair<Control, Zone>, SeatArea>()
    private val windows = mutableMapOf<Zone, WindowArea>()
    @Volatile private var soc: Int? = null
    @Volatile private var socObservedAt: String? = null
    @Volatile private var diagnostic = ""
    private var active = false
    private var closed = false
    private var loaded = false
    private val adapter = PleosVehicleAdapter(sdk, { control, zone -> seats[control to zone] }, windows,
        TemperatureUnit.CELSIUS, { if (it) LightSwitch.ON else LightSwitch.OFF },
        { when(it) { LightState.ON -> true; LightState.OFF -> false; else -> null } },
        CapabilityRegistry { control, zone -> caps[control to zone] }, hostMotion,
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
    private fun zone(area: SeatArea): Zone? = when {
        area.values.isNotEmpty() && area.values.all { it.name.startsWith("ROW_1_") } -> Zone.FRONT
        area.values.isNotEmpty() && area.values.all { it.name.startsWith("ROW_2_") || it.name.startsWith("ROW_3_") } -> Zone.REAR
        else -> null
    }
    private fun zone(area: WindowArea): Zone? = when {
        area.values.isNotEmpty() && area.values.all { it.name.startsWith("ROW_1_") } -> Zone.FRONT
        area.values.isNotEmpty() && area.values.all { it.name.startsWith("ROW_2_") } -> Zone.REAR
        else -> null
    }
    private data class TemperatureCaps(val config: CapabilityConfig, val areas: Map<SeatArea, AreaBounds<Float>>, val steps: HvacTemperatureConfigArray?)
    private suspend fun ensure() {
        if (closed) return
        if (!active) { adapter.start(); active = true }
        if (loaded) return
        val temperature = read<TemperatureCaps> { done, fail ->
            sdk.getHVAC().checkHvacTemperatureCapabilities({ config, areas, steps -> done(TemperatureCaps(config, areas, steps)) }, fail)
        }
        temperature?.areas?.forEach { (area, bounds) -> zone(area)?.let { z ->
            seats[Control.TARGET_TEMPERATURE to z] = area
            caps[Control.TARGET_TEMPERATURE to z] = Capability(readable(temperature.config), writable(temperature.config),
                minimum = bounds.minValue.toDouble(), maximum = bounds.maxValue.toDouble(), increment = temperature.steps?.incrementCelsius?.toDouble())
        } }
        val state = read<Pair<CapabilityConfig, Map<SeatArea, List<LightState>>>> { done, fail ->
            sdk.getLight().checkReadingLightStateCapabilities({ config, areas -> done(config to areas) }, fail)
        }
        val switches = read<Pair<CapabilityConfig, Map<SeatArea, List<LightSwitch>>>> { done, fail ->
            sdk.getLight().checkReadingLightSwitchCapabilities({ config, areas -> done(config to areas) }, fail)
        }
        switches?.second?.forEach { (area, values) -> zone(area)?.let { z ->
            seats[Control.READING_LIGHT to z] = area
            val canRead = state?.let { readable(it.first) && it.second.containsKey(area) } ?: false
            caps[Control.READING_LIGHT to z] = Capability(canRead, writable(switches.first),
                allowedValues = buildSet { if (LightSwitch.OFF in values) add(0.0); if (LightSwitch.ON in values) add(1.0) })
        } }
        val ambient = read<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>> { done, fail ->
            sdk.getLight().checkAmbientLightBrightnessCapabilities({ config, areas -> done(config to areas) }, fail)
        }
        ambient?.second?.forEach { (area, bounds) -> zone(area)?.let { z ->
            seats[Control.AMBIENT_BRIGHTNESS to z] = area
            caps[Control.AMBIENT_BRIGHTNESS to z] = Capability(readable(ambient.first), writable(ambient.first), minimum = bounds.minValue.toDouble(), maximum = bounds.maxValue.toDouble(), increment = 1.0)
        } }
        val glass = read<Pair<CapabilityConfig, Map<WindowArea, AreaBounds<Int>>>> { done, fail ->
            sdk.getWindow().checkSmartGlassLevelCapabilities({ config, areas -> done(config to areas) }, fail)
        }
        glass?.second?.forEach { (area, bounds) -> zone(area)?.let { z ->
            windows[z] = area
            caps[Control.SMART_GLASS to z] = Capability(readable(glass.first), writable(glass.first), minimum = bounds.minValue.toDouble(), maximum = bounds.maxValue.toDouble(), increment = 1.0)
        } }
        loaded = caps.isNotEmpty()
    }
    private fun unknown() = JSONObject().put("mode", "pleos").put("motionState", hostMotion.current().name.lowercase())
        .put("soc", soc ?: JSONObject.NULL).put("socObservedAt", socObservedAt ?: JSONObject.NULL).put("cabinTemperature", JSONObject.NULL).put("targetTemperature", JSONObject.NULL)
        .put("front", JSONObject().put("readingLight", JSONObject.NULL).put("ambientBrightness", JSONObject.NULL).put("blindLevel", JSONObject.NULL))
        .put("rear", JSONObject().put("readingLight", JSONObject.NULL).put("ambientBrightness", JSONObject.NULL).put("blindLevel", JSONObject.NULL))
        .put("doorsClosed", JSONObject.NULL).put("windowsClosed", JSONObject.NULL).put("lastSceneId", JSONObject.NULL)
        .put("updatedAt", Instant.now().toString()).put("message", diagnostic)

    suspend fun vehicle(): JSONObject = guard.withLock {
        try { ensure() } catch(cancelled: CancellationException) { throw cancelled } catch(error: Exception) { soc=null; socObservedAt=null; diagnostic = "Pleos 차량 연결/권한 확인 필요: ${error.javaClass.simpleName}" }
        val result = unknown()
        if (!active) return@withLock result
        for (z in Zone.entries) {
            val targetArea = seats[Control.TARGET_TEMPERATURE to z]
            if (targetArea != null && caps[Control.TARGET_TEMPERATURE to z]?.readable == true) {
                val target = read<Float?> { done, fail -> sdk.getHVAC().getHvacTemperature(targetArea, TemperatureUnit.CELSIUS, done, fail) }
                val measured = read<Float?> { done, fail -> sdk.getHVAC().getHvacTemperatureCurrent(targetArea, TemperatureUnit.CELSIUS, done, fail) }
                if (z == Zone.FRONT) { result.put("targetTemperature", target ?: JSONObject.NULL); result.put("cabinTemperature", measured ?: JSONObject.NULL) }
            }
            val values = result.getJSONObject(z.name.lowercase())
            val lightArea = seats[Control.READING_LIGHT to z]
            if (lightArea != null && caps[Control.READING_LIGHT to z]?.readable == true) {
                val state = read<LightState?> { done, fail -> sdk.getLight().getCurrentReadingLightState(lightArea, done, fail) }
                values.put("readingLight", when(state) { LightState.ON -> true; LightState.OFF -> false; else -> JSONObject.NULL })
            }
            val ambientArea = seats[Control.AMBIENT_BRIGHTNESS to z]
            if (ambientArea != null && caps[Control.AMBIENT_BRIGHTNESS to z]?.readable == true) {
                val value = read<Int?> { done, fail -> sdk.getLight().getAmbientLightBrightness(ambientArea, done, fail) }
                values.put("ambientBrightness", percent(value, caps[Control.AMBIENT_BRIGHTNESS to z]))
            }
            val window = windows[z]
            if (window != null && caps[Control.SMART_GLASS to z]?.readable == true) {
                val value = read<Int?> { done, fail -> sdk.getWindow().getCurrentSmartGlassLevel(window, done, fail) }
                values.put("blindLevel", percent(value, caps[Control.SMART_GLASS to z]))
            }
        }
        result.put("message", diagnostic)
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
    private suspend fun command(control: Control, zone: Zone, value: Double): JSONObject {
        var latest: CommandUpdate? = null
        val update = withTimeoutOrNull(2400) { suspendCancellableCoroutine<CommandUpdate> { continuation ->
            adapter.apply(Command(control, zone, value)) { event ->
                latest = event
                if (event.confirmation != Confirmation.ACKNOWLEDGED || caps[control to zone]?.readable != true)
                    if (continuation.isActive) continuation.resume(event)
            }
        } } ?: latest
        val kind = update?.confirmation
        return JSONObject().put("control", "${zone.name.lowercase()}.${control.name.lowercase()}")
            .put("target", value).put("observed", update?.observed ?: JSONObject.NULL)
            .put("status", when(kind) { Confirmation.OBSERVED -> "applied"; Confirmation.BLOCKED -> "blocked"; Confirmation.FAILED -> "failed"; else -> "partial" })
            .put("confirmation", when(kind) { Confirmation.OBSERVED -> "observed"; Confirmation.ACKNOWLEDGED -> "acknowledged"; else -> "unverified" })
            .put("message", update?.message ?: "관측값을 확인하지 못했습니다.")
    }
    private suspend fun controlResult(body: JSONObject): JSONObject {
        try { ensure() } catch(cancelled: CancellationException) { throw cancelled } catch(error: Exception) { diagnostic = error.javaClass.simpleName }
        val zone = if (body.optString("zone") == "rear") Zone.REAR else Zone.FRONT
        val control = when(body.optString("control")) { "targetTemperature" -> Control.TARGET_TEMPERATURE; "readingLight" -> Control.READING_LIGHT; "ambientBrightness" -> Control.AMBIENT_BRIGHTNESS; "blindLevel" -> Control.SMART_GLASS; else -> null }
        val result = if (control == null) JSONObject().put("control",body.optString("control")).put("status","unsupported").put("confirmation","unverified").put("message","지원되지 않는 제어") else {
            val raw = if (body.opt("value") is Boolean) if(body.optBoolean("value")) 1.0 else 0.0 else body.optDouble("value", Double.NaN)
            command(control, zone, if (control in setOf(Control.AMBIENT_BRIGHTNESS,Control.SMART_GLASS)) target(raw,caps[control to zone]) else raw)
        }
        return result
    }
    suspend fun control(body: JSONObject): JSONObject {
        val result = controlResult(body)
        return JSONObject().put("vehicle",vehicle()).put("results",JSONArray().put(result)).put("status",if(result.optString("status")=="applied")"completed" else "partial")
    }
    suspend fun scene(sceneId: String): JSONObject {
        val results = JSONArray()
        val values = when(sceneId) { "load" -> listOf(22,1,1,75,75,0,0); "together" -> listOf(22,1,1,45,45,40,40); "sleep" -> listOf(20,0,0,5,5,95,95); "departure" -> listOf(22,0,0,0,0,0,0); else -> listOf(22,1,0,65,15,35,80) }
        for ((i, item) in listOf(Triple("targetTemperature",Zone.FRONT,values[0]),Triple("readingLight",Zone.FRONT,values[1]),Triple("readingLight",Zone.REAR,values[2]),Triple("ambientBrightness",Zone.FRONT,values[3]),Triple("ambientBrightness",Zone.REAR,values[4]),Triple("blindLevel",Zone.FRONT,values[5]),Triple("blindLevel",Zone.REAR,values[6])).withIndex()) {
            val body=JSONObject().put("control",item.first).put("zone",item.second.name.lowercase()).put("value",if(item.first=="readingLight")item.third!=0 else item.third)
            results.put(controlResult(body))
        }
        return JSONObject().put("vehicle",vehicle()).put("results",results).put("status",if((0 until results.length()).all {results.getJSONObject(it).optString("status")=="applied"})"completed" else "partial")
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
    fun start(){if(!closed){active=false;runCatching{assistant.start()}}}
    fun stop(){try{adapter.stop()}finally{active=false;soc=null;socObservedAt=null;disconnectNavigation();assistant.stop()}}
    fun close(){if(closed)return;closed=true;try{stop()}finally{try{adapter.close()}finally{assistant.close()}}}
}
