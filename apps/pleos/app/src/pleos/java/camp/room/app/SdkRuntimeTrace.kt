package camp.room.app

import android.util.Log
import org.json.JSONObject

/** Local debug diagnostics only. Call sites pass SDK scalars/statuses, never user text. */
internal object SdkRuntimeTrace {
    private const val TAG = "CampSdkRuntime"

    fun event(module: String, event: String, vararg fields: Pair<String, Any?>) {
        if (!BuildConfig.DEBUG || BuildConfig.IS_DEMO) return
        runCatching {
            val record = JSONObject().put("module", module).put("event", event)
            fields.forEach { (key, raw) ->
                val value = when (raw) {
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
