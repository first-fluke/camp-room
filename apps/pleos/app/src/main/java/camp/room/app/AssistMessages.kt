package camp.room.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Localizes app-owned SDK outcome messages without translating provider names or altering evidence.
 */
object AssistMessages {
    fun result(operation: String, original: JSONObject): JSONObject {
        val value = JSONObject(original.toString())
        val status = value.optString("status").lowercase(java.util.Locale.ROOT)
        val key =
            when (operation) {
                "location" ->
                    if (status == "available") R.string.assist_location_observed
                    else R.string.assist_location_unavailable
                "arrival" -> arrivalKey(status)
                "tts" ->
                    when (status) {
                        "started",
                        "speaking",
                        "playing" -> R.string.assist_tts_started
                        "completed",
                        "done" -> R.string.assist_tts_completed
                        "ready",
                        "available" -> R.string.assist_tts_ready
                        "stopped",
                        "cancelled" -> R.string.assist_tts_stopped
                        else -> R.string.assist_tts_unavailable
                    }
                "stt" ->
                    when (status) {
                        "recognized" -> R.string.assist_stt_recognized
                        "started",
                        "listening" -> R.string.assist_stt_listening
                        "ended" -> R.string.assist_stt_ended
                        "ready",
                        "idle" -> R.string.assist_stt_ready
                        else -> R.string.assist_stt_unavailable
                    }
                "navigation" ->
                    if (value.optBoolean("started")) {
                        if (BuildConfig.IS_DEMO) R.string.assist_navigation_demo_started
                        else R.string.assist_navigation_started
                    } else R.string.assist_navigation_unavailable
                else -> R.string.assist_status_unknown
            }
        value.put("sourceMessage", original.optString("message"))
        value.put("message", CampText.get(key))
        if (operation == "location") {
            value.put("sourceDetails", original.optString("source"))
            value.put(
                "source",
                if (BuildConfig.IS_DEMO) "Android LocationManager" else "Pleos FusedLocation",
            )
        }
        return value
    }

    fun status(original: JSONObject): JSONObject =
        JSONObject(original.toString()).apply {
            put("arrivalSourceMessage", original.optString("arrivalMessage"))
            put(
                "arrivalMessage",
                CampText.get(arrivalKey(original.optString("arrivalStatus").lowercase(java.util.Locale.ROOT))),
            )
        }

    private fun arrivalKey(status: String): Int =
        when (status) {
            "registered" -> R.string.assist_arrival_registered
            "inside" -> R.string.assist_arrival_inside
            "entered",
            "enter",
            "entry" -> R.string.assist_arrival_entered
            "exited",
            "exit" -> R.string.assist_arrival_exited
            "idle",
            "waiting" -> R.string.assist_arrival_idle
            else -> R.string.assist_arrival_unavailable
        }

    fun vehicle(original: JSONObject): JSONObject =
        JSONObject(original.toString()).apply {
            val output = JSONArray()
            val input = original.optJSONArray("results") ?: JSONArray()
            for (index in 0 until input.length()) {
                val old = input.optJSONObject(index) ?: continue
                val item = JSONObject(old.toString())
                val target = old.opt("target")
                val observed = old.opt("observed")
                val comparable =
                    (target is Number && observed is Number) ||
                        (target is Boolean && observed is Boolean)
                val matches =
                    when {
                        target is Number && observed is Number ->
                            kotlin.math.abs(target.toDouble() - observed.toDouble()) < .001
                        target is Boolean && observed is Boolean -> target == observed
                        else -> false
                    }
                val key =
                    when {
                        old.optString("status") == "unsupported" ->
                            R.string.assist_control_unsupported
                        old.optString("status") == "blocked" -> R.string.assist_control_blocked
                        old.optString("status") == "failed" -> R.string.assist_control_failed
                        old.optString("confirmation") == "observed_mismatch" || (old.optString("confirmation") == "observed" && comparable && !matches) ->
                            R.string.assist_control_observed_different
                        old.optString("confirmation") == "observed" ->
                            R.string.assist_control_observed
                        old.optString("confirmation") == "acknowledged" ->
                            R.string.assist_control_acknowledged
                        else -> R.string.assist_control_unverified
                    }
                item.put("sourceMessage", old.optString("message"))
                item.put("message", CampText.get(key))
                output.put(item)
            }
            put("results", output)
        }
}
