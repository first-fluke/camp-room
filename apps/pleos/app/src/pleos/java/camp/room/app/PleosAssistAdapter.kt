package camp.room.app

import javax.inject.Inject
import javax.inject.Provider
import dagger.hilt.android.scopes.ViewModelScoped
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit
import ai.pleos.playground.tts.listener.EventListener as TtsEvents
import ai.pleos.playground.stt.SpeechToText as PleosStt
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.stt.listener.ResultListener as SttResults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume

/** All three remaining SDK modules are local, ON_DEVICE voice uses no embedded server secret. */
@ViewModelScoped
class PleosAssistAdapter @Inject constructor(
    private val fused:FusedLocation,
    private val ttsFactory:Provider<PleosTts>,
    private val sttFactory:Provider<PleosStt>,
) {
    private var speechLocale=Locale.KOREAN
    private var initialized = false
    @Volatile private var active = false
    private var locationActive = false
    @Volatile private var closed = false
    private var epoch = 0
    private var locationListener: FusedLocationListener? = null
    private var latest: LocationData? = null
    private var watchedSite: Campsite? = null
    private var alertId: String? = null
    private var watchToken = 0
    private var pendingRegistration = false
    private val pendingCleanup = mutableSetOf<String>()
    private var liveLocationSequence = 0
    private var tts: PleosTts? = null
    private var ttsListener: TtsEvents? = null
    private var ttsGeneration = 0
    private var ttsPlaying = false
    private var ttsStarted: ((Boolean) -> Unit)? = null
    private var stt: PleosStt? = null
    private var sttListener: SttResults? = null
    private var sttGeneration = 0
    private var sttCompleted: ((String?) -> Unit)? = null
    @Volatile private var arrivalState = "idle"
    @Volatile private var arrivalMessage = "도착 알림을 아직 설정하지 않았습니다."
    @Volatile private var ttsState = "idle"
    @Volatile private var sttState = "idle"
    private fun result(status: String, message: String) = JSONObject().put("status", status).put("message", message)
    private fun valid(location: LocationData?) = location != null && location.latitude.isFinite() && location.longitude.isFinite() &&
        location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0
    private fun accept(location: LocationData?) {
        if (location == null) { latest = null; return }
        if (valid(location)) latest = location
    }
    @Synchronized fun start() {
        if (closed) return
        active = true
        if (locationActive) return
        if (!fused.isSupported()) { arrivalMessage = "이 차량의 FusedLocation 지원 상태를 확인해 주세요."; return }
        if (!initialized) { fused.initialize(); initialized = true }
        pendingCleanup.toList().forEach { cleanup(it) }
        locationActive = true
        val registration = ++epoch
        val listener = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) { synchronized(this@PleosAssistAdapter) { if(active && locationActive && !closed && epoch == registration) { liveLocationSequence++; accept(location) } } }
            override fun onError(exception: Exception) { synchronized(this@PleosAssistAdapter) { if(active && locationActive && !closed && epoch == registration) { latest = null; arrivalMessage = "차량 위치 권한과 연결을 확인해 주세요." } } }
        }
        locationListener = listener
        try { fused.registerFusedLocationCallback(listener) }
        catch(error: Exception) { locationActive = false; locationListener = null; arrivalMessage = "위치 권한과 연결을 확인해 주세요."; throw error }
        watchedSite?.let { registerWatch(it) {} }
    }
    suspend fun location(): JSONObject {
        try { start() } catch(error: Exception) { return result("unavailable", "Pleos 위치 권한과 연결을 확인해 주세요.") }
        if (!locationActive) return result("unavailable", "이 차량에서 위치 제공 여부를 확인해 주세요.")
        val registration = epoch
        val liveSequence = liveLocationSequence
        val sample = withTimeoutOrNull(3000) { suspendCancellableCoroutine<LocationData?> { continuation ->
            val listener = object : FusedLocationListener {
                override fun onChanged(location: LocationData?) { synchronized(this@PleosAssistAdapter) {
                    if(active && locationActive && !closed && epoch == registration) { if(liveLocationSequence==liveSequence)accept(location); if(continuation.isActive) continuation.resume(latest) }
                } }
                override fun onError(exception: Exception) { if(continuation.isActive) continuation.resume(null) }
            }
            try { fused.getLastKnownLocation(listener) } catch(error: Exception) { if(continuation.isActive) continuation.resume(null) }
        } }
        return if(valid(sample)) result("available", "차량 SDK 최근 위치입니다. 측정 시각을 확인해 주세요.")
            .put("latitude", sample!!.latitude).put("longitude", sample.longitude).put("timestampMillis", JSONObject.NULL)
            .put("sdkTimestampMillis", sample.timestampMillis).put("receivedAt",java.time.Instant.now().toString())
            .put("confidence", if(sample.confidence.isFinite())sample.confidence.toDouble() else JSONObject.NULL).put("source", "Pleos FusedLocation 1.1.3").put("lastKnown",true)
        else result("unavailable", "위치 관측값을 받지 못했습니다. 권한과 위치 서비스를 확인해 주세요.")
    }
    @Synchronized private fun registerWatch(site: Campsite, complete: (JSONObject) -> Unit) {
        val lat=site.latitude;val lon=site.longitude
        if(!active || !locationActive || closed || lat==null || lon==null || !lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            complete(result("unavailable","현재 위치 연결과 캠핑장 좌표를 확인해 주세요."));return
        }
        if(pendingRegistration || pendingCleanup.isNotEmpty()){complete(result("unavailable","이전 도착 알림 등록/해제 응답을 기다리고 있습니다."));return}
        alertId?.let { cleanup(it) }; alertId=null
        if(pendingCleanup.isNotEmpty()){complete(result("unavailable","기존 도착 알림 해제를 먼저 확인해야 합니다."));return}
        val token=++watchToken
        pendingRegistration=true
        var waitingForId=true
        val listener=object: ProximityAlertListener {
            override fun onRegistered(id:String) { synchronized(this@PleosAssistAdapter) {
                if(waitingForId){waitingForId=false;pendingRegistration=false}
                if(active && !closed && watchToken==token) { alertId=id;arrivalState="registered";arrivalMessage="캠핑장 반경500m 도착/이탈 알림이 등록되었습니다. 진입 감지와는 다릅니다.";complete(result("registered",arrivalMessage).put("alertId",id)) }
                else { cleanup(id);releaseIfIdle() }
            } }
            override fun onTransition(event:ProximityAlertEvent) { synchronized(this@PleosAssistAdapter) {
                if(active && !closed && watchToken==token && event.alertId==alertId) {
                    arrivalState=if(event.transition==ProximityTransition.ENTER)"entered" else "exited"
                    arrivalMessage=if(event.transition==ProximityTransition.ENTER)"캠핑장 주변 진입 이벤트를 확인했습니다. 차량은 자동으로 제어하지 않습니다." else "캠핑장 주변 이탈 이벤트를 확인했습니다."
                }
            } }
            override fun onError(exception:Exception) { synchronized(this@PleosAssistAdapter) {
                if(waitingForId){waitingForId=false;pendingRegistration=false}
                if(watchToken==token && !closed) { arrivalState="unavailable";arrivalMessage="도착 알림 권한/연결을 확인해 주세요.";complete(result("unavailable",arrivalMessage)) }
                releaseIfIdle()
            } }
        }
        try { fused.addProximityAlert(ProximityAlertRequest(lat,lon,500f,setOf(ProximityTransition.ENTER,ProximityTransition.EXIT)),listener) }
        catch(error:Exception) { if(waitingForId){waitingForId=false;pendingRegistration=false};arrivalState="unavailable";arrivalMessage="도착 알림을 등록하지 못했습니다.";complete(result("unavailable",arrivalMessage));releaseIfIdle() }
    }
    suspend fun arrivalWatch(site:Campsite):JSONObject {
        try { start() } catch(error:Exception) { return result("unavailable","Pleos 위치 연결/권한을 확인해 주세요.") }
        watchedSite=site
        val registration=withTimeoutOrNull(3500) { suspendCancellableCoroutine<JSONObject> { continuation ->
            registerWatch(site) { if(continuation.isActive) continuation.resume(it) }
        } }
        if(registration==null) { watchToken++;alertId?.let{cleanup(it)};alertId=null }
        return registration ?: result("unavailable","도착 알림 등록을 확인하지 못했습니다.")
    }
    private suspend fun ttsEngine():PleosTts? {
        tts?.let { return it }
        if(closed)return null
        val engine=ttsFactory.get()
        val generation=++ttsGeneration
        tts=engine
        val initialized=withTimeoutOrNull(4000) { suspendCancellableCoroutine<Boolean> { continuation ->
            engine.initialize(object:TtsInit {
                override fun onSuccess(){if(continuation.isActive)continuation.resume(true)}
                override fun onFailure(errorType:TtsError){if(continuation.isActive)continuation.resume(false)}
            })
        } } == true
        if(!initialized||closed||!active||ttsGeneration!=generation||tts!==engine){if(tts===engine)runCatching{stopTts()};return null}
        val listener=object:TtsEvents {
            private fun current()=!closed&&ttsGeneration==generation
            override fun onReady(){if(current())ttsState="ready"}
            override fun onStart(){if(current()){ttsState="started";ttsStarted?.invoke(true)}}
            override fun onDone(){if(current()){ttsState="done";ttsPlaying=false}}
            override fun onStop(){if(current()){ttsState="stopped";ttsPlaying=false}}
            override fun onError(errorType:TtsError){if(current()){ttsState="unavailable";ttsPlaying=false;ttsStarted?.invoke(false)}}
            override fun onUpdatedRms(rms:Double)=Unit
        }
        engine.addEventListener(listener);engine.setLocale(speechLocale)
        tts=engine;ttsListener=listener;return engine
    }
    suspend fun speak(text:String):JSONObject {
        if(text.isBlank())return result("unavailable","읽어드릴 여행 정보가 없습니다.")
        if(ttsPlaying)return result("unavailable","이전 안내가 재생 중입니다. 안내가 끝난 뒤 다시 선택해 주세요.")
        return try {
            val engine=ttsEngine()?:return result("unavailable","차량 음성 합성 서비스와 한국어 지원을 확인해 주세요.")
            ttsPlaying=true
            val started=withTimeoutOrNull(5000) { suspendCancellableCoroutine<Boolean> { continuation ->
                ttsStarted={if(continuation.isActive)continuation.resume(it)}
                engine.speak(text,true)
            } } == true
            ttsStarted=null
            if(!started)stopTts()
            result(if(started)"started" else "unavailable",if(started)"차량 TTS의 재생 시작 이벤트를 확인했습니다." else "음성 안내 시작을 확인하지 못했습니다.")
        } catch(cancelled:CancellationException){runCatching{stopTts()};throw cancelled}
        catch(error:Exception){runCatching{stopTts()};result("unavailable","차량 음성 안내 권한/서비스를 확인해 주세요.")}
    }
    suspend fun listen():JSONObject {
        if(closed)return result("unavailable","음성 서비스가 종료되었습니다.")
        stopStt()
        val engine=sttFactory.get()
        stt=engine
        val generation=++sttGeneration
        return try {
            val initialized=withTimeoutOrNull(4000) { suspendCancellableCoroutine<Boolean> { continuation ->
                engine.initialize(object:SttInit {
                    override fun onSuccess(){if(continuation.isActive)continuation.resume(true)}
                    override fun onFailure(errorType:SttError){if(continuation.isActive)continuation.resume(false)}
                })
            } } == true
            if(!initialized||closed||!active||sttGeneration!=generation||stt!==engine)return result("unavailable","차량 음성 인식 연결을 확인해 주세요.")
            engine.setLocale(speechLocale)
            val listener=object:SttResults {
                private fun current()=!closed&&sttGeneration==generation
                override fun onReady(){if(current())sttState="ready"}
                override fun onStartedRecognition(){if(current())sttState="listening"}
                override fun onEndedRecognition(){if(current())sttState="processing"}
                override fun onUpdated(text:String,completed:Boolean){if(current()&&completed){sttState="recognized";sttCompleted?.invoke(text)}}
                override fun onError(errorType:SttError){if(current()){sttState="unavailable";sttCompleted?.invoke(null)}}
                override fun onUpdatedEpdData(on:Long,off:Long)=Unit
                override fun onUpdatedRms(rms:Double)=Unit
            }
            sttListener=listener;engine.addListener(listener)
            val text=withTimeoutOrNull(20000) { suspendCancellableCoroutine<String?> { continuation ->
                sttCompleted={if(continuation.isActive)continuation.resume(it)}
                engine.request()
            } }
            if(text.isNullOrBlank())result("unavailable","음성 인식 결과를 받지 못했습니다. 직접 입력 후 명령을 미리 볼 수 있습니다.")
            else result("recognized","차량 STT 최종 인식 결과입니다. 실행 전 내용을 확인해 주세요.").put("text",text).put("source","Pleos SpeechToText 3.3.1")
        } catch(cancelled:CancellationException){throw cancelled}
        catch(error:Exception){result("unavailable","차량 음성 인식 권한/서비스를 확인해 주세요.")}
        finally{stopStt()}
    }
    private fun stopTts(){ttsGeneration++;ttsPlaying=false;ttsStarted?.invoke(false);ttsStarted=null;val engine=tts;tts=null;try{engine?.stop();ttsListener?.let{engine?.removeEventListener(it)}}finally{engine?.release();ttsListener=null}}
    private fun stopStt(){sttGeneration++;sttCompleted?.invoke(null);sttCompleted=null;val engine=stt;stt=null;try{engine?.stop();sttListener?.let{engine?.removeListener(it)}}finally{engine?.release();sttListener=null}}
    private fun cleanup(id:String){try{fused.removeProximityAlert(id);pendingCleanup.remove(id)}catch(error:Exception){pendingCleanup.add(id);arrivalState="cleanup_pending";arrivalMessage="이전 도착 알림 해제를 확인해야 합니다."}}
    private fun releaseIfIdle(){if(closed&&initialized&&!pendingRegistration&&pendingCleanup.isEmpty()){initialized=false;runCatching{fused.release()}}else if(closed&&(pendingRegistration||pendingCleanup.isNotEmpty())){arrivalState="cleanup_pending";arrivalMessage="대기 중인 등록 응답의 ID를 받은 뒤 알림을 해제합니다."}}
    fun setLanguage(tag:String){
        val next=Locale.forLanguageTag(tag)
        if(next.language==speechLocale.language)return
        speechLocale=next
        runCatching{stopTts()};runCatching{stopStt()}
    }
    fun assistStatus()=JSONObject().put("arrivalStatus",arrivalState).put("arrivalMessage",arrivalMessage).put("ttsStatus",ttsState).put("sttStatus",sttState)
    @Synchronized fun stop(){active=false;locationActive=false;epoch++;watchToken++;latest=null;locationListener?.let{runCatching{fused.unregisterFusedLocationCallback(it)}};locationListener=null;alertId?.let{cleanup(it)};alertId=null;if(watchedSite!=null){arrivalState="paused";arrivalMessage="앱이 다시 활성화되면 도착 알림을 다시 등록합니다."};runCatching{stopTts()};runCatching{stopStt()}}
    @Synchronized fun close(){if(closed)return;try{stop()}finally{closed=true;releaseIfIdle()}}
}
