package camp.room.app

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale
import javax.inject.Inject
import javax.inject.Provider
import kotlin.coroutines.resume
import ai.pleos.playground.stt.SpeechToText as PleosStt
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.stt.listener.ResultListener as SttResults
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.listener.EventListener as TtsEvents
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/** All three remaining SDK modules are local, ON_DEVICE voice uses no embedded server secret. */
@ViewModelScoped
class PleosAssistAdapter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val fused: FusedLocation,
        private val ttsFactory: Provider<PleosTts>,
        private val sttFactory: Provider<PleosStt>,
    ) {
        private companion object {
            val retainedClose = RetainedCleanupOwner()
        }

        @Volatile private var speechLocale = Locale.KOREA
        private var initialized = false
        private var locationReleaseRequired = false

        @Volatile private var active = false
        private var locationActive = false

        @Volatile private var closed = false
        private val locationIo = LocationSdkIo()
        private val locationLock = locationIo.stateLock
        private var epoch = 0
        private var locationListener: FusedLocationListener? = null
        private val pendingLocationCleanup = LocationListenerCleanup<FusedLocationListener>()
        private var latest: LocationData? = null
        private var watchedSite: Campsite? = null
        private var alertId: String? = null
        private val watchEpoch = LocationWatchEpoch()
        private var pendingRegistration = false
        private val pendingCleanup = mutableSetOf<String>()
        private var liveLocationSequence = 0
        private val speechLock = Any()
        private val speechConstruction = SdkResourceConstruction(speechLock)
        private var speechHostEpoch = 0L
        private val ttsRequests = Mutex()
        private val sttRequests = Mutex()
        private val speechCleanupJob = SupervisorJob()
        private val speechCleanupScope = CoroutineScope(Dispatchers.IO + speechCleanupJob)
        private val ttsSession = SpeechSessionState(SpeechKind.PLAYBACK)
        private val sttSession = SpeechSessionState(SpeechKind.RECOGNITION)

        private open class SpeechRun<E>(
            val engine: E,
            val token: Long,
        ) {
            val sdkLock = Any()

            @Volatile var closedForCalls = false
            var requested = false
            val cleanup = SpeechCleanupState()
            val signals = SpeechRunSignals()
            val initialized get() = signals.initialized
            val started get() = signals.started
            val terminal get() = signals.terminal
        }

        private class TtsRun(
            engine: PleosTts,
            token: Long,
        ) : SpeechRun<PleosTts>(engine, token) {
            var listener: TtsEvents? = null
        }

        private class SttRun(
            engine: PleosStt,
            token: Long,
        ) : SpeechRun<PleosStt>(engine, token) {
            var listener: SttResults? = null
            val diagnostics = SpeechRecognitionDiagnostics(token)
            var cleanupReported = false
            var cleanupSuccessReported = false
        }

        private var ttsRun: TtsRun? = null
        private var sttRun: SttRun? = null
        private val stagingTtsRuns = mutableSetOf<TtsRun>()
        private val stagingSttRuns = mutableSetOf<SttRun>()
        private val pendingTtsCleanup = mutableSetOf<TtsRun>()
        private val pendingSttCleanup = mutableSetOf<SttRun>()
        private var ttsBlockedReason: String? = null
        private var sttBlockedReason: String? = null

        @Volatile private var arrivalState = "idle"

        @Volatile private var arrivalMessage = "도착 알림을 아직 설정하지 않았습니다."

        @Volatile private var ttsState = "idle"

        @Volatile private var sttState = "idle"

        @Volatile private var ttsMessage = ""

        @Volatile private var sttMessage = ""

        @Volatile private var ttsErrorCode: String? = null

        @Volatile private var sttErrorCode: String? = null

        @Volatile private var ttsRetryable = true

        @Volatile private var sttRetryable = true

        private fun hasPermission(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        private fun hasLocationPermission() = hasPermission("pleos.car.permission.FUSED_LOCATION")

        private fun result(
            status: String,
            message: String,
        ) = JSONObject().put("status", status).put("message", message)

        private fun valid(location: LocationData?) =
            location != null && location.latitude.isFinite() && location.longitude.isFinite() &&
                location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0

        private fun accept(location: LocationData?) {
            latest = location.takeIf(::valid)
        }

        private fun traceLocation(
            event: String,
            location: LocationData?,
            current: Boolean,
        ) {
            val receivedAt = System.currentTimeMillis()
            val timestamp = location?.timestampMillis
            traceFused(
                event,
                "current" to current,
                "present" to (location != null),
                "latitude" to location?.latitude,
                "longitude" to location?.longitude,
                "coordinatesValid" to valid(location),
                "sdkTimestampMillis" to timestamp,
                "timestampPositive" to (timestamp != null && timestamp > 0),
                "timestampEpochPlausible" to (timestamp != null && timestamp in 946684800000L..receivedAt),
                "receivedAtEpochMillis" to receivedAt,
            )
        }

        private fun traceFused(
            event: String,
            vararg fields: Pair<String, Any?>,
        ) {
            SdkRuntimeTrace.event(
                "FusedLocation",
                event,
                "assistantInstance" to System.identityHashCode(this),
                "fusedInstance" to System.identityHashCode(fused),
                "onMainThread" to (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()),
                *fields,
            )
        }

        fun start() {
            retainedClose.requireClearExcept(this)
            val registration =
                synchronized(locationLock) {
                    if (closed) return
                    if (!active) {
                        synchronized(speechLock) {
                            ttsBlockedReason = null
                            sttBlockedReason = null
                            ttsRetryable = pendingTtsCleanup.isEmpty()
                            sttRetryable = pendingSttCleanup.isEmpty()
                        }
                    }
                    active = true
                    if (locationActive) return
                    epoch = locationIo.advance()
                    epoch
                }
            locationIo.native { startLocation(registration) }
        }

        private fun locationCurrent(registration: Int) = active && !closed && epoch == registration && locationIo.current(registration)

        private fun startLocation(registration: Int) {
            if (!synchronized(locationLock) { locationCurrent(registration) }) return
            if (!hasLocationPermission()) {
                synchronized(locationLock) {
                    if (locationCurrent(registration)) {
                        detachLocationLocked()
                        arrivalState = "unavailable"
                        arrivalMessage = "위치 권한을 허용한 뒤 다시 시도해 주세요."
                    }
                }
                traceFused("permission.required")
                return
            }
            val supported = fused.isSupported()
            traceFused("isSupported", "supported" to supported)
            if (!supported) {
                arrivalMessage = "이 차량의 FusedLocation 지원 상태를 확인해 주세요."
                return
            }
            unregisterLocationListeners()
            if (pendingLocationCleanup.isNotEmpty()) return
            val initialize =
                synchronized(locationLock) {
                    if (!locationCurrent(registration)) return
                    if (initialized) {
                        false
                    } else {
                        check(!locationReleaseRequired) { "Previous Fused initialization needs cleanup" }
                        locationReleaseRequired = true
                        true
                    }
                }
            if (initialize) {
                traceFused("initialize.request")
                try {
                    fused.initialize()
                    synchronized(locationLock) { initialized = true }
                    traceFused("initialize.returned")
                } catch (error: Exception) {
                    traceFused("initialize.error", "errorClass" to error.javaClass.simpleName)
                    throw error
                }
            }
            synchronized(locationLock) { pendingCleanup.toList() }.forEach(::cleanup)
            val listener =
                object : FusedLocationListener {
                    override fun onChanged(location: LocationData?) {
                        synchronized(locationLock) {
                            val current = locationCurrent(registration) && locationActive && locationListener === this
                            traceLocation("subscription.onChanged", location, current)
                            if (current) {
                                liveLocationSequence++
                                accept(location)
                            }
                        }
                    }

                    override fun onError(exception: Exception) {
                        synchronized(locationLock) {
                            val current = locationCurrent(registration) && locationActive && locationListener === this
                            traceFused("subscription.onError", "errorClass" to exception.javaClass.simpleName, "current" to current)
                            if (current) {
                                latest = null
                                arrivalMessage = "차량 위치 권한과 연결을 확인해 주세요."
                            }
                        }
                    }
                }
            synchronized(locationLock) {
                if (!locationCurrent(registration)) return
                locationActive = true
                locationListener = listener
            }
            try {
                traceFused("register.request")
                fused.registerFusedLocationCallback(listener)
                traceFused("register.returned")
            } catch (error: Exception) {
                traceFused("register.error", "errorClass" to error.javaClass.simpleName)
                synchronized(locationLock) {
                    if (locationListener === listener) {
                        locationActive = false
                        locationListener = null
                    }
                    pendingLocationCleanup.add(listener)
                }
                unregisterLocationListeners()
                throw error
            }
            val stale = synchronized(locationLock) { !locationCurrent(registration) || locationListener !== listener }
            if (stale) {
                pendingLocationCleanup.add(listener)
                unregisterLocationListeners()
                return
            }
            synchronized(locationLock) { watchedSite }.let { if (it != null) registerWatch(it) {} }
        }

        suspend fun location(): JSONObject =
            withContext(Dispatchers.IO) {
                // Current callers are the verified LOCATION user command, not lifecycle start.
                retainedClose.retryAfterExplicitRequest()
                try {
                    start()
                } catch (error: Exception) {
                    return@withContext result("unavailable", "Pleos 위치 권한과 연결을 확인해 주세요.")
                }
                if (!hasLocationPermission()) {
                    return@withContext result("unavailable", "위치 권한을 허용한 뒤 다시 시도해 주세요.")
                        .put("errorCode", "PERMISSION_REQUIRED")
                        .put("retryable", true)
                }
                if (pendingLocationCleanup.isNotEmpty()) {
                    return@withContext result("unavailable", arrivalMessage)
                        .put("errorCode", "CLEANUP_PENDING")
                        .put("retryable", true)
                }
                val state =
                    synchronized(locationLock) { if (!locationActive || closed) null else epoch to liveLocationSequence }
                        ?: return@withContext result("unavailable", "이 차량에서 위치 제공 여부를 확인해 주세요.")
                val (registration, liveSequence) = state
                val sample =
                    withTimeoutOrNull(3000) {
                        suspendCancellableCoroutine<LocationData?> { continuation ->
                            val listener =
                                object : FusedLocationListener {
                                    override fun onChanged(location: LocationData?) {
                                        val value =
                                            synchronized(locationLock) {
                                                val current = locationCurrent(registration) && locationActive
                                                traceLocation("getLastKnown.onChanged", location, current)
                                                if (current) {
                                                    if (liveLocationSequence == liveSequence) accept(location)
                                                    latest
                                                } else {
                                                    null
                                                }
                                            }
                                        if (continuation.isActive) continuation.resume(value)
                                    }

                                    override fun onError(exception: Exception) {
                                        traceFused(
                                            "getLastKnown.onError",
                                            "errorClass" to exception.javaClass.simpleName,
                                            "continuationActive" to continuation.isActive,
                                        )
                                        if (continuation.isActive) continuation.resume(null)
                                    }
                                }
                            try {
                                locationIo.native {
                                    if (synchronized(locationLock) { locationCurrent(registration) && locationActive }) {
                                        traceFused("getLastKnown.request")
                                        fused.getLastKnownLocation(listener)
                                        traceFused("getLastKnown.returned")
                                    } else if (continuation.isActive) {
                                        continuation.resume(null)
                                    }
                                }
                            } catch (error: Exception) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                        }
                    }
                if (valid(sample)) {
                    result("available", "차량 SDK 최근 위치입니다. 측정 시각을 확인해 주세요.")
                        .put("latitude", sample!!.latitude)
                        .put("longitude", sample.longitude)
                        .put("timestampMillis", JSONObject.NULL)
                        .put("sdkTimestampMillis", sample.timestampMillis)
                        .put(
                            "receivedAt",
                            java.time.Instant
                                .now()
                                .toString(),
                        ).put("confidence", if (sample.confidence.isFinite()) sample.confidence.toDouble() else JSONObject.NULL)
                        .put("source", "Pleos FusedLocation 1.1.3")
                        .put("lastKnown", true)
                } else {
                    result("unavailable", "위치 관측값을 받지 못했습니다. 권한과 위치 서비스를 확인해 주세요.")
                }
            }

        private fun registerWatch(
            site: Campsite,
            complete: (JSONObject) -> Unit,
        ) = locationIo.native {
            val lat = site.latitude
            val lon = site.longitude
            val eligible = synchronized(locationLock) { active && locationActive && !closed }
            if (!eligible || lat == null || lon == null || !lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 ||
                lon !in -180.0..180.0
            ) {
                complete(result("unavailable", "현재 위치 연결과 캠핑장 좌표를 확인해 주세요."))
                return@native
            }
            val old =
                synchronized(locationLock) {
                    if (pendingRegistration || pendingCleanup.isNotEmpty()) {
                        complete(result("unavailable", "이전 도착 알림 등록/해제 응답을 기다리고 있습니다."))
                        return@native
                    }
                    alertId.also {
                        alertId = null
                        it?.let(pendingCleanup::add)
                    }
                }
            old?.let(::cleanup)
            val token =
                synchronized(locationLock) {
                    if (pendingCleanup.isNotEmpty() || !active || !locationActive || closed) {
                        complete(result("unavailable", "기존 도착 알림 해제를 먼저 확인해야 합니다."))
                        return@native
                    }
                    pendingRegistration = true
                    watchEpoch.begin()
                }
            var waitingForId = true
            val listener =
                object : ProximityAlertListener {
                    override fun onRegistered(id: String) {
                        val permission = hasLocationPermission()
                        val response =
                            synchronized(locationLock) {
                                if (waitingForId) {
                                    waitingForId = false
                                    pendingRegistration = false
                                }
                                if (active && locationActive && !closed && permission && watchEpoch.isCurrent(token)) {
                                    alertId = id
                                    arrivalState = "registered"
                                    arrivalMessage = "캠핑장 반경500m 도착/이탈 알림이 등록되었습니다. 진입 감지와는 다릅니다."
                                    result("registered", arrivalMessage).put("alertId", id)
                                } else {
                                    pendingCleanup.add(id)
                                    null
                                }
                            }
                        if (response != null) complete(response) else requestLocationCleanup()
                    }

                    override fun onTransition(event: ProximityAlertEvent) {
                        val permission = hasLocationPermission()
                        synchronized(locationLock) {
                            if (active && locationActive && !closed && permission && watchEpoch.isCurrent(token) &&
                                event.alertId == alertId
                            ) {
                                arrivalState = if (event.transition == ProximityTransition.ENTER) "entered" else "exited"
                                arrivalMessage =
                                    if (event.transition ==
                                        ProximityTransition.ENTER
                                    ) {
                                        "캠핑장 주변 진입 이벤트를 확인했습니다. 차량은 자동으로 제어하지 않습니다."
                                    } else {
                                        "캠핑장 주변 이탈 이벤트를 확인했습니다."
                                    }
                            }
                        }
                    }

                    override fun onError(exception: Exception) {
                        val response =
                            synchronized(locationLock) {
                                if (waitingForId) {
                                    waitingForId = false
                                    pendingRegistration = false
                                }
                                if (watchEpoch.isCurrent(token) && !closed) {
                                    arrivalState = "unavailable"
                                    arrivalMessage = "도착 알림 권한/연결을 확인해 주세요."
                                    result("unavailable", arrivalMessage)
                                } else {
                                    null
                                }
                            }
                        response?.let(complete)
                        requestLocationCleanup()
                    }
                }
            try {
                fused.addProximityAlert(
                    ProximityAlertRequest(lat, lon, 500f, setOf(ProximityTransition.ENTER, ProximityTransition.EXIT)),
                    listener,
                )
            } catch (
                error: Exception,
            ) {
                synchronized(locationLock) {
                    if (waitingForId) {
                        waitingForId = false
                        pendingRegistration = false
                    }
                }
                arrivalState = "unavailable"
                arrivalMessage = "도착 알림을 등록하지 못했습니다."
                complete(result("unavailable", arrivalMessage))
                requestLocationCleanup()
            }
        }

        suspend fun arrivalWatch(site: Campsite): JSONObject =
            withContext(Dispatchers.IO) {
                // Current callers are the verified ARRIVAL_WATCH user command.
                retainedClose.retryAfterExplicitRequest()
                try {
                    start()
                } catch (error: Exception) {
                    return@withContext result("unavailable", "Pleos 위치 연결/권한을 확인해 주세요.")
                }
                if (!hasLocationPermission()) {
                    return@withContext result("unavailable", "위치 권한을 허용한 뒤 다시 시도해 주세요.")
                        .put("errorCode", "PERMISSION_REQUIRED")
                        .put("retryable", true)
                }
                synchronized(locationLock) { watchedSite = site }
                var completed = false
                try {
                    val registration =
                        withTimeoutOrNull(3500) {
                            suspendCancellableCoroutine<JSONObject> { continuation ->
                                registerWatch(site) { if (continuation.isActive) continuation.resume(it) }
                            }
                        }
                    completed = registration != null
                    registration ?: result("unavailable", "도착 알림 등록을 확인하지 못했습니다.")
                } finally {
                    if (!completed) {
                        synchronized(locationLock) {
                            watchEpoch.invalidate()
                            alertId?.let(pendingCleanup::add)
                            alertId = null
                        }
                        requestLocationCleanup()
                    }
                }
            }

        private fun ownsTts(run: TtsRun) = synchronized(speechLock) { !closed && active && ttsRun === run }

        private fun ownsStt(run: SttRun) = synchronized(speechLock) { !closed && active && sttRun === run }

        private fun sttDisposition(run: SttRun) =
            recognitionCallbackDisposition(
                hostActive = !closed && active,
                sameRun = sttRun === run,
                sessionCurrent = sttSession.isCurrent(run.token),
            )

        private fun traceSttCallback(
            run: SttRun,
            callback: RecognitionCallback,
            observation: RecognitionCallbackObservation,
            vararg fields: Pair<String, Any?>,
        ) {
            if (observation.shouldTrace) {
                SdkRuntimeTrace.event(
                    "STT",
                    callback.traceName,
                    "sessionEpoch" to run.token,
                    "currentEpoch" to sttRun?.token,
                    "current" to observation.current,
                    "disposition" to observation.disposition.name,
                    "elapsedMillis" to observation.elapsedMillis,
                    *fields,
                )
            }
        }

        private fun sttCallback(
            run: SttRun,
            callback: RecognitionCallback,
            vararg fields: Pair<String, Any?>,
        ): Boolean {
            val observation = run.diagnostics.callback(callback, sttDisposition(run))
            traceSttCallback(run, callback, observation, *fields)
            return observation.current
        }

        private fun fatalVoiceError(code: String?) =
            code == "ON_DEVICE_ENGINE_ERROR" ||
                code?.contains("NOT_SUPPORTED") == true || code?.contains("UNSUPPORTED") == true

        private fun ttsOutcome(
            run: TtsRun,
            outcome: SpeechOutcome?,
        ) {
            if (outcome == null) return
            synchronized(speechLock) {
                // Private/retired runs still own their waiters even when no longer
                // allowed to publish UI state.
                run.signals.settle(outcome)
                if (ttsRun !== run) return
                ttsState =
                    when (outcome.phase) {
                        SpeechPhase.COMPLETED -> "completed"
                        SpeechPhase.CANCELLED -> "stopped"
                        else -> "unavailable"
                    }
                ttsErrorCode = outcome.errorCode
                if (fatalVoiceError(outcome.errorCode)) ttsBlockedReason = outcome.errorCode
                ttsRetryable = ttsBlockedReason == null && pendingTtsCleanup.isEmpty()
                ttsMessage =
                    CampText.get(
                        when {
                            outcome.phase == SpeechPhase.COMPLETED -> R.string.camp_tts_completed
                            outcome.phase == SpeechPhase.CANCELLED -> R.string.camp_voice_cancelled
                            fatalVoiceError(outcome.errorCode) -> R.string.camp_tts_engine_unavailable
                            outcome.phase == SpeechPhase.TIMED_OUT -> R.string.camp_tts_timeout
                            else -> R.string.camp_tts_failed
                        },
                    )
                run.initialized.complete(false)
                run.started.complete(false)
                run.terminal.complete(outcome)
                SdkRuntimeTrace.event(
                    "TTS",
                    "terminal",
                    "status" to ttsState,
                    "errorCode" to outcome.errorCode,
                    "retryable" to ttsRetryable,
                )
            }
        }

        private fun sttOutcome(
            run: SttRun,
            outcome: SpeechOutcome?,
        ) {
            if (outcome == null) return
            synchronized(speechLock) {
                run.signals.settle(outcome)
                if (sttRun !== run) return
                sttState =
                    when (outcome.phase) {
                        SpeechPhase.RECOGNIZED -> "recognized"
                        SpeechPhase.CANCELLED -> "stopped"
                        else -> "unavailable"
                    }
                sttErrorCode = outcome.errorCode
                if (fatalVoiceError(outcome.errorCode)) sttBlockedReason = outcome.errorCode
                sttRetryable = sttBlockedReason == null && pendingSttCleanup.isEmpty()
                sttMessage =
                    CampText.get(
                        when (outcome.phase) {
                            SpeechPhase.RECOGNIZED -> R.string.camp_stt_final_result
                            SpeechPhase.CANCELLED -> R.string.camp_voice_cancelled
                            SpeechPhase.TIMED_OUT -> R.string.camp_stt_no_result
                            else -> R.string.camp_stt_unavailable
                        },
                    )
                run.diagnostics.terminal(outcome.phase)
                run.initialized.complete(false)
                run.terminal.complete(outcome)
                SdkRuntimeTrace.event(
                    "STT",
                    "terminal",
                    "status" to sttState,
                    "errorCode" to outcome.errorCode,
                    "retryable" to sttRetryable,
                    "currentEpoch" to sttRun?.token,
                    *run.diagnostics.fields(),
                )
            }
        }

        private fun unavailableSpeech(
            tts: Boolean,
            code: String,
            message: Int,
            retryable: Boolean,
        ): JSONObject =
            synchronized(speechLock) {
                val text = CampText.get(message)
                if (tts) {
                    ttsState = "unavailable"
                    ttsErrorCode = code
                    ttsMessage = text
                    ttsRetryable = retryable
                } else {
                    sttState = "unavailable"
                    sttErrorCode = code
                    sttMessage = text
                    sttRetryable = retryable
                }
                result("unavailable", text).put("errorCode", code).put("retryable", retryable).put("mode", "ON_DEVICE")
            }

        private fun speechResponse(
            outcome: SpeechOutcome,
            tts: Boolean,
        ): JSONObject =
            synchronized(speechLock) {
                val cleanupPending = if (tts) pendingTtsCleanup.isNotEmpty() else pendingSttCleanup.isNotEmpty()
                val base = if (tts) ttsMessage else sttMessage
                val message = base + if (cleanupPending) " " + CampText.get(R.string.camp_voice_cleanup_pending) else ""
                val response =
                    result(
                        when (outcome.phase) {
                            SpeechPhase.COMPLETED -> "completed"
                            SpeechPhase.RECOGNIZED -> "recognized"
                            SpeechPhase.CANCELLED -> "stopped"
                            else -> "unavailable"
                        },
                        message,
                    ).put("errorCode", outcome.errorCode ?: JSONObject.NULL)
                        .put("retryable", (if (tts) ttsRetryable else sttRetryable) && !cleanupPending)
                        .put("cleanupPending", cleanupPending)
                        .put("mode", "ON_DEVICE")
                if (outcome.phase == SpeechPhase.RECOGNIZED) response.put("text", outcome.text).put("source", "Pleos SpeechToText 3.3.1")
                response
            }

        private fun ttsCall(
            run: TtsRun,
            call: (PleosTts) -> Unit,
        ): Boolean =
            synchronized(run.sdkLock) {
                if (run.closedForCalls || !ownsTts(run) || !ttsSession.isCurrent(run.token)) {
                    false
                } else {
                    call(run.engine)
                    true
                }
            }

        private fun sttCall(
            run: SttRun,
            call: (PleosStt) -> Unit,
        ): Boolean =
            synchronized(run.sdkLock) {
                if (run.closedForCalls || !ownsStt(run) || !sttSession.isCurrent(run.token)) {
                    false
                } else {
                    call(run.engine)
                    true
                }
            }

        /** Each utterance owns a fresh engine: SDK callbacks have no utterance ID. */
        suspend fun speak(text: String): JSONObject =
            ttsRequests.withLock {
                withContext(Dispatchers.IO) {
                    if (text.isBlank()) return@withContext unavailableSpeech(true, "EMPTY_TEXT", R.string.camp_tts_failed, true)
                    if (!hasPermission("pleos.car.permission.TTS_SERVICE")) {
                        return@withContext unavailableSpeech(true, "PERMISSION_REQUIRED", R.string.camp_voice_permission_needed, true)
                    }
                    val blocked = synchronized(speechLock) { ttsBlockedReason }
                    if (blocked != null) return@withContext unavailableSpeech(true, blocked, R.string.camp_tts_engine_unavailable, false)
                    synchronized(speechLock) { pendingTtsCleanup.toList() }.forEach { cleanupTts(it) }
                    if (synchronized(speechLock) { pendingTtsCleanup.isNotEmpty() }) {
                        return@withContext unavailableSpeech(true, "CLEANUP_PENDING", R.string.camp_voice_cleanup_pending, false)
                    }
                    var attempt: TtsRun? = null
                    val creationEpoch = synchronized(speechLock) { speechHostEpoch }
                    val outcome =
                        try {
                            if (closed || !active) throw CancellationException("Speech host is inactive")
                            // speak/listen are entered by the BRIEFING/LISTEN user actions.
                            retainedClose.retryAfterExplicitRequest()
                            retainedClose.requireClearExcept(this)
                            val run =
                                speechConstruction.create(
                                    admit = {
                                        !closed && active && speechHostEpoch == creationEpoch &&
                                            !speechConstruction.hasAbandoned()
                                    },
                                    factory = { ttsFactory.get() },
                                    construct = { engine -> TtsRun(engine, ttsSession.begin()) },
                                    releaseUnwrapped = { it.release() },
                                    adopt = { stagingTtsRuns.add(it) },
                                )
                            attempt = run
                            synchronized(speechLock) {
                                if (closed || !active || speechHostEpoch != creationEpoch || run.closedForCalls ||
                                    !ttsSession.isCurrent(run.token)
                                ) {
                                    throw CancellationException("Speech host is inactive")
                                }
                                stagingTtsRuns.remove(run)
                                ttsRun = run
                                ttsState = "initializing"
                                ttsErrorCode = null
                                ttsMessage = CampText.get(R.string.camp_voice_in_progress)
                            }
                            ttsCall(run) {
                                SdkRuntimeTrace.event(
                                    "TTS",
                                    "initialize.request",
                                    "onMainThread" to (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()),
                                )
                                it.initialize(
                                    object : TtsInit {
                                        override fun onSuccess() =
                                            synchronized(speechLock) {
                                                val current = ownsTts(run) && ttsSession.isCurrent(run.token)
                                                SdkRuntimeTrace.event("TTS", "initialize.onSuccess", "current" to current)
                                                if (current) {
                                                    ttsSession.ready(run.token)
                                                    run.initialized.complete(true)
                                                }
                                                Unit
                                            }

                                        override fun onFailure(errorType: TtsError) =
                                            synchronized(speechLock) {
                                                val current = ownsTts(run) && ttsSession.isCurrent(run.token)
                                                SdkRuntimeTrace.event(
                                                    "TTS",
                                                    "initialize.onFailure",
                                                    "errorType" to errorType.name,
                                                    "current" to current,
                                                )
                                                if (current) ttsOutcome(run, ttsSession.fail(run.token, errorType.name))
                                            }
                                    },
                                )
                            }
                            if (withTimeoutOrNull(4000) { run.initialized.await() } != true) {
                                synchronized(speechLock) { ttsOutcome(run, ttsSession.timeout(run.token, "INITIALIZE_TIMEOUT")) }
                            } else if (ownsTts(run) && ttsSession.isCurrent(run.token)) {
                                val listener =
                                    object : TtsEvents {
                                        override fun onReady() =
                                            synchronized(speechLock) {
                                                val current = ownsTts(run) && ttsSession.isCurrent(run.token)
                                                SdkRuntimeTrace.event("TTS", "onReady", "current" to current)
                                                if (current && !run.started.isCompleted) {
                                                    ttsSession.ready(run.token)
                                                    ttsState = "ready"
                                                }
                                            }

                                        override fun onStart() =
                                            synchronized(speechLock) {
                                                val current = ownsTts(run) && ttsSession.started(run.token)
                                                SdkRuntimeTrace.event("TTS", "onStart", "current" to current)
                                                if (current) {
                                                    ttsState = "started"
                                                    run.started.complete(true)
                                                }
                                                Unit
                                            }

                                        override fun onDone() =
                                            synchronized(speechLock) {
                                                val current = ownsTts(run) && ttsSession.isCurrent(run.token)
                                                SdkRuntimeTrace.event("TTS", "onDone", "current" to current)
                                                if (current) ttsOutcome(run, ttsSession.playbackDone(run.token))
                                            }

                                        override fun onStop() =
                                            synchronized(speechLock) {
                                                val current = ownsTts(run) && ttsSession.isCurrent(run.token)
                                                SdkRuntimeTrace.event("TTS", "onStop", "current" to current)
                                                if (current) ttsOutcome(run, ttsSession.cancel(run.token))
                                            }

                                        override fun onError(errorType: TtsError) =
                                            synchronized(speechLock) {
                                                val current = ownsTts(run) && ttsSession.isCurrent(run.token)
                                                SdkRuntimeTrace.event("TTS", "onError", "errorType" to errorType.name, "current" to current)
                                                if (current) ttsOutcome(run, ttsSession.fail(run.token, errorType.name))
                                            }

                                        override fun onUpdatedRms(rms: Double) = Unit
                                    }
                                ttsCall(run) {
                                    run.listener = listener
                                    it.addEventListener(listener)
                                    it.setLocale(speechLocale)
                                }
                                ttsCall(run) {
                                    run.requested = true
                                    it.speak(text, true)
                                }
                                if (!run.terminal.isCompleted && withTimeoutOrNull(60000) { run.terminal.await() } == null) {
                                    synchronized(speechLock) {
                                        ttsOutcome(
                                            run,
                                            ttsSession.timeout(
                                                run.token,
                                                if (run.started.isCompleted) "COMPLETION_TIMEOUT" else "START_TIMEOUT",
                                            ),
                                        )
                                    }
                                }
                            }
                            run.terminal.await()
                        } catch (cancelled: CancellationException) {
                            attempt?.let { run ->
                                synchronized(speechLock) {
                                    ttsOutcome(
                                        run,
                                        ttsSession.cancel(run.token) ?: SpeechOutcome(SpeechPhase.CANCELLED, "CANCELLED"),
                                    )
                                }
                            }
                            throw cancelled
                        } catch (error: Exception) {
                            SdkRuntimeTrace.event("TTS", "operation.error", "errorClass" to error.javaClass.simpleName)
                            attempt?.let { run ->
                                synchronized(speechLock) {
                                    ttsOutcome(
                                        run,
                                        ttsSession.fail(run.token, error.javaClass.simpleName)
                                            ?: SpeechOutcome(SpeechPhase.FAILED, error.javaClass.simpleName),
                                    )
                                }
                            }
                            attempt?.terminal?.await() ?: SpeechOutcome(SpeechPhase.FAILED, error.javaClass.simpleName).also {
                                unavailableSpeech(true, it.errorCode!!, R.string.camp_tts_failed, true)
                            }
                        } finally {
                            attempt?.let { run ->
                                synchronized(speechLock) {
                                    stagingTtsRuns.remove(run)
                                    pendingTtsCleanup.add(run)
                                    if (ttsRun === run) ttsRun = null
                                }
                                cleanupTts(run)
                            }
                            cleanupAbandonedSpeech()
                            retainSpeechCleanupIfNeeded()
                        }
                    speechResponse(outcome, true)
                }
            }

        suspend fun listen(): JSONObject =
            sttRequests.withLock {
                withContext(Dispatchers.IO) {
                    if (!hasPermission("pleos.car.permission.STT_SERVICE")) {
                        SdkRuntimeTrace.event("STT", "request.rejected", "reason" to "PERMISSION_REQUIRED")
                        return@withContext unavailableSpeech(false, "PERMISSION_REQUIRED", R.string.camp_voice_permission_needed, true)
                    }
                    val blocked = synchronized(speechLock) { sttBlockedReason }
                    if (blocked != null) {
                        SdkRuntimeTrace.event("STT", "request.rejected", "reason" to blocked)
                        return@withContext unavailableSpeech(false, blocked, R.string.camp_stt_unavailable, false)
                    }
                    synchronized(speechLock) { pendingSttCleanup.toList() }.forEach { cleanupStt(it) }
                    if (synchronized(speechLock) { pendingSttCleanup.isNotEmpty() }) {
                        SdkRuntimeTrace.event("STT", "request.rejected", "reason" to "CLEANUP_PENDING")
                        return@withContext unavailableSpeech(false, "CLEANUP_PENDING", R.string.camp_voice_cleanup_pending, false)
                    }
                    var attempt: SttRun? = null
                    val creationEpoch = synchronized(speechLock) { speechHostEpoch }
                    val outcome =
                        try {
                            if (closed || !active) throw CancellationException("Speech host is inactive")
                            retainedClose.retryAfterExplicitRequest()
                            retainedClose.requireClearExcept(this)
                            val run =
                                speechConstruction.create(
                                    admit = {
                                        !closed && active && speechHostEpoch == creationEpoch &&
                                            !speechConstruction.hasAbandoned()
                                    },
                                    factory = { sttFactory.get() },
                                    construct = { engine -> SttRun(engine, sttSession.begin()) },
                                    releaseUnwrapped = { it.release() },
                                    adopt = { stagingSttRuns.add(it) },
                                )
                            attempt = run
                            synchronized(speechLock) {
                                if (closed || !active || speechHostEpoch != creationEpoch || run.closedForCalls ||
                                    !sttSession.isCurrent(run.token)
                                ) {
                                    throw CancellationException("Speech host is inactive")
                                }
                                stagingSttRuns.remove(run)
                                sttRun = run
                                sttState = "initializing"
                                sttErrorCode = null
                                sttMessage = CampText.get(R.string.camp_voice_in_progress)
                            }
                            sttCall(run) {
                                SdkRuntimeTrace.event(
                                    "STT",
                                    "initialize.request",
                                    "sessionEpoch" to run.token,
                                    "inputSource" to "MICROPHONE",
                                    "mode" to "ON_DEVICE",
                                    "onMainThread" to (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()),
                                )
                                it.initialize(
                                    object : SttInit {
                                        override fun onSuccess() =
                                            synchronized(speechLock) {
                                                val current = sttCallback(run, RecognitionCallback.INITIALIZED)
                                                if (current) {
                                                    sttSession.ready(run.token)
                                                    run.initialized.complete(true)
                                                }
                                                Unit
                                            }

                                        override fun onFailure(errorType: SttError) =
                                            synchronized(speechLock) {
                                                val current =
                                                    sttCallback(
                                                        run,
                                                        RecognitionCallback.INITIALIZE_FAILED,
                                                        "errorType" to errorType.name,
                                                    )
                                                if (current) sttOutcome(run, sttSession.fail(run.token, errorType.name))
                                            }
                                    },
                                )
                            }
                            if (withTimeoutOrNull(4000) { run.initialized.await() } != true) {
                                synchronized(speechLock) { sttOutcome(run, sttSession.timeout(run.token, "INITIALIZE_TIMEOUT")) }
                            } else if (ownsStt(run) && sttSession.isCurrent(run.token)) {
                                val listener =
                                    object : SttResults {
                                        override fun onReady() =
                                            synchronized(speechLock) {
                                                val current = sttCallback(run, RecognitionCallback.READY)
                                                if (current) {
                                                    sttSession.ready(run.token)
                                                    sttState = "ready"
                                                }
                                            }

                                        override fun onStartedRecognition() =
                                            synchronized(speechLock) {
                                                val current = sttCallback(run, RecognitionCallback.STARTED)
                                                if (current && sttSession.started(run.token)) sttState = "listening"
                                            }

                                        override fun onEndedRecognition() =
                                            synchronized(speechLock) {
                                                val current = sttCallback(run, RecognitionCallback.ENDED)
                                                if (current && sttSession.processing(run.token)) sttState = "processing"
                                            }

                                        override fun onUpdated(
                                            text: String,
                                            completed: Boolean,
                                        ) = synchronized(speechLock) {
                                            val blank = text.isBlank()
                                            val observation = run.diagnostics.result(text.length, blank, completed, sttDisposition(run))
                                            traceSttCallback(
                                                run,
                                                if (completed) RecognitionCallback.FINAL_RESULT else RecognitionCallback.PARTIAL_RESULT,
                                                observation,
                                                "characterCount" to text.length,
                                                "blank" to blank,
                                                "resultDisposition" to
                                                    when {
                                                        !observation.current -> "IGNORED"
                                                        !completed -> "PARTIAL_PENDING"
                                                        blank -> "BLANK_FINAL_REJECTED"
                                                        else -> "FINAL_CANDIDATE"
                                                    },
                                            )
                                            if (observation.current) sttOutcome(run, sttSession.finalText(run.token, text, completed))
                                        }

                                        override fun onError(errorType: SttError) =
                                            synchronized(speechLock) {
                                                val current = sttCallback(run, RecognitionCallback.ERROR, "errorType" to errorType.name)
                                                if (current) sttOutcome(run, sttSession.fail(run.token, errorType.name))
                                            }

                                        override fun onUpdatedEpdData(
                                            on: Long,
                                            off: Long,
                                        ) = Unit

                                        override fun onUpdatedRms(rms: Double) =
                                            synchronized(speechLock) {
                                                run.diagnostics.rms(rms, sttDisposition(run))
                                            }
                                    }
                                sttCall(run) {
                                    run.listener = listener
                                    it.setLocale(speechLocale)
                                    it.addListener(listener)
                                }
                                sttCall(run) {
                                    run.requested = true
                                    run.diagnostics.requested()
                                    SdkRuntimeTrace.event(
                                        "STT",
                                        "request",
                                        "sessionEpoch" to run.token,
                                        "locale" to speechLocale.toLanguageTag(),
                                        "inputSource" to "MICROPHONE",
                                        "mode" to "ON_DEVICE",
                                    )
                                    it.request()
                                }
                                if (withTimeoutOrNull(20000) { run.terminal.await() } == null) {
                                    synchronized(speechLock) { sttOutcome(run, sttSession.timeout(run.token, "RESULT_TIMEOUT")) }
                                }
                            }
                            run.terminal.await()
                        } catch (cancelled: CancellationException) {
                            attempt?.let { run ->
                                synchronized(speechLock) {
                                    sttOutcome(
                                        run,
                                        sttSession.cancel(run.token) ?: SpeechOutcome(SpeechPhase.CANCELLED, "CANCELLED"),
                                    )
                                }
                            }
                            throw cancelled
                        } catch (error: Exception) {
                            SdkRuntimeTrace.event(
                                "STT",
                                "operation.error",
                                "sessionEpoch" to attempt?.token,
                                "errorClass" to error.javaClass.simpleName,
                            )
                            attempt?.let { run ->
                                synchronized(speechLock) {
                                    sttOutcome(
                                        run,
                                        sttSession.fail(run.token, error.javaClass.simpleName)
                                            ?: SpeechOutcome(SpeechPhase.FAILED, error.javaClass.simpleName),
                                    )
                                }
                            }
                            attempt?.terminal?.await() ?: SpeechOutcome(SpeechPhase.FAILED, error.javaClass.simpleName).also {
                                unavailableSpeech(false, it.errorCode!!, R.string.camp_stt_unavailable, true)
                            }
                        } finally {
                            attempt?.let { run ->
                                synchronized(speechLock) {
                                    stagingSttRuns.remove(run)
                                    pendingSttCleanup.add(run)
                                    if (sttRun === run) sttRun = null
                                }
                                cleanupStt(run)
                            }
                            cleanupAbandonedSpeech()
                            retainSpeechCleanupIfNeeded()
                        }
                    speechResponse(outcome, false)
                }
            }

        private fun cleanupTts(run: TtsRun) {
            val cleaned =
                synchronized(run.sdkLock) {
                    run.closedForCalls = true
                    run.cleanup.clean(run.requested, {
                        run.engine.stop()
                    }, { run.listener?.let { run.engine.removeEventListener(it) } }, { run.engine.release() })
                }
            synchronized(speechLock) {
                if (!cleaned) {
                    pendingTtsCleanup.add(run)
                    ttsRetryable = false
                } else {
                    pendingTtsCleanup.remove(run)
                    ttsRetryable = ttsBlockedReason == null && pendingTtsCleanup.isEmpty()
                }
            }
            if (!cleaned) {
                SdkRuntimeTrace.event("TTS", "cleanup.error")
                retainSpeechCleanupIfNeeded()
            }
            finishSpeechCleanupIfIdle()
        }

        private fun cleanupStt(run: SttRun) {
            val cleaned =
                synchronized(run.sdkLock) {
                    run.closedForCalls = true
                    run.cleanup.clean(
                        run.requested,
                        { run.engine.stop() },
                        { run.listener?.let { run.engine.removeListener(it) } },
                        { run.engine.release() },
                    )
                }
            val report =
                synchronized(speechLock) {
                    if (!cleaned) {
                        pendingSttCleanup.add(run)
                        sttRetryable = false
                    } else {
                        pendingSttCleanup.remove(run)
                        sttRetryable = sttBlockedReason == null && pendingSttCleanup.isEmpty()
                    }
                    val first = !run.cleanupReported || (cleaned && !run.cleanupSuccessReported)
                    run.cleanupReported = true
                    if (cleaned) run.cleanupSuccessReported = true
                    first
                }
            if (report) SdkRuntimeTrace.event("STT", "cleanup.summary", "cleaned" to cleaned, *run.diagnostics.fields())
            if (!cleaned) retainSpeechCleanupIfNeeded()
            finishSpeechCleanupIfIdle()
        }

        private fun cleanupAbandonedSpeech() {
            speechConstruction.drainAbandoned { error ->
                SdkRuntimeTrace.event("Speech", "unwrapped.release.error", "retained" to true, "errorClass" to error.javaClass.simpleName)
            }
        }

        private fun retainSpeechCleanupIfNeeded() {
            if (closed || speechConstruction.hasAbandoned()) retainedClose.retire(this) { cleanupRetainedResources() }
        }

        private fun cleanupRetainedResources(): Boolean {
            if (closed) return cleanupClosedResources()
            cleanupAbandonedSpeech()
            // A close racing this pass either changes the retained entry revision or
            // creates a new entry; never use the speech-only path for a closed host.
            return if (closed) cleanupClosedResources() else !speechConstruction.hasOutstanding()
        }

        private fun finishSpeechCleanupIfIdle() {
            val finished =
                synchronized(speechLock) {
                    closed && ttsRun == null && sttRun == null && stagingTtsRuns.isEmpty() &&
                        stagingSttRuns.isEmpty() &&
                        pendingTtsCleanup.isEmpty() &&
                        pendingSttCleanup.isEmpty() &&
                        !speechConstruction.hasOutstanding()
                }
            if (finished) speechCleanupJob.cancel()
        }

        private fun stopTts() {
            val runs =
                synchronized(speechLock) {
                    ttsRun?.let { run -> ttsOutcome(run, ttsSession.cancel(run.token)) }
                    (pendingTtsCleanup.toList() + stagingTtsRuns.toList() + listOfNotNull(ttsRun)).distinct().also {
                        pendingTtsCleanup.addAll(it)
                        stagingTtsRuns.clear()
                        ttsRun = null
                        if (it.isNotEmpty()) ttsRetryable = false
                    }
                }
            runs.forEach { run -> speechCleanupScope.launch { cleanupTts(run) } }
        }

        private fun stopStt() {
            val runs =
                synchronized(speechLock) {
                    sttRun?.let { run -> sttOutcome(run, sttSession.cancel(run.token)) }
                    (pendingSttCleanup.toList() + stagingSttRuns.toList() + listOfNotNull(sttRun)).distinct().also {
                        pendingSttCleanup.addAll(it)
                        stagingSttRuns.clear()
                        sttRun = null
                        if (it.isNotEmpty()) sttRetryable = false
                    }
                }
            runs.forEach { run -> speechCleanupScope.launch { cleanupStt(run) } }
        }

        private fun cleanup(id: String) {
            if (!hasLocationPermission()) {
                synchronized(locationLock) { pendingCleanup.add(id) }
                return
            }
            try {
                fused.removeProximityAlert(id)
                synchronized(locationLock) { pendingCleanup.remove(id) }
            } catch (error: Exception) {
                synchronized(locationLock) { pendingCleanup.add(id) }
                arrivalState = "cleanup_pending"
                arrivalMessage = "이전 도착 알림 해제를 확인해야 합니다."
            }
        }

        private fun unregisterLocationListeners() {
            when (
                pendingLocationCleanup.prepare(hasLocationPermission()) { listener ->
                    try {
                        traceFused("unregister.request")
                        fused.unregisterFusedLocationCallback(listener)
                        traceFused("unregister.returned")
                    } catch (
                        error: Exception,
                    ) {
                        traceFused("unregister.error", "errorClass" to error.javaClass.simpleName)
                        throw error
                    }
                }
            ) {
                LocationRegistrationReadiness.CLEANUP_REQUIRED -> {
                    arrivalState = "cleanup_pending"
                    arrivalMessage =
                        "이전 위치 구독 해제를 확인하지 못했습니다. 다시 시도하거나 앱을 다시 열어 주세요."
                }

                LocationRegistrationReadiness.PERMISSION_REQUIRED -> {
                    arrivalState = "unavailable"
                    arrivalMessage = "위치 권한을 허용한 뒤 다시 시도해 주세요."
                }

                LocationRegistrationReadiness.READY -> {
                    Unit
                }
            }
        }

        /** Called only on the private native lane; callbacks only enqueue a cleanup signal. */
        private fun releaseLocationIfIdle() {
            if (!synchronized(locationLock) { closed && locationReleaseRequired }) return
            val ready = synchronized(locationLock) { !pendingRegistration && pendingCleanup.isEmpty() } && pendingLocationCleanup.isEmpty()
            if (!ready) {
                arrivalState = "cleanup_pending"
                arrivalMessage = "대기 중인 위치 구독과 도착 알림 해제를 확인해야 합니다."
                return
            }
            try {
                traceFused("release.request")
                fused.release()
                synchronized(locationLock) {
                    initialized = false
                    locationReleaseRequired = false
                }
                traceFused("release.returned")
            } catch (error: Exception) {
                arrivalState = "cleanup_pending"
                arrivalMessage = "위치 서비스 종료를 확인해야 합니다."
                traceFused("release.error", "retained" to true, "errorClass" to error.javaClass.simpleName)
            }
        }

        private fun cleanupLocationNative() {
            unregisterLocationListeners()
            synchronized(locationLock) { pendingCleanup.toList() }.forEach(::cleanup)
            releaseLocationIfIdle()
        }

        private fun requestLocationCleanup() {
            if (closed) {
                retainedClose.retire(this) { cleanupRetainedResources() }
            } else {
                locationIo.enqueue("location.cleanup") { cleanupLocationNative() }
            }
        }

        /** Caller holds only callback metadata; no native operation or lane wait. */
        private fun detachLocationLocked() {
            locationActive = false
            epoch = locationIo.advance()
            watchEpoch.invalidate()
            latest = null
            locationListener?.let(pendingLocationCleanup::add)
            locationListener = null
            alertId?.let(pendingCleanup::add)
            alertId = null
        }

        fun setLanguage(tag: String) {
            val next = canonicalSpeechLocale(tag)
            if (next.toLanguageTag() == speechLocale.toLanguageTag()) return
            runCatching { stopTts() }
            runCatching { stopStt() }
            speechLocale = next
            synchronized(speechLock) {
                ttsBlockedReason = null
                sttBlockedReason = null
                ttsRetryable = pendingTtsCleanup.isEmpty()
                sttRetryable = pendingSttCleanup.isEmpty()
            }
        }

        fun assistStatus() =
            synchronized(speechLock) {
                JSONObject()
                    .put("arrivalStatus", arrivalState)
                    .put("arrivalMessage", arrivalMessage)
                    .put("ttsStatus", ttsState)
                    .put("sttStatus", sttState)
                    .put(
                        "ttsMessage",
                        ttsMessage + if (pendingTtsCleanup.isNotEmpty()) " " + CampText.get(R.string.camp_voice_cleanup_pending) else "",
                    ).put(
                        "sttMessage",
                        sttMessage + if (pendingSttCleanup.isNotEmpty()) " " + CampText.get(R.string.camp_voice_cleanup_pending) else "",
                    ).put("ttsErrorCode", ttsErrorCode ?: JSONObject.NULL)
                    .put("sttErrorCode", sttErrorCode ?: JSONObject.NULL)
                    .put(
                        "ttsRetryable",
                        ttsRetryable && pendingTtsCleanup.isEmpty(),
                    ).put("sttRetryable", sttRetryable && pendingSttCleanup.isEmpty())
                    .put("ttsCleanupPending", pendingTtsCleanup.isNotEmpty())
                    .put("sttCleanupPending", pendingSttCleanup.isNotEmpty())
            }

        fun stop() {
            synchronized(locationLock) {
                synchronized(speechLock) {
                    active = false
                    speechHostEpoch++
                }
                detachLocationLocked()
            }
            // Cancel/enqueue existing speech engines before any possibly blocked Fused IPC.
            runCatching { stopTts() }
            runCatching { stopStt() }
            requestLocationCleanup()
        }

        fun close() {
            synchronized(locationLock) {
                synchronized(speechLock) {
                    closed = true
                    active = false
                    speechHostEpoch++
                }
                detachLocationLocked()
            }
            retainedClose.retire(this) { cleanupRetainedResources() }
            runCatching { stopTts() }
            runCatching { stopStt() }
        }

        private fun cleanupClosedResources(): Boolean {
            runCatching { stopTts() }
            runCatching { stopStt() }
            cleanupAbandonedSpeech()
            locationIo.native { cleanupLocationNative() }
            synchronized(speechLock) { pendingTtsCleanup.toList() }.forEach(::cleanupTts)
            synchronized(speechLock) { pendingSttCleanup.toList() }.forEach(::cleanupStt)
            val locationFinished =
                synchronized(locationLock) {
                    !locationReleaseRequired && !pendingRegistration && pendingCleanup.isEmpty() && pendingLocationCleanup.isEmpty()
                }
            val speechFinished =
                synchronized(speechLock) {
                    ttsRun == null && sttRun == null && stagingTtsRuns.isEmpty() && stagingSttRuns.isEmpty() &&
                        pendingTtsCleanup.isEmpty() &&
                        pendingSttCleanup.isEmpty() &&
                        !speechConstruction.hasOutstanding()
                }
            finishSpeechCleanupIfIdle()
            val finished = locationFinished && speechFinished
            SdkRuntimeTrace.event(
                "Assist",
                "cleanup.retained.result",
                "complete" to finished,
                "onMainThread" to (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()),
            )
            return finished
        }
    }
