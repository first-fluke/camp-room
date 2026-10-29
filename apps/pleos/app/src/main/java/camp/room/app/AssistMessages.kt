package camp.room.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Localizes app-owned SDK outcome messages without translating provider names or altering evidence.
 */
object AssistMessages {
    fun result(
        operation: String,
        original: JSONObject,
    ): JSONObject {
        val value = JSONObject(original.toString())
        val status = value.optString("status").lowercase(java.util.Locale.ROOT)
        val key =
            when (operation) {
                "location" -> {
                    if (status == "available") {
                        R.string.assist_location_observed
                    } else {
                        R.string.assist_location_unavailable
                    }
                }

                "arrival" -> {
                    arrivalKey(status)
                }

                "tts" -> {
                    speechFailureKey(operation, speechErrorCode(value, "errorCode"))
                        ?: if (!value.optBoolean("retryable", true)) {
                            R.string.camp_voice_unavailable_reopen
                        } else {
                            when (status) {
                                "initializing", "processing" -> R.string.camp_voice_in_progress

                                "started",
                                "speaking",
                                "playing",
                                -> R.string.assist_tts_started

                                "completed",
                                "done",
                                -> R.string.camp_tts_completed

                                "ready",
                                "available",
                                -> R.string.assist_tts_ready

                                "stopped",
                                "cancelled",
                                -> R.string.assist_tts_stopped

                                else -> R.string.camp_tts_failed
                            }
                        }
                }

                "stt" -> {
                    speechFailureKey(operation, speechErrorCode(value, "errorCode"))
                        ?: if (!value.optBoolean("retryable", true)) {
                            R.string.camp_voice_unavailable_reopen
                        } else {
                            when (status) {
                                "initializing", "processing" -> R.string.camp_voice_in_progress

                                "recognized" -> R.string.camp_stt_final_result

                                "started",
                                "listening",
                                -> R.string.assist_stt_listening

                                "ended" -> R.string.assist_stt_ended

                                "ready",
                                "idle",
                                -> R.string.assist_stt_ready

                                else -> R.string.camp_stt_unavailable
                            }
                        }
                }

                "navigation" -> {
                    if (value.optBoolean("started")) {
                        if (BuildConfig.IS_DEMO) {
                            R.string.assist_navigation_demo_started
                        } else {
                            R.string.assist_navigation_started
                        }
                    } else {
                        R.string.assist_navigation_unavailable
                    }
                }

                else -> {
                    R.string.assist_status_unknown
                }
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
            for (operation in listOf("tts", "stt")) {
                val state = original.optString("${operation}Status").lowercase(java.util.Locale.ROOT)
                val errorCode = speechErrorCode(original, "${operation}ErrorCode")
                val hasOutcome = state in setOf("unavailable", "failed", "error", "stopped", "cancelled", "done", "completed", "recognized")
                if (original.optString("${operation}Message").isNotBlank() || errorCode.isNotBlank() ||
                    !original.optBoolean("${operation}Retryable", true) || hasOutcome
                ) {
                    val voice =
                        JSONObject()
                            .put("status", state)
                            .put("errorCode", errorCode)
                            .put("retryable", original.optBoolean("${operation}Retryable", true))
                    put("${operation}Message", result(operation, voice).optString("message"))
                }
            }
        }

    private fun speechErrorCode(
        value: JSONObject,
        key: String,
    ): String = if (value.isNull(key)) "" else value.optString(key).takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()

    private fun speechFailureKey(
        operation: String,
        errorCode: String,
    ): Int? =
        when (errorCode.uppercase(java.util.Locale.ROOT)) {
            "PERMISSION_DENIED", "PERMISSION_REQUIRED" -> {
                R.string.camp_voice_permission_needed
            }

            "ON_DEVICE_ENGINE_ERROR", "NOT_SUPPORTED_API", "UNAVAILABLE_LANGUAGE" -> {
                if (operation == "tts") {
                    R.string.camp_tts_engine_unavailable
                } else {
                    R.string.camp_stt_unavailable
                }
            }

            "TIMEOUT", "INITIALIZE_TIMEOUT", "START_TIMEOUT", "COMPLETION_TIMEOUT", "RESULT_TIMEOUT" -> {
                if (operation == "tts") R.string.camp_tts_timeout else R.string.camp_stt_no_result
            }

            "CANCELLED" -> {
                R.string.camp_voice_cancelled
            }

            "CLEANUP_PENDING" -> {
                R.string.camp_voice_cleanup_pending
            }

            "IN_PROGRESS" -> {
                R.string.camp_voice_in_progress
            }

            "NO_RESULT", "EMPTY_RESULT" -> {
                R.string.camp_stt_no_result
            }

            else -> {
                null
            }
        }

    private fun arrivalKey(status: String): Int =
        when (status) {
            "registered" -> R.string.assist_arrival_registered

            "inside" -> R.string.assist_arrival_inside

            "entered",
            "enter",
            "entry",
            -> R.string.assist_arrival_entered

            "exited",
            "exit",
            -> R.string.assist_arrival_exited

            "idle",
            "waiting",
            -> R.string.assist_arrival_idle

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
                        target is Number && observed is Number -> {
                            kotlin.math.abs(target.toDouble() - observed.toDouble()) < .001
                        }

                        target is Boolean && observed is Boolean -> {
                            target == observed
                        }

                        else -> {
                            false
                        }
                    }
                val key =
                    when {
                        old.optString("status") == "unsupported" -> {
                            R.string.assist_control_unsupported
                        }

                        old.optString("status") == "blocked" -> {
                            R.string.assist_control_blocked
                        }

                        old.optString("status") == "failed" -> {
                            R.string.assist_control_failed
                        }

                        old.optString("confirmation") == "observed_mismatch" ||
                            (old.optString("confirmation") == "observed" && comparable && !matches) -> {
                            R.string.assist_control_observed_different
                        }

                        old.optString("confirmation") == "observed" -> {
                            R.string.assist_control_observed
                        }

                        old.optString("confirmation") == "acknowledged" -> {
                            R.string.assist_control_acknowledged
                        }

                        else -> {
                            R.string.assist_control_unverified
                        }
                    }
                item.put("sourceMessage", old.optString("message"))
                item.put("message", CampText.get(key))
                output.put(item)
            }
            put("results", output)
        }
}
