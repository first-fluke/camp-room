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
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RoutePointPoiType
import ai.pleos.playground.navi.constants.RoutePointType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
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
    private data class TemperatureCaps(val config: CapabilityConfig, val areas: Map<SeatArea, AreaBounds<Float>>, val steps: HvacTemperatureConfigArray?)
    private data class CapabilityCache(
        val temperature: TemperatureCaps? = null,
        val measured: Pair<CapabilityConfig, List<SeatArea>>? = null,
        val state: Pair<CapabilityConfig, Map<SeatArea, List<LightState>>>? = null,
        val switches: Pair<CapabilityConfig, Map<SeatArea, List<LightSwitch>>>? = null,
        val ambient: Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>? = null,
        val glass: Pair<CapabilityConfig, Map<WindowArea, AreaBounds<Int>>>? = null,
    )
    private data class CapabilitySnapshot(
        val targets: CabinTargetIndex<NativeTarget> = CabinTargetIndex(),
        val queried: Set<Control> = emptySet(),
        val cache: CapabilityCache = CapabilityCache(),
    )
    @Volatile private var capabilitySnapshot = CapabilitySnapshot()
    private val targets get() = capabilitySnapshot.targets
    private val supportedControls = listOf(Control.TARGET_TEMPERATURE, Control.READING_LIGHT, Control.AMBIENT_BRIGHTNESS, Control.SMART_GLASS)
    @Volatile private var soc: Int? = null
    @Volatile private var socObservedAt: String? = null
    @Volatile private var diagnostic = ""
    @Volatile private var readDiagnostic = ""
    @Volatile private var active = false
    @Volatile private var closed = false
    @Volatile private var connectionEpoch = 0
    private val readAvailability = SdkReadAvailability()
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
    private var routeResult: ((NavigationStatus) -> Unit)? = null
    private var destinationResult: ((DestinationInfo) -> Unit)? = null
    private var navigationError: (() -> Unit)? = null
    private var navigationInvalidated: (() -> Unit)? = null
    private val navigationObservations = NavigationRequestState()
    private var navigationGeneration = 0
    private val navigationLock = Any()
    private val navigationRequests = Mutex()
    private var navigationForeground = false
    @Volatile private var navigationConnectionStatus = "not_connected"

    // The SDK may do synchronous work before callbacks. Keep it off Main and retry
    // unavailable reads independently; absence/errors never become successful values.
    private suspend fun <T> read(
        operation: SdkReadOperation,
        areaKey: String = "capability",
        areaOrdinal: Int? = null,
        expectedEpoch: Int = connectionEpoch,
        isUsableValue: (T) -> Boolean = { true },
        call: ((T) -> Unit, (Exception) -> Unit) -> Unit,
    ): T? {
        val epoch = expectedEpoch
        return withContext(Dispatchers.IO) {
            if (closed || !active || epoch != connectionEpoch) {
                SdkRuntimeTrace.event("Vehicle", "read.skipped", "operation" to operation.name,
                    "areaOrdinal" to areaOrdinal, "epoch" to epoch, "reason" to "stale_epoch")
                return@withContext null
            }
            val key = SdkReadKey(operation, areaKey)
            val admission = readAvailability.begin(key, epoch)
            if (admission is SdkReadAdmission.Deferred) {
                SdkRuntimeTrace.event("Vehicle", "read.skipped", "operation" to operation.name,
                    "areaOrdinal" to areaOrdinal, "epoch" to epoch, "reason" to admission.reason,
                    "retryAfterMillis" to admission.retryAfterMillis)
                return@withContext null
            }
            val attempt = (admission as SdkReadAdmission.Allowed).attempt
            fun elapsed() = (System.nanoTime() / 1_000_000 - attempt.startedAtMillis).coerceAtLeast(0)
            fun trace(event: String, vararg fields: Pair<String, Any?>) {
                SdkRuntimeTrace.event("Vehicle", event, "operation" to operation.name,
                    "correlationId" to attempt.id, "areaOrdinal" to areaOrdinal, "epoch" to epoch,
                    "elapsedMs" to elapsed(), *fields)
            }
            fun finish(outcome: SdkReadOutcome, vararg fields: Pair<String, Any?>): Boolean {
                val current = !closed && active && epoch == connectionEpoch
                val accepted = readAvailability.finish(attempt, if (current) outcome else SdkReadOutcome.CANCELLED)
                if (accepted) {
                    trace("read.result", "outcome" to if (current) outcome.name.lowercase() else "stale",
                        "current" to current, "accepted" to true, *fields)
                    if (current && outcome !in setOf(SdkReadOutcome.SUCCESS, SdkReadOutcome.CANCELLED))
                        readDiagnostic = "일부 차량 상태를 확인하지 못했습니다. 잠시 후 다시 확인합니다."
                } else {
                    trace("read.ignored", "outcome" to outcome.name.lowercase(), "current" to current, "accepted" to false)
                }
                return accepted && current
            }
            try {
                val result = withTimeoutOrNull(2200) {
                    suspendCancellableCoroutine<T?> { continuation ->
                        continuation.invokeOnCancellation { cause ->
                            finish(if (cause is TimeoutCancellationException) SdkReadOutcome.TIMEOUT else SdkReadOutcome.CANCELLED,
                                "continuationActive" to false)
                        }
                        if (!continuation.isActive || closed || !active || epoch != connectionEpoch) {
                            finish(SdkReadOutcome.CANCELLED)
                            if (continuation.isActive) continuation.resume(null)
                            return@suspendCancellableCoroutine
                        }
                        trace("read.invoked", "onMainThread" to
                            (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()))
                        try {
                            call({ value ->
                                val finite = (value as? Number)?.toDouble()?.isFinite()
                                val outcome = when {
                                    elapsed() >= 2200 -> SdkReadOutcome.TIMEOUT
                                    value == null -> SdkReadOutcome.EMPTY
                                    finite == false || !isUsableValue(value) -> SdkReadOutcome.INVALID_VALUE
                                    else -> SdkReadOutcome.SUCCESS
                                }
                                val accepted = finish(outcome, "valuePresent" to (value != null),
                                    "finiteNumericResult" to finite, "continuationActive" to continuation.isActive)
                                if (continuation.isActive && (accepted || closed || epoch != connectionEpoch))
                                    continuation.resume(if (accepted && outcome == SdkReadOutcome.SUCCESS) value else null)
                            }, { error ->
                                val failureFields = SdkVehicleReadFailure.from(error).traceFields()
                                trace("read.onError", "errorClass" to error.javaClass.simpleName,
                                    "continuationActive" to continuation.isActive, *failureFields)
                                val accepted = finish(if (elapsed() >= 2200) SdkReadOutcome.TIMEOUT else SdkReadOutcome.CALLBACK_ERROR,
                                    *failureFields)
                                if (continuation.isActive && (accepted || closed || epoch != connectionEpoch)) continuation.resume(null)
                            })
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            val failureFields = SdkVehicleReadFailure.from(error).traceFields()
                            trace("read.error", "errorClass" to error.javaClass.simpleName,
                                "continuationActive" to continuation.isActive, *failureFields)
                            val accepted = finish(SdkReadOutcome.SYNCHRONOUS_ERROR, *failureFields)
                            if (continuation.isActive && (accepted || closed || epoch != connectionEpoch)) continuation.resume(null)
                        }
                    }
                }
                if (result == null && readAvailability.state(key, epoch)?.inFlight == true) finish(SdkReadOutcome.TIMEOUT)
                result
            } catch (cancelled: CancellationException) {
                finish(SdkReadOutcome.CANCELLED)
                throw cancelled
            }
        }
    }
    private fun readable(config: CapabilityConfig) = config.accessType.name in setOf("READ", "READ_WRITE")
    private fun writable(config: CapabilityConfig) = config.accessType.name in setOf("WRITE", "READ_WRITE")
    private fun descriptor(area: SeatArea) = CabinArea("seat", area.values.map { it.name })
    private fun descriptor(area: WindowArea) = CabinArea("window", area.values.map { it.name })
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
            val cached = capabilitySnapshot.cache
            if (cached.temperature != null && cached.measured != null && cached.state != null &&
                cached.switches != null && cached.ambient != null && cached.glass != null) return
            fun current() = active && !closed && epoch == connectionEpoch
            val discovered = CabinTargetIndex<NativeTarget>()
            val discoveredQueries = mutableSetOf<Control>()
            fun add(control: Control, area: SeatArea, capability: Capability, readArea: SeatArea? = area) {
                discovered.put(control.name, descriptor(area), NativeTarget(capability, seat = area, readSeat = readArea))
            }
            val temperature = cached.temperature ?: read<TemperatureCaps>(SdkReadOperation.CAP_HVAC_TARGET, expectedEpoch = epoch) { done, fail ->
                sdk.getHVAC().checkHvacTemperatureCapabilities({ config, areas, steps ->
                    SdkRuntimeTrace.event("Vehicle", "capability", "feature" to "hvacTemperature", "accessType" to config.accessType.name, "areaCount" to areas.size, "current" to current())
                    done(TemperatureCaps(config, areas, steps))
                }, fail)
            }
            if (!current()) return
            if (temperature != null) discoveredQueries += Control.TARGET_TEMPERATURE
            temperature?.areas?.forEach { (area, bounds) ->
                add(Control.TARGET_TEMPERATURE, area, Capability(readable(temperature.config), writable(temperature.config),
                    minimum = bounds.minValue.toDouble(), maximum = bounds.maxValue.toDouble(), increment = temperature.steps?.incrementCelsius?.toDouble()))
            }
            val measured = cached.measured ?: read<Pair<CapabilityConfig, List<SeatArea>>>(SdkReadOperation.CAP_HVAC_CURRENT, expectedEpoch = epoch) { done, fail ->
                sdk.getHVAC().checkHvacTemperatureCurrentCapabilities({ config, areas ->
                    SdkRuntimeTrace.event("Vehicle", "capability", "feature" to "hvacTemperatureCurrent", "accessType" to config.accessType.name, "areaCount" to areas.size, "current" to current())
                    done(config to areas)
                }, fail)
            }
            if (!current()) return
            if (measured != null) discoveredQueries += Control.CURRENT_TEMPERATURE
            measured?.second?.forEach { area -> add(Control.CURRENT_TEMPERATURE, area, Capability(readable(measured.first), false)) }
            val state = cached.state ?: read<Pair<CapabilityConfig, Map<SeatArea, List<LightState>>>>(SdkReadOperation.CAP_LIGHT_STATE, expectedEpoch = epoch) { done, fail ->
                sdk.getLight().checkReadingLightStateCapabilities({ config, areas ->
                    SdkRuntimeTrace.event("Vehicle", "capability", "feature" to "readingLightState", "accessType" to config.accessType.name, "areaCount" to areas.size, "current" to current())
                    done(config to areas)
                }, fail)
            }
            if (!current()) return
            val switches = cached.switches ?: read<Pair<CapabilityConfig, Map<SeatArea, List<LightSwitch>>>>(SdkReadOperation.CAP_LIGHT_SWITCH, expectedEpoch = epoch) { done, fail ->
                sdk.getLight().checkReadingLightSwitchCapabilities({ config, areas ->
                    SdkRuntimeTrace.event("Vehicle", "capability", "feature" to "readingLightSwitch", "accessType" to config.accessType.name, "areaCount" to areas.size, "current" to current())
                    done(config to areas)
                }, fail)
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
            val ambient = cached.ambient ?: read<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>>(SdkReadOperation.CAP_AMBIENT, expectedEpoch = epoch) { done, fail ->
                sdk.getLight().checkAmbientLightBrightnessCapabilities({ config, areas ->
                    SdkRuntimeTrace.event("Vehicle", "capability", "feature" to "ambientLightBrightness", "accessType" to config.accessType.name, "areaCount" to areas.size, "current" to current())
                    done(config to areas)
                }, fail)
            }
            if (!current()) return
            if (ambient != null) discoveredQueries += Control.AMBIENT_BRIGHTNESS
            ambient?.second?.forEach { (area, bounds) ->
                add(Control.AMBIENT_BRIGHTNESS, area, Capability(readable(ambient.first), writable(ambient.first), minimum = bounds.minValue.toDouble(), maximum = bounds.maxValue.toDouble(), increment = 1.0))
            }
            val glass = cached.glass ?: read<Pair<CapabilityConfig, Map<WindowArea, AreaBounds<Int>>>>(SdkReadOperation.CAP_SMART_GLASS, expectedEpoch = epoch) { done, fail ->
                sdk.getWindow().checkSmartGlassLevelCapabilities({ config, areas ->
                    SdkRuntimeTrace.event("Vehicle", "capability", "feature" to "smartGlassLevel", "accessType" to config.accessType.name, "areaCount" to areas.size, "current" to current())
                    done(config to areas)
                }, fail)
            }
            if (!current()) return
            if (glass != null) discoveredQueries += Control.SMART_GLASS
            glass?.second?.forEach { (area, bounds) ->
                val descriptor = descriptor(area)
                discovered.put(Control.SMART_GLASS.name, descriptor,
                    NativeTarget(Capability(readable(glass.first), writable(glass.first), minimum = bounds.minValue.toDouble(), maximum = bounds.maxValue.toDouble(), increment = 1.0), window = area))
            }
            synchronized(topologyLock) {
                if (current()) capabilitySnapshot = CapabilitySnapshot(discovered, discoveredQueries.toSet(),
                    CapabilityCache(temperature, measured, state, switches, ambient, glass))
            }
        }
    }

    private fun unknown() = JSONObject().put("mode", "pleos").put("connected", false).put("motionState", hostMotion.current().name.lowercase())
        .put("soc", soc ?: JSONObject.NULL).put("socObservedAt", socObservedAt ?: JSONObject.NULL).put("cabinTemperature", JSONObject.NULL).put("targetTemperature", JSONObject.NULL)
        .put("front", JSONObject().put("readingLight", JSONObject.NULL).put("ambientBrightness", JSONObject.NULL).put("blindLevel", JSONObject.NULL))
        .put("rear", JSONObject().put("readingLight", JSONObject.NULL).put("ambientBrightness", JSONObject.NULL).put("blindLevel", JSONObject.NULL))
        .put("doorsClosed", JSONObject.NULL).put("windowsClosed", JSONObject.NULL).put("lastSceneId", JSONObject.NULL)
        .put("updatedAt", Instant.now().toString()).put("message", listOf(diagnostic, readDiagnostic).filter { it.isNotBlank() }.joinToString(" "))

    suspend fun vehicle(): JSONObject = guard.withLock {
        try { ensure() } catch(cancelled: CancellationException) { throw cancelled } catch(error: Exception) { soc=null; socObservedAt=null; diagnostic = "Pleos vehicle connection/permission: ${error.javaClass.simpleName}" }
        val epoch = connectionEpoch
        val result = unknown().put("layoutSource", "sdk_capabilities")
        val snapshot = capabilitySnapshot
        val groups = JSONArray()
        val unavailableReads = JSONArray()
        val unavailableFeatures = mutableSetOf<Control>()
        val observations = mutableMapOf<Pair<String, String>, Any?>()
        for ((ordinal, entry) in snapshot.targets.all().withIndex()) {
            val cap = entry.native.capability
            val control = Control.valueOf(entry.feature)
            val operation = when (control) {
                Control.CURRENT_TEMPERATURE -> SdkReadOperation.GET_HVAC_CURRENT
                Control.TARGET_TEMPERATURE -> SdkReadOperation.GET_HVAC_TARGET
                Control.READING_LIGHT -> SdkReadOperation.GET_LIGHT_STATE
                Control.AMBIENT_BRIGHTNESS -> SdkReadOperation.GET_AMBIENT
                Control.SMART_GLASS -> SdkReadOperation.GET_SMART_GLASS
                else -> null
            }
            val raw: Any? = if (!active || !cap.readable) null else when (control) {
                Control.CURRENT_TEMPERATURE -> entry.native.readSeat?.let { area -> read<Float?>(SdkReadOperation.GET_HVAC_CURRENT, entry.area.id, ordinal, expectedEpoch = epoch) { done, fail -> sdk.getHVAC().getHvacTemperatureCurrent(area, TemperatureUnit.CELSIUS, done, fail) } }
                Control.TARGET_TEMPERATURE -> entry.native.seat?.let { area -> read<Float?>(SdkReadOperation.GET_HVAC_TARGET, entry.area.id, ordinal, expectedEpoch = epoch) { done, fail -> sdk.getHVAC().getHvacTemperature(area, TemperatureUnit.CELSIUS, done, fail) } }
                Control.READING_LIGHT -> entry.native.readSeat?.let { area ->
                    when (read<LightState?>(SdkReadOperation.GET_LIGHT_STATE, entry.area.id, ordinal, expectedEpoch = epoch,
                        isUsableValue = { it == LightState.ON || it == LightState.OFF }) { done, fail -> sdk.getLight().getCurrentReadingLightState(area, done, fail) }) { LightState.ON -> 1.0; LightState.OFF -> 0.0; else -> null }
                }
                Control.AMBIENT_BRIGHTNESS -> entry.native.seat?.let { area -> percent(read<Int?>(SdkReadOperation.GET_AMBIENT, entry.area.id, ordinal, expectedEpoch = epoch) { done, fail -> sdk.getLight().getAmbientLightBrightness(area, done, fail) }, cap) }
                Control.SMART_GLASS -> entry.native.window?.let { area -> percent(read<Int?>(SdkReadOperation.GET_SMART_GLASS, entry.area.id, ordinal, expectedEpoch = epoch) { done, fail -> sdk.getWindow().getCurrentSmartGlassLevel(area, done, fail) }, cap) }
                else -> null
            }
            if (!active || closed || epoch != connectionEpoch) return@withLock unknown()
                .put("layoutSource", "unknown").put("controlAreas", JSONArray())
                .put("unknownFeatures", JSONArray((supportedControls + Control.CURRENT_TEMPERATURE).map(::featureName)))
            val numeric = (raw as? Number)?.toDouble()?.takeIf { it.isFinite() }
            if (cap.readable && numeric == null) {
                unavailableFeatures += control
                val availability = operation?.let { readAvailability.state(SdkReadKey(it, entry.area.id), epoch) }
                unavailableReads.put(JSONObject().put("feature", featureName(control)).put("areaOrdinal", ordinal)
                    .put("status", when {
                        availability?.inFlight == true -> "pending"
                        (availability?.retryAfterMillis ?: 0) > 0 -> "backoff"
                        availability?.lastOutcome == SdkReadOutcome.SUCCESS -> "invalid_value"
                        else -> availability?.lastOutcome?.name?.lowercase() ?: "unknown"
                    }).put("retryAfterMillis", availability?.retryAfterMillis ?: 0))
            }
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
        val unknownControls = ((supportedControls + Control.CURRENT_TEMPERATURE).filter { control ->
            control !in snapshot.queried || snapshot.targets.all().none { it.feature == control.name && it.native.capability.readable }
        } + unavailableFeatures).distinct()
        if (unknownControls.isEmpty()) readDiagnostic = ""
        result.put("targetTemperature", consensus(Control.TARGET_TEMPERATURE, "front") ?: JSONObject.NULL)
            .put("cabinTemperature", consensus(Control.CURRENT_TEMPERATURE, "front") ?: JSONObject.NULL)
            .put("controlAreas", groups).put("unknownFeatures", JSONArray(unknownControls.map(::featureName)))
            .put("readStatus", if (!active || closed) "unknown" else if (unknownControls.isNotEmpty()) "partial" else "available")
            .put("unavailableReads", unavailableReads)
            .put("connected", active && !closed && epoch == connectionEpoch &&
                (soc != null || observations.values.any { it != null } ||
                    (snapshot.targets.all().isEmpty() && snapshot.queried.isNotEmpty())))
            .put("message", listOf(diagnostic, readDiagnostic).filter { it.isNotBlank() }.joinToString(" "))
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
        val epoch = synchronized(navigationLock) {
            if (closed || !navigationForeground) { navigationConnectionStatus = "stale"; return false }
            if (navigation != null) { navigationConnectionStatus = "connected"; return true }
            navigationConnectionStatus = "connecting"
            navigationGeneration
        }
        val newHelper=navigationFactory.get()
        val newAdapter=PleosNavigationAdapter(newHelper)
        var published = false
        var listenerAdded = false
        val listener=object: NaviHelperEventListener {
            override fun onTripInfo(tripInfo:TripInfo) { synchronized(navigationLock) {
                SdkRuntimeTrace.event("NaviHelper", "onTripInfo", "status" to "received", "distanceMetres" to tripInfo.distance,
                    "eta" to tripInfo.eta, "energyConsumption" to tripInfo.energyConsumption, "estimatedSocAtDest" to tripInfo.estimatedSocAtDest,
                    "current" to (!closed && navigationForeground && navigationGeneration == epoch))
                if(!closed && navigationForeground && navigationGeneration == epoch) tripResult?.invoke(tripInfo)
            } }
            override fun onNavigationStatus(navigationStatus:NavigationStatus) { synchronized(navigationLock) {
                SdkRuntimeTrace.event("NaviHelper", "onNavigationStatus", "status" to navigationStatus.name, "current" to (!closed && navigationForeground && navigationGeneration == epoch))
                if(!closed && navigationForeground && navigationGeneration == epoch) routeResult?.invoke(navigationStatus)
            } }
            override fun onDestinationInfo(destinationInfo:DestinationInfo) { synchronized(navigationLock) {
                val coordinatesValid = destinationInfo.latitude.isFinite() && destinationInfo.longitude.isFinite() && destinationInfo.latitude in -90.0..90.0 && destinationInfo.longitude in -180.0..180.0
                SdkRuntimeTrace.event("NaviHelper", "onDestinationInfo", "status" to "received", "coordinatesValid" to coordinatesValid, "current" to (!closed && navigationForeground && navigationGeneration == epoch))
                if(!closed && navigationForeground && navigationGeneration == epoch) destinationResult?.invoke(destinationInfo)
            } }
            override fun onError(naviErrorCode:NaviErrorCode) { synchronized(navigationLock) {
                SdkRuntimeTrace.event("NaviHelper", "onError", "errorCode" to naviErrorCode.name, "current" to (!closed && navigationForeground && navigationGeneration == epoch))
                if(!closed && navigationForeground && navigationGeneration == epoch) navigationError?.invoke()
            } }
        }
        try {
            val connected=withTimeoutOrNull(4000) { suspendCancellableCoroutine<Boolean> { continuation ->
                newAdapter.connect({
                    SdkRuntimeTrace.event("NaviHelper", "onConnected", "continuationActive" to continuation.isActive)
                    if(continuation.isActive)continuation.resume(true)
                }, { error ->
                    SdkRuntimeTrace.event("NaviHelper", "onFailed", "errorClass" to error.javaClass.simpleName, "continuationActive" to continuation.isActive)
                    synchronized(navigationLock) {
                        if (continuation.isActive && !closed && navigationForeground && navigationGeneration == epoch) navigationConnectionStatus = "service_failure"
                    }
                    if(continuation.isActive)continuation.resume(false)
                })
            } }
            if (connected != true) {
                synchronized(navigationLock) {
                    navigationConnectionStatus = if (closed || !navigationForeground || navigationGeneration != epoch) "stale"
                        else if (connected == null) "timeout" else "service_failure"
                }
                return false
            }
            return synchronized(navigationLock) {
                if (closed || !navigationForeground || navigationGeneration != epoch) { navigationConnectionStatus = "stale"; false }
                else {
                    listenerAdded = true
                    newHelper.addListener(listener)
                    helper=newHelper;navigation=newAdapter;naviListener=listener
                    published = true
                    navigationConnectionStatus = "connected"
                    true
                }
            }
        } catch (cancelled: CancellationException) {
            navigationConnectionStatus = "cancelled"
            throw cancelled
        } catch (error: Exception) {
            navigationConnectionStatus = "service_failure"
            SdkRuntimeTrace.event("NaviHelper", "connect.error", "errorClass" to error.javaClass.simpleName)
            return false
        } finally {
            if (!published) {
                if (listenerAdded) runCatching { newHelper.removeListener(listener) }.onFailure { diagnostic="내비 리스너 해제 확인 필요: ${it.javaClass.simpleName}" }
                runCatching { newAdapter.close() }.onFailure { diagnostic="내비 연결 종료 확인 필요: ${it.javaClass.simpleName}" }
            }
        }
    }
    private fun validDestination(site:Campsite):Boolean {
        val latitude=site.latitude;val longitude=site.longitude
        return site.id.isNotBlank() && latitude!=null && longitude!=null && latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
    }

    // Navi callbacks carry no app request ID. A new helper/epoch for every operation
    // prevents callbacks queued on an earlier listener from satisfying a later request.
    private fun prepareFreshNavigationRequest(): Boolean = try {
        disconnectNavigation()
        true
    } catch (error: Exception) {
        SdkRuntimeTrace.event("NaviHelper", "request.cleanup_error", "errorClass" to error.javaClass.simpleName)
        false
    }

    private fun traceNavigationAdmission(event: String, token: NavigationRequestToken, admission: NavigationCallbackAdmission) {
        val elapsed = navigationObservations.elapsedMillis(token)
        SdkRuntimeTrace.event("NaviHelper", event, "appRequestId" to token.id,
            "requestEpoch" to token.connectionEpoch, "kind" to token.kind.name,
            "admission" to admission.name.lowercase(), "elapsedMs" to elapsed,
            "afterInitialWindow" to elapsed?.let { it >= NavigationRequestState.INITIAL_OBSERVATION_MS })
    }

    // Observe the SAME read-planning request: 5s initial window, then at most the
    // remaining 10s. Claim/deadline/epoch checks in callbacks remain authoritative.
    // This is app observation policy, not a claim that the SDK finishes in 15s.
    private suspend fun <T> observeNavigationResult(result: CompletableDeferred<T?>, token: NavigationRequestToken): T? =
        NavigationResultObserver(result,token,navigationObservations,navigationLock,{ navigationGeneration },
            onExtended={ elapsed,remaining ->
                SdkRuntimeTrace.event("NaviHelper", "trip.observation_extended", "appRequestId" to token.id,
                    "elapsedMs" to elapsed,"remainingMs" to remaining,
                    "maximumObservationMs" to NavigationRequestState.PREVIEW_MAX_OBSERVATION_MS,
                    "additionalSdkRequests" to 0)
            }).await()

    suspend fun navigationPreview(site:Campsite):JSONObject = withContext(Dispatchers.IO) {
      navigationRequests.withLock {
        val unavailable=JSONObject().put("mode","pleos").put("status","unavailable")
            .put("destinationConfirmed",false).put("distanceKm",JSONObject.NULL).put("durationMinutes",JSONObject.NULL)
            .put("consumptionKwh",JSONObject.NULL).put("arrivalSoc",JSONObject.NULL).put("chargingStop",false)
            .put("message","Pleos 내비 연결 또는 이동 예상 확인이 필요합니다.")
        if(!validDestination(site))return@withLock unavailable.put("status","failed").put("reason","invalid_destination")
        if(!prepareFreshNavigationRequest())return@withLock unavailable.put("reason","cleanup_failure")
        val connected = try { connectNavigation() } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                SdkRuntimeTrace.event("NaviHelper", "preview.connectError", "errorClass" to error.javaClass.simpleName)
                return@withLock unavailable.put("status","failed").put("reason","service_failure")
            }
        if(!connected)return@withLock unavailable.put("status",if(navigationConnectionStatus=="service_failure") "failed" else "unavailable")
            .put("reason",navigationConnectionStatus)
        val token = synchronized(navigationLock) {
            navigationObservations.begin(NavigationRequestKind.PREVIEW,
                SdkDestinationRequest(site.id,site.latitude!!,site.longitude!!),navigationGeneration)
        }
        val requestEpoch=token.connectionEpoch
        val result=CompletableDeferred<TripInfo?>()
        var completed=false
        var requestOutcome="timeout"
        try {
            synchronized(navigationLock) {
                val current=navigation
                if(closed || !navigationForeground || current==null) {
                    requestOutcome="stale";navigationObservations.finish(token);result.complete(null)
                } else {
                    tripResult={ received ->
                        val admission=navigationObservations.claim(token,navigationGeneration)
                        traceNavigationAdmission("trip.callback",token,admission)
                        if(admission==NavigationCallbackAdmission.CURRENT) { requestOutcome="received";result.complete(received) }
                    }
                    navigationError={
                        val admission=navigationObservations.claim(token,navigationGeneration)
                        traceNavigationAdmission("trip.error_callback",token,admission)
                        if(admission==NavigationCallbackAdmission.CURRENT) { requestOutcome="service_failure";result.complete(null) }
                    }
                    navigationInvalidated={ requestOutcome="stale";navigationObservations.finish(token);result.complete(null) }
                    try {
                        current.preview(TripRequest(latitude=site.latitude!!,longitude=site.longitude!!,poiId=site.id,poiName=site.name,poiSubId=null))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        requestOutcome="service_failure";navigationObservations.finish(token)
                        SdkRuntimeTrace.event("NaviHelper", "preview.error", "errorClass" to error.javaClass.simpleName)
                        result.complete(null)
                    }
                }
            }
            val info=observeNavigationResult(result,token)
            currentCoroutineContext().ensureActive()
            if(info==null) {
                if(requestOutcome=="received")requestOutcome="timeout"
                return@withLock unavailable.put("status",if(requestOutcome=="service_failure") "failed" else "unavailable")
                    .put("reason",requestOutcome)
            }
            val points=info.summary.routePoints.map { point ->
                SdkRouteDestinationPoint(point.poiId.orEmpty(),point.latitude,point.longitude,point.pointType==RoutePointType.DESTINATION)
            }
            val match=synchronized(navigationLock) {
                if(closed || !navigationForeground || navigation==null) SdkDestinationMatch.STALE
                else SdkDestinationMatcher.match(SdkDestinationRequest(site.id,site.latitude!!,site.longitude!!),points,requestEpoch,navigationGeneration)
            }
            val destination = points.singleOrNull { it.isDestination }
            val requestedLatitude = site.latitude
            val requestedLongitude = site.longitude
            val requestLatitudeValid = requestedLatitude != null && requestedLatitude.isFinite() && requestedLatitude in -90.0..90.0
            val requestLongitudeValid = requestedLongitude != null && requestedLongitude.isFinite() && requestedLongitude in -180.0..180.0
            val returnedLatitudeValid = destination?.let { it.latitude.isFinite() && it.latitude in -90.0..90.0 }
            val returnedLongitudeValid = destination?.let { it.longitude.isFinite() && it.longitude in -180.0..180.0 }
            val destinationDistanceMeters = if (destination != null && requestedLatitude != null && requestedLongitude != null)
                SdkDestinationMatcher.distanceMeters(SdkDestinationRequest(site.id,requestedLatitude,requestedLongitude),destination) else null
            val within20Meters = destinationDistanceMeters?.let { SdkDestinationMatcher.isWithinDestinationDistance(it) }
            val destinationDistanceBucket = when {
                destinationDistanceMeters == null -> "not_comparable"
                destinationDistanceMeters <= 1.0 -> "le_1m"
                destinationDistanceMeters <= 5.0 -> "gt_1_le_5m"
                destinationDistanceMeters <= 10.0 -> "gt_5_le_10m"
                within20Meters == true -> "gt_10_le_20m"
                destinationDistanceMeters <= 50.0 -> "gt_20_le_50m"
                destinationDistanceMeters <= 100.0 -> "gt_50_le_100m"
                destinationDistanceMeters <= 1000.0 -> "gt_100_le_1000m"
                else -> "gt_1000m"
            }
            SdkRuntimeTrace.event("NaviHelper", "trip.destination", "matchesRequestedDestination" to (match==SdkDestinationMatch.MATCH),
                "matchStatus" to match.name, "routePointCount" to points.size,
                "destinationPointCount" to points.count { it.isDestination },
                "destinationOrdinal" to points.indexOfFirst { it.isDestination },
                "requestEpoch" to requestEpoch,
                "requestPoiIdPresent" to site.id.isNotBlank(),
                "returnedPoiIdPresent" to destination?.poiId?.isNotBlank(),
                "poiIdMatches" to destination?.let { it.poiId == site.id },
                "requestLatitudeValid" to requestLatitudeValid, "requestLongitudeValid" to requestLongitudeValid,
                "returnedLatitudeValid" to returnedLatitudeValid, "returnedLongitudeValid" to returnedLongitudeValid,
                "coordinatePolicy" to "same_poi_geodesic_20m",
                "destinationDistanceLimitMeters" to SdkDestinationMatcher.MAX_DESTINATION_DISTANCE_METERS,
                "within20Meters" to within20Meters,
                "destinationDistanceBucket" to destinationDistanceBucket)
            if(match!=SdkDestinationMatch.MATCH) {
                requestOutcome="destination_${match.name.lowercase()}"
                return@withLock unavailable.put("status",if(match==SdkDestinationMatch.STALE) "unavailable" else "failed")
                    .put("reason",requestOutcome).put("message","요청한 목적지가 확인되지 않아 경로 정보를 표시하지 않았습니다.")
            }
            if(info.distance<0 || !info.energyConsumption.isFinite()) {
                requestOutcome="invalid_result"
                return@withLock unavailable.put("status","failed").put("reason",requestOutcome)
            }
            synchronized(navigationLock) {
                if(closed || !navigationForeground || navigation==null ||
                    !navigationObservations.canPublishClaimedResult(token,navigationGeneration)) {
                    requestOutcome="stale"
                    unavailable.put("status","unavailable").put("reason","stale")
                } else {
                    completed=true
                    requestOutcome="available"
                    unavailable.put("status","available").put("reason","destination_matched").put("destinationConfirmed",true)
                        .put("distanceKm",info.distance/1000.0).put("durationMinutes",JSONObject.NULL).put("etaSeconds",info.eta)
                        .put("etaBasis","unspecified_by_supplied_contract").put("consumptionKwh",info.energyConsumption/1000.0)
                        .put("arrivalSoc",info.estimatedSocAtDest).put("chargingStop",info.summary.routePoints.any { it.poiType == RoutePointPoiType.EV_CHARGING && it.pointType == RoutePointType.WAYPOINT })
                        .put("message","요청한 목적지와 일치하는 Pleos 경로를 확인했습니다.")
                }
            }
        } catch (cancelled: CancellationException) {
            requestOutcome="cancelled"
            throw cancelled
        } finally {
            SdkRuntimeTrace.event("NaviHelper", "trip.result", "status" to requestOutcome, "destinationConfirmed" to completed,
                "appRequestId" to token.id,"elapsedMs" to navigationObservations.elapsedMillis(token))
            synchronized(navigationLock) {
                navigationObservations.finish(token);tripResult=null;navigationError=null;navigationInvalidated=null
            }
            result.cancel()
            if(!completed)runCatching { disconnectNavigation() }.onFailure { diagnostic="내비 연결 종료 확인 필요: ${it.javaClass.simpleName}" }
        }
      }
    }

    suspend fun navigationStart(site:Campsite):JSONObject = withContext(Dispatchers.IO) {
      navigationRequests.withLock {
        val response=JSONObject().put("started",false).put("mode","pleos").put("message","실제 내비 경로 시작을 확인하지 못했습니다.")
        if(!validDestination(site))return@withLock response
        if(!prepareFreshNavigationRequest())return@withLock response.put("reason","cleanup_failure")
        if(!connectNavigation())return@withLock response
        val token=synchronized(navigationLock) {
            navigationObservations.begin(NavigationRequestKind.START,
                SdkDestinationRequest(site.id,site.latitude!!,site.longitude!!),navigationGeneration)
        }
        val result=CompletableDeferred<Boolean?>()
        var started=false
        var drivingObserved=false
        var requestOutcome="timeout"
        try {
            synchronized(navigationLock) {
                val current=navigation
                if(closed || !navigationForeground || current==null) {
                    requestOutcome="stale";navigationObservations.finish(token);result.complete(false)
                } else {
                    routeResult={ status ->
                        val admission=navigationObservations.admission(token,navigationGeneration)
                        traceNavigationAdmission("route.status_callback",token,admission)
                        if(admission==NavigationCallbackAdmission.CURRENT) {
                            drivingObserved=status==NavigationStatus.ROUTE_DRIVE
                            try { if(drivingObserved)helper?.getDestinationInfo() }
                            catch(error:Exception) {
                                diagnostic="내비 목적지 확인 필요: ${error.javaClass.simpleName}"
                                if(navigationObservations.claim(token,navigationGeneration)==NavigationCallbackAdmission.CURRENT) {
                                    requestOutcome="service_failure";result.complete(false)
                                }
                            }
                        }
                    }
                    destinationResult={ destination ->
                        val admission=navigationObservations.admission(token,navigationGeneration)
                        val match=if(admission==NavigationCallbackAdmission.CURRENT)
                            SdkDestinationMatcher.match(token.request,listOf(SdkRouteDestinationPoint(destination.poiId.orEmpty(),
                                destination.latitude,destination.longitude,true)),token.connectionEpoch,navigationGeneration)
                            else SdkDestinationMatch.STALE
                        SdkRuntimeTrace.event("NaviHelper", "destination.result", "matchesRequestedDestination" to (match==SdkDestinationMatch.MATCH),
                            "matchStatus" to match.name,"drivingObserved" to drivingObserved,"appRequestId" to token.id,
                            "admission" to admission.name.lowercase(),"coordinatePolicy" to "same_poi_geodesic_20m",
                            "elapsedMs" to navigationObservations.elapsedMillis(token))
                        if(drivingObserved && match==SdkDestinationMatch.MATCH &&
                            navigationObservations.claim(token,navigationGeneration)==NavigationCallbackAdmission.CURRENT) {
                            requestOutcome="started";result.complete(true)
                        }
                    }
                    navigationError={
                        val admission=navigationObservations.claim(token,navigationGeneration)
                        traceNavigationAdmission("route.error_callback",token,admission)
                        if(admission==NavigationCallbackAdmission.CURRENT) { requestOutcome="service_failure";result.complete(false) }
                    }
                    navigationInvalidated={ requestOutcome="stale";navigationObservations.finish(token);result.complete(false) }
                    current.start(RouteInfo(latitude=site.latitude!!,longitude=site.longitude!!,poiName=site.name,poiId=site.id,address=site.address,poiSubId=""))
                }
            }
            val observed=observeNavigationResult(result,token)==true
            currentCoroutineContext().ensureActive()
            synchronized(navigationLock) {
                started=observed && !closed && navigationForeground && navigation!=null &&
                    navigationObservations.canPublishClaimedResult(token,navigationGeneration)
                if(observed && !started)requestOutcome="stale"
                response.put("started",started).put("destinationConfirmed",started).put("message",if(started)"NaviHelper 주행 안내 상태와 요청한 목적지 정보를 확인했습니다." else "실제 내비 경로 시작을 확인하지 못했습니다.")
            }
        } catch(cancelled:CancellationException) {
            requestOutcome="cancelled"
            throw cancelled
        } catch(error:Exception) {
            requestOutcome="service_failure"
            SdkRuntimeTrace.event("NaviHelper", "route.error", "errorClass" to error.javaClass.simpleName)
            throw error
        } finally {
            SdkRuntimeTrace.event("NaviHelper", "route.result", "status" to if(started) "started" else requestOutcome,
                "drivingObserved" to drivingObserved,"destinationConfirmed" to started,"appRequestId" to token.id,
                "elapsedMs" to navigationObservations.elapsedMillis(token))
            synchronized(navigationLock) {
                navigationObservations.finish(token);routeResult=null;destinationResult=null;navigationError=null;navigationInvalidated=null
            }
            result.cancel()
            if(!started)runCatching { disconnectNavigation() }.onFailure { diagnostic="내비 연결 종료 확인 필요: ${it.javaClass.simpleName}" }
        }
      }
    }
    private fun disconnectNavigation(){
        val resources=synchronized(navigationLock) {
            navigationGeneration++
            navigationConnectionStatus="not_connected"
            navigationInvalidated?.invoke()
            navigationObservations.invalidate()
            tripResult=null;routeResult=null;destinationResult=null;navigationError=null;navigationInvalidated=null
            Triple(helper,navigation,naviListener).also { navigation=null;helper=null;naviListener=null }
        }
        try{resources.third?.let{resources.first?.removeListener(it)}}finally{resources.second?.close()}
    }
    suspend fun location()=assistant.location()
    suspend fun arrivalWatch(site:Campsite)=assistant.arrivalWatch(site)
    suspend fun speak(text:String)=assistant.speak(text)
    suspend fun listen()=assistant.listen()
    fun setLanguage(tag:String)=assistant.setLanguage(tag)
    fun assistStatus()=assistant.assistStatus()
    fun start(){if(!closed){synchronized(navigationLock){navigationForeground=true};synchronized(topologyLock){connectionEpoch++;readAvailability.reset(connectionEpoch);readDiagnostic="";active=false;soc=null;socObservedAt=null;capabilitySnapshot=CapabilitySnapshot()};runCatching{assistant.start()}}}
    fun stop(){synchronized(navigationLock){navigationForeground=false};synchronized(topologyLock){connectionEpoch++;readAvailability.reset(connectionEpoch);readDiagnostic="";active=false;capabilitySnapshot=CapabilitySnapshot()};try{adapter.stop()}finally{soc=null;socObservedAt=null;try{disconnectNavigation()}finally{assistant.stop()}}}
    fun close(){if(closed)return;closed=true;try{stop()}finally{try{adapter.close()}finally{assistant.close()}}}
}
