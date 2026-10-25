package camp.room.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Android emulator assist services. No invented coordinates, audio results, or arrival events. */
class AndroidAssistAdapter(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val locations = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    @Volatile private var active = false
    @Volatile private var closed = false
    @Volatile private var generation = 0L
    @Volatile private var latest: Location? = null
    @Volatile private var locationSubscribed = false
    @Volatile private var currentUtteranceId: String? = null
    @Volatile private var arrivalStatus = "idle"
    @Volatile private var arrivalMessage = "도착 알림을 아직 등록하지 않았어요."
    @Volatile private var ttsStatus = "idle"
    @Volatile private var sttStatus = "idle"
    private var watchedSite: Campsite? = null
    private var wasInside: Boolean? = null
    private val locationWaiters = linkedMapOf<String, CancellableContinuation<JSONObject>>()
    private var engine: TextToSpeech? = null
    private var engineReady = false
    private val initWaiters = linkedMapOf<String, CancellableContinuation<Boolean>>()
    private var pendingSpeech: Pair<String, CancellableContinuation<JSONObject>>? = null
    private var recognizer: SpeechRecognizer? = null
    private var pendingRecognition: Pair<String, CancellableContinuation<JSONObject>>? = null

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission() =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun unavailable(message: String) =
        JSONObject().put("status", "unavailable").put("message", message)

    private fun <T> resolve(continuation: CancellableContinuation<T>, value: T) {
        if (continuation.isActive) continuation.resumeWith(Result.success(value))
    }

    private fun availableLocation(value: Location): JSONObject =
        JSONObject()
            .put("status", "available")
            .put("latitude", value.latitude)
            .put("longitude", value.longitude)
            .put("timestampMillis", value.time)
            .put("accuracyMeters", value.accuracy.toDouble())
            .put("source", "Android ${value.provider ?: "location"} · 에뮬레이터/기기 위치")
            .put("message", "기기에서 제공한 위치를 확인했어요.")

    private val listener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!active || closed || !valid(location)) return
                if (latest?.let { location.time < it.time } == true) return
                latest = Location(location)
                locationWaiters.values.toList().forEach { resolve(it, availableLocation(location)) }
                locationWaiters.clear()
                checkArrival(location)
            }

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Legacy Android callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

    private fun valid(location: Location) =
        location.latitude.isFinite() &&
            location.longitude.isFinite() &&
            location.latitude in -90.0..90.0 &&
            location.longitude in -180.0..180.0 &&
            location.time > 0

    fun start() {
        if (closed) return
        active = true
        val expected = generation
        main.post {
            if (!active || closed || generation != expected || !hasLocationPermission()) return@post
            val providers =
                locations.getProviders(true).filter {
                    it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER
                }
            providers.forEach { provider ->
                try {
                    val cached = locations.getLastKnownLocation(provider)
                    if (
                        cached != null &&
                            valid(cached) &&
                            (latest == null || cached.time > latest!!.time)
                    )
                        latest = Location(cached)
                    locations.requestLocationUpdates(
                        provider,
                        1_000L,
                        0f,
                        listener,
                        Looper.getMainLooper(),
                    )
                    locationSubscribed = true
                } catch (_: SecurityException) {
                    arrivalMessage = "위치 권한을 다시 확인해 주세요."
                } catch (_: IllegalArgumentException) {
                    arrivalMessage = "사용 가능한 위치 제공자가 없어요."
                }
            }
        }
    }

    suspend fun location(): JSONObject {
        if (closed) return unavailable("위치 서비스가 종료되었어요.")
        if (!hasLocationPermission()) return unavailable("위치 권한을 허용해 주세요.")
        val cached = latest
        if (
            cached != null &&
                valid(cached) &&
                System.currentTimeMillis() - cached.time in 0..120_000L
        )
            return availableLocation(cached)
        start()
        val result =
            withTimeoutOrNull(8_000L) {
                suspendCancellableCoroutine<JSONObject> { continuation ->
                    val id = UUID.randomUUID().toString()
                    continuation.invokeOnCancellation { main.post { locationWaiters.remove(id) } }
                    main.post {
                        if (closed || !active)
                            resolve(continuation, unavailable("현재 위치를 요청할 수 없는 상태예요."))
                        else {
                            val current = latest
                            if (
                                current != null &&
                                    valid(current) &&
                                    System.currentTimeMillis() - current.time in 0..120_000L
                            )
                                resolve(continuation, availableLocation(current))
                            else locationWaiters[id] = continuation
                        }
                    }
                }
            }
        return result ?: unavailable("아직 기기 위치를 받지 못했어요. GPS 설정을 확인하고 다시 시도해 주세요.")
    }

    suspend fun arrivalWatch(site: Campsite): JSONObject {
        if (closed) return unavailable("도착 알림 서비스가 종료되었어요.")
        if (!hasLocationPermission()) return unavailable("도착 알림에는 위치 권한이 필요해요.")
        val latitude = site.latitude
        val longitude = site.longitude
        if (
            latitude == null ||
                longitude == null ||
                latitude !in -90.0..90.0 ||
                longitude !in -180.0..180.0
        )
            return unavailable("이 캠핑장의 좌표를 확인할 수 없어요.")
        start()
        return suspendCancellableCoroutine { continuation ->
            main.post {
                if (
                    closed ||
                        !active ||
                        !locationSubscribed ||
                        locations.getProviders(true).none {
                            it == LocationManager.GPS_PROVIDER ||
                                it == LocationManager.NETWORK_PROVIDER
                        }
                ) {
                    resolve(continuation, unavailable("위치 제공자를 사용할 수 없어 알림을 등록하지 못했어요."))
                } else {
                    watchedSite = site
                    wasInside = null
                    arrivalStatus = "registered"
                    arrivalMessage = "${site.name} 500m 도착 알림을 등록했어요. 실제 위치의 진입·이탈이 관측되면 표시합니다."
                    resolve(
                        continuation,
                        JSONObject()
                            .put("status", "registered")
                            .put("alertId", "android-${site.id}")
                            .put("message", arrivalMessage),
                    )
                }
            }
        }
    }

    private fun checkArrival(location: Location) {
        val site = watchedSite ?: return
        val latitude = site.latitude ?: return
        val longitude = site.longitude ?: return
        val inside =
            campDistanceKm(location.latitude, location.longitude, latitude, longitude) <= .5
        val previous = wasInside
        wasInside = inside
        when {
            previous == null -> {
                arrivalStatus = if (inside) "inside" else "registered"
                arrivalMessage =
                    if (inside) "현재 위치가 ${site.name} 500m 범위 안에 있어요. 등록 이전의 진입 사건은 추정하지 않습니다."
                    else "${site.name} 도착 알림 대기 중 · 현재 500m 범위 밖이에요."
            }
            !previous && inside -> {
                arrivalStatus = "entered"
                arrivalMessage = "${site.name} 500m 범위에 진입한 위치 변화가 확인되었어요."
            }
            previous && !inside -> {
                arrivalStatus = "exited"
                arrivalMessage = "${site.name} 500m 범위에서 이탈한 위치 변화가 확인되었어요."
            }
        }
    }

    private suspend fun prepareTts(): Boolean =
        withTimeoutOrNull(6_000L) {
            suspendCancellableCoroutine { continuation ->
                val id = UUID.randomUUID().toString()
                continuation.invokeOnCancellation { main.post { initWaiters.remove(id) } }
                main.post {
                    if (closed || !active) {
                        resolve(continuation, false)
                        return@post
                    }
                    if (engineReady) {
                        resolve(continuation, true)
                        return@post
                    }
                    initWaiters[id] = continuation
                    if (engine != null) return@post
                    val expected = generation
                    engine =
                        TextToSpeech(context) { status ->
                            main.post initialized@{
                                if (closed || !active || generation != expected) return@initialized
                                val current = engine
                                val supported =
                                    status == TextToSpeech.SUCCESS &&
                                        current != null &&
                                        current.isLanguageAvailable(Locale.KOREAN) >=
                                            TextToSpeech.LANG_AVAILABLE &&
                                        current.setLanguage(Locale.KOREAN) >=
                                            TextToSpeech.LANG_AVAILABLE
                                engineReady = supported
                                ttsStatus = if (supported) "ready" else "unavailable"
                                if (supported)
                                    current?.setOnUtteranceProgressListener(
                                        object : UtteranceProgressListener() {
                                            override fun onStart(utteranceId: String?) {
                                                main.post {
                                                    if (
                                                        generation == expected &&
                                                            active &&
                                                            !closed &&
                                                            currentUtteranceId == utteranceId
                                                    ) {
                                                        ttsStatus = "started"
                                                        pendingSpeech?.second?.let {
                                                            resolve(
                                                                it,
                                                                JSONObject()
                                                                    .put("status", "started")
                                                                    .put(
                                                                        "message",
                                                                        "기기 TTS의 실제 음성 재생 시작을 확인했어요.",
                                                                    ),
                                                            )
                                                        }
                                                        pendingSpeech = null
                                                    }
                                                }
                                            }

                                            override fun onDone(utteranceId: String?) {
                                                if (
                                                    generation == expected &&
                                                        active &&
                                                        !closed &&
                                                        currentUtteranceId == utteranceId
                                                )
                                                    ttsStatus = "completed"
                                            }

                                            @Deprecated("Legacy Android callback")
                                            override fun onError(utteranceId: String?) {
                                                onError(utteranceId, TextToSpeech.ERROR)
                                            }

                                            override fun onError(
                                                utteranceId: String?,
                                                errorCode: Int,
                                            ) {
                                                main.post {
                                                    if (
                                                        generation == expected &&
                                                            !closed &&
                                                            currentUtteranceId == utteranceId
                                                    ) {
                                                        ttsStatus = "unavailable"
                                                        pendingSpeech?.second?.let {
                                                            resolve(
                                                                it,
                                                                unavailable("기기 TTS 음성을 재생하지 못했어요."),
                                                            )
                                                        }
                                                        pendingSpeech = null
                                                    }
                                                }
                                            }
                                        }
                                    )
                                if (!supported) {
                                    current?.shutdown()
                                    engine = null
                                }
                                initWaiters.values.toList().forEach { resolve(it, supported) }
                                initWaiters.clear()
                            }
                        }
                }
            }
        } ?: false

    suspend fun speak(text: String): JSONObject {
        if (text.isBlank()) return unavailable("읽을 여행 안내가 없어요.")
        if (!prepareTts()) return unavailable("한국어 TTS 엔진을 사용할 수 없어요. 기기 음성 엔진과 언어 설정을 확인해 주세요.")
        return withTimeoutOrNull(8_000L) {
            suspendCancellableCoroutine<JSONObject> { continuation ->
                val id = UUID.randomUUID().toString()
                continuation.invokeOnCancellation {
                    main.post {
                        if (pendingSpeech?.first == id) {
                            engine?.stop()
                            pendingSpeech = null
                            ttsStatus = "stopped"
                        }
                    }
                }
                main.post {
                    if (closed || !active || !engineReady) {
                        resolve(continuation, unavailable("음성 안내를 시작할 수 없는 상태예요."))
                        return@post
                    }
                    pendingSpeech?.second?.let { resolve(it, unavailable("다른 음성 안내 요청으로 교체되었어요.")) }
                    pendingSpeech = id to continuation
                    currentUtteranceId = id
                    val result = engine?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
                    if (result != TextToSpeech.SUCCESS) {
                        pendingSpeech = null
                        ttsStatus = "unavailable"
                        resolve(continuation, unavailable("TTS 엔진이 재생 요청을 받지 못했어요."))
                    }
                }
            }
        } ?: unavailable("TTS의 재생 시작을 확인하지 못했어요.")
    }

    suspend fun listen(): JSONObject {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO))
            return unavailable("마이크 권한을 허용해 주세요.").put("text", "")
        return withTimeoutOrNull(18_000L) {
            suspendCancellableCoroutine<JSONObject> { continuation ->
                val id = UUID.randomUUID().toString()
                continuation.invokeOnCancellation {
                    main.post {
                        if (pendingRecognition?.first == id) {
                            recognizer?.cancel()
                            recognizer?.destroy()
                            recognizer = null
                            pendingRecognition = null
                            sttStatus = "stopped"
                        }
                    }
                }
                main.post {
                    if (closed || !active || !SpeechRecognizer.isRecognitionAvailable(context)) {
                        sttStatus = "unavailable"
                        resolve(
                            continuation,
                            unavailable("기기에 사용 가능한 음성 인식 서비스가 없어요. 텍스트를 직접 입력할 수 있습니다.")
                                .put("text", ""),
                        )
                        return@post
                    }
                    if (pendingRecognition != null) {
                        resolve(continuation, unavailable("이미 음성을 듣고 있어요.").put("text", ""))
                        return@post
                    }
                    try {
                        recognizer =
                            if (
                                Build.VERSION.SDK_INT >= 31 &&
                                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                            )
                                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                            else SpeechRecognizer.createSpeechRecognizer(context)
                        pendingRecognition = id to continuation
                        sttStatus = "listening"
                        fun complete(result: JSONObject) {
                            if (pendingRecognition?.first != id) return
                            pendingRecognition = null
                            recognizer?.destroy()
                            recognizer = null
                            sttStatus = result.optString("status", "unavailable")
                            resolve(continuation, result)
                        }
                        recognizer?.setRecognitionListener(
                            object : RecognitionListener {
                                override fun onReadyForSpeech(params: Bundle?) = Unit

                                override fun onBeginningOfSpeech() = Unit

                                override fun onRmsChanged(rmsdB: Float) = Unit

                                override fun onBufferReceived(buffer: ByteArray?) = Unit

                                override fun onEndOfSpeech() = Unit

                                override fun onError(error: Int) {
                                    complete(
                                        unavailable(
                                                "음성 인식 결과를 받지 못했어요. 텍스트를 직접 입력할 수 있습니다. (기기 상태 $error)"
                                            )
                                            .put("text", "")
                                    )
                                }

                                override fun onResults(results: Bundle?) {
                                    val transcript =
                                        results
                                            ?.getStringArrayList(
                                                SpeechRecognizer.RESULTS_RECOGNITION
                                            )
                                            ?.firstOrNull { it.isNotBlank() }
                                    complete(
                                        if (transcript != null)
                                            JSONObject()
                                                .put("status", "recognized")
                                                .put("text", transcript)
                                                .put("message", "실제 기기 음성 인식 결과를 받았어요.")
                                        else
                                            unavailable("인식된 문장이 없어요. 텍스트를 직접 입력할 수 있습니다.")
                                                .put("text", "")
                                    )
                                }

                                override fun onPartialResults(partialResults: Bundle?) = Unit

                                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                            }
                        )
                        recognizer?.startListening(
                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(
                                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                )
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        )
                    } catch (_: Exception) {
                        recognizer?.destroy()
                        recognizer = null
                        pendingRecognition = null
                        sttStatus = "unavailable"
                        resolve(
                            continuation,
                            unavailable("기기 음성 인식을 시작하지 못했어요. 텍스트를 직접 입력할 수 있습니다.").put("text", ""),
                        )
                    }
                }
            }
        } ?: unavailable("음성 인식 시간이 지났어요. 텍스트를 직접 입력할 수 있습니다.").put("text", "")
    }

    fun assistStatus(): JSONObject =
        JSONObject()
            .put("arrivalStatus", arrivalStatus)
            .put("arrivalMessage", arrivalMessage)
            .put("ttsStatus", ttsStatus)
            .put("sttStatus", sttStatus)

    fun stop() {
        active = false
        locationSubscribed = false
        generation++
        main.post {
            try {
                locations.removeUpdates(listener)
            } catch (_: Exception) {
                arrivalMessage = "위치 구독 해제를 확인하지 못했어요."
            }
            locationWaiters.values.toList().forEach {
                resolve(it, unavailable("앱이 백그라운드로 이동해 위치 요청을 중지했어요."))
            }
            locationWaiters.clear()
            initWaiters.values.toList().forEach { resolve(it, false) }
            initWaiters.clear()
            pendingSpeech?.second?.let { resolve(it, unavailable("앱이 백그라운드로 이동해 음성 안내를 중지했어요.")) }
            pendingSpeech = null
            currentUtteranceId = null
            engine?.stop()
            engine?.shutdown()
            engine = null
            engineReady = false
            pendingRecognition?.second?.let {
                resolve(it, unavailable("앱이 백그라운드로 이동해 음성 인식을 중지했어요.").put("text", ""))
            }
            pendingRecognition = null
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
            ttsStatus = "stopped"
            sttStatus = "stopped"
        }
    }

    fun close() {
        closed = true
        stop()
    }
}
