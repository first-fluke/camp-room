package camp.room.app

import android.util.Log
import org.json.JSONObject

/** Local debug diagnostics only. Call sites pass SDK scalars/statuses, never user text. */
internal object SdkRuntimeTrace {
    private const val TAG = "CampSdkRuntime"
    private val identity =
        SdkTraceIdentity(
            java.util.UUID
                .randomUUID()
                .toString(),
        )
    private val reserved = setOf("module", "event", "traceProcessId", "traceSequence", "traceElapsedMillis", "traceAttribution")

    fun newOwner(kind: String): SdkTraceOwner = identity.owner(kind)

    /** Epoch/request values must be captured by the caller, never read from a new session. */
    fun ownedEvent(
        module: String,
        event: String,
        owner: SdkTraceOwner,
        vararg fields: Pair<String, Any?>,
    ) {
        val values = fields.toMap()
        val epoch = (values["sessionEpoch"] ?: values["requestEpoch"] ?: values["epoch"]) as? Number
        val request = (values["requestId"] ?: values["appRequestId"] ?: values["correlationId"]) as? Number
        val namespace = (values["requestNamespace"] ?: values["operation"] ?: values["kind"]) as? String ?: "unscoped"
        val context = SdkTraceContext(owner, epoch?.toInt(), request?.toLong(), namespace)
        this.event(module, event, *fields, *context.fields())
    }

    fun event(
        module: String,
        event: String,
        vararg fields: Pair<String, Any?>,
    ) {
        if (!BuildConfig.DEBUG || BuildConfig.IS_DEMO) return
        runCatching {
            val stamp = identity.stamp()
            val scope = fields.toMap()
            val attribution =
                when {
                    scope["ownerId"] != null && scope["requestKey"] != null -> "request"
                    scope["ownerId"] != null && scope["sessionEpoch"] != null -> "owner_session"
                    scope["ownerId"] != null -> "owner_only"
                    else -> "process_only"
                }
            val record =
                JSONObject()
                    .put("module", module)
                    .put("event", event)
                    .put("traceProcessId", stamp.processId)
                    .put("traceSequence", stamp.sequence)
                    .put("traceElapsedMillis", stamp.elapsedMillis)
                    .put("traceAttribution", attribution)
            fields.forEach { (key, raw) ->
                if (key in reserved) return@forEach
                val value =
                    when (raw) {
                        null -> JSONObject.NULL
                        is Double -> raw.takeIf { it.isFinite() } ?: JSONObject.NULL
                        is Float -> raw.takeIf { it.isFinite() } ?: JSONObject.NULL
                        is Number, is Boolean, is String -> raw
                        else -> return@forEach
                    }
                record.put(key, value)
            }
            Log.d(TAG, record.toString())
        }
    }
}
