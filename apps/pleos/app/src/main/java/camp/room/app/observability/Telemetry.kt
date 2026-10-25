package camp.room.app.observability

import java.io.File
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** No arbitrary event attributes: user input and vehicle data cannot enter this port. */
enum class Event { APP_STARTED, SCREEN_VIEWED, ACTION_COMPLETED, API_COMPLETED, SDK_COMPLETED, FAILURE, CRASH, CLOUD_STATE }
enum class Screen { FIND, TRIP, CABIN, SHOP, CART, VOICE }
enum class Action { LOAD, SEARCH, SAVE_TRIP, NAVIGATION, APPLY_SCENE, CONTROL, ADD_PRODUCT, CART_QUANTITY, QUOTE, CHECKOUT, LOCATION, ARRIVAL_WATCH, BRIEFING, LISTEN, VOICE_PREVIEW }
enum class ApiOperation { BOOTSTRAP, SEARCH_CAMPSITES, SAVE_TRIP, WEATHER, ADD_CART_ITEM, UPDATE_CART_ITEM, DELETE_CART_ITEM, QUOTE_CHECKOUT, CREATE_ORDER, VEHICLE, SCENE, CONTROL, NAVIGATION_PREVIEW, NAVIGATION_START }
enum class SdkOperation { VEHICLE_STATE, VEHICLE_SCENE, VEHICLE_CONTROL, NAVIGATION_PREVIEW, NAVIGATION_START, LOCATION, ARRIVAL_WATCH, TTS, STT, LIFECYCLE }
enum class RuntimeMode { DEMO, PLEOS }
enum class OrderMode { DEMO }
enum class Outcome { SUCCESS, FAILED, UNAVAILABLE, BLOCKED, PARTIAL, CANCELLED, STARTED, UNVERIFIED, OBSERVED_MISMATCH }

data class Dimensions(
    val runtime: RuntimeMode? = null,
    val orderMode: OrderMode? = null,
    val screen: Screen? = null,
    val action: Action? = null,
    val api: ApiOperation? = null,
    val sdk: SdkOperation? = null,
    val outcome: Outcome? = null,
    val durationMs: Long? = null,
    val statusCode: Int? = null,
    val errorType: String? = null,
)

interface Telemetry {
    fun initialize()
    fun record(event: Event, dimensions: Dimensions = Dimensions())
    fun failure(error: Throwable, dimensions: Dimensions = Dimensions())
}

@Serializable
data class DiagnosticRecord(
    val timestamp: String,
    val event: String,
    val attributes: Map<String, String>,
    val crash: CrashSummary? = null,
)
@Serializable data class CrashSummary(val type: String, val frames: List<CrashFrame>)
@Serializable data class CrashFrame(val type: String, val method: String, val line: Int)

/** A second boundary for vendor SDKs which add automatic device/default properties. */
object TelemetryPolicy {
    private fun Enum<*>.token() = name.lowercase(Locale.ROOT)
    private val safeClass = Regex("[A-Za-z0-9_.$]{1,120}")
    fun errorType(error: Throwable): String = error.javaClass.name.takeIf { safeClass.matches(it) } ?: "Throwable"
    fun attributes(value: Dimensions): Map<String, String> = buildMap {
        value.runtime?.let { put("runtime",it.token()) }
        value.orderMode?.let { put("order_mode",it.token()) }
        value.screen?.let { put("screen",it.token()) }
        value.action?.let { put("action",it.token()) }
        value.api?.let { put("api",it.token()) }
        value.sdk?.let { put("sdk",it.token()) }
        value.outcome?.let { put("outcome",it.token()) }
        value.durationMs?.let { put("duration_ms",it.coerceIn(0,600_000).toString()) }
        value.statusCode?.takeIf { it in 100..599 }?.let { put("status_code",it.toString()) }
        value.errorType?.takeIf { safeClass.matches(it) }?.let { put("error_type",it) }
    }
    private val values = mapOf(
        "runtime" to RuntimeMode.entries.map { it.token() }.toSet(),
        "order_mode" to OrderMode.entries.map { it.token() }.toSet(),
        "screen" to Screen.entries.map { it.token() }.toSet(),
        "action" to Action.entries.map { it.token() }.toSet(),
        "api" to ApiOperation.entries.map { it.token() }.toSet(),
        "sdk" to SdkOperation.entries.map { it.token() }.toSet(),
        "outcome" to Outcome.entries.map { it.token() }.toSet(),
    )
    fun filter(attributes: Map<String, Any>): Map<String, String> = attributes.mapNotNull { (key,raw) ->
        val value=raw.toString()
        when {
            values[key]?.contains(value)==true -> key to value
            key=="duration_ms" && value.toLongOrNull()?.let { it in 0..600_000 }==true -> key to value
            key=="status_code" && value.toIntOrNull()?.let { it in 100..599 }==true -> key to value
            key=="error_type" && safeClass.matches(value) -> key to value
            else -> null
        }
    }.toMap()
    fun crash(error: Throwable) = CrashSummary(errorType(error),error.stackTrace.take(32).map {
        CrashFrame(it.className.take(120),it.methodName.take(100),it.lineNumber)
    })
    fun name(event: Event) = "camp_"+event.token()
    fun isKnownEvent(value: String) = Event.entries.any { name(it)==value }
    fun record(event: Event, dimensions: Dimensions, error: Throwable? = null) =
        DiagnosticRecord(Instant.now().toString(),name(event),attributes(dimensions),error?.let(::crash))
}

/** Two bounded app-private JSONL generations. No logcat, external storage, or raw throwable text. */
class DiagnosticStore(private val directory: File, private val maxBytes: Long = 128*1024) {
    private val codec = Json { encodeDefaults=true }
    @Synchronized fun append(record: DiagnosticRecord) {
        directory.mkdirs()
        val current=File(directory,"events.jsonl")
        val line=codec.encodeToString(record)+"\n"
        if(line.toByteArray().size>maxBytes)return
        if(current.exists() && current.length()+line.toByteArray().size>maxBytes) {
            val previous=File(directory,"events.previous.jsonl")
            previous.delete()
            if(!current.renameTo(previous)) current.delete()
        }
        current.appendText(line)
    }
}

/** Crash recording cannot swallow or replace Android/Sentry's existing fatal exception handler. */
class RecordingExceptionHandler(
    private val store: DiagnosticStore,
    private val delegate: Thread.UncaughtExceptionHandler,
):Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try { store.append(TelemetryPolicy.record(Event.CRASH,Dimensions(errorType=TelemetryPolicy.errorType(error)),error)) }
        catch (_: Throwable) { /* The original fatal handler must always run. */ }
        finally { delegate.uncaughtException(thread,error) }
    }
}
