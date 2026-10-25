package camp.room.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

data class CampUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val data: Bootstrap? = null,
    val sites: List<Campsite> = emptyList(),
    val tab: Int = 0,
    val error: String = "",
    val notice: String = "",
    val checkIn: String =
        LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.SATURDAY)).toString(),
    val checkOut: String =
        LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.SATURDAY)).plusDays(1).toString(),
    val guests: Int = 2,
    val region: String = "전체",
    val carCamping: Boolean = true,
    val facilities: Set<String> = emptySet(),
    val selectedSite: Campsite? = null,
    val cartOpen: Boolean = false,
    val route: RoutePreview? = null,
    val weather: Weather? = null,
    val navigationLaunched: Boolean = false,
    val sceneId: String = "split",
    val commands: List<CommandResult> = emptyList(),
    val commandStatus: String = "",
    val destination: String = "campsite",
    val recipient: String = "데모 캠퍼",
    val address: String = "서울시 캠핑로 12, 101호 (가상 주소)",
    val siteReference: String = "A-12 (데모)",
    val quote: Quote? = null,
    val order: Order? = null,
    val locationSummary: String = "",
    val distances: Map<String, Double> = emptyMap(),
    val arrivalStatus: String = "",
    val arrivalMessage: String = "",
    val ttsStatus: String = "",
    val sttStatus: String = "",
    val voiceOpen: Boolean = false,
    val transcript: String = "",
    val transcriptSource: String = "",
    val voicePreview: String = "",
    val previewTab: Int? = null,
    val previewScene: String? = null,
) {
    val tripSite: Campsite?
        get() = (data?.campsites.orEmpty() + sites).firstOrNull { it.id == data?.trip?.campsiteId }
}

class CampViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CampRepository()
    private val bridge = PlatformBridge(application, repository)
    private val mutable = MutableStateFlow(CampUiState())
    val state = mutable.asStateFlow()
    private var orderKey: String? = null
    private var foregroundVehicleJob: Job? = null

    init {
        reload()
    }

    private fun launchAction(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = "") }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.message ?: "요청을 처리하지 못했어요.") }
            } finally {
                mutable.update { it.copy(busy = false, loading = false) }
            }
        }
    }

    /** SDK subscriptions and polling live only while the Activity is foreground. */
    fun onHostStart() {
        try {
            bridge.start()
        } catch (error: Exception) {
            mutable.update { it.copy(error = "차량 연결을 시작하지 못했어요. ${error.message.orEmpty()}") }
        }
        if (foregroundVehicleJob?.isActive == true) return
        foregroundVehicleJob =
            viewModelScope.launch {
                while (isActive) {
                    if (!mutable.value.busy && mutable.value.data != null) {
                        try {
                            val current = Vehicle.parse(bridge.vehicle())
                            val assist = bridge.assistStatus()
                            mutable.update {
                                it.copy(
                                    data = it.data?.copy(vehicle = current),
                                    arrivalStatus = assist.text("arrivalStatus"),
                                    arrivalMessage = assist.text("arrivalMessage"),
                                    ttsStatus = assist.text("ttsStatus"),
                                    sttStatus = assist.text("sttStatus"),
                                )
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            mutable.update { state ->
                                val unknown =
                                    state.data
                                        ?.vehicle
                                        ?.copy(
                                            soc = null,
                                            measuredTemperature = null,
                                            targetTemperature = null,
                                            front = CabinZone(null, null, null),
                                            rear = CabinZone(null, null, null),
                                            doorsClosed = null,
                                            windowsClosed = null,
                                            motionState = "unknown",
                                            updatedAt = "",
                                        )
                                state.copy(
                                    data = unknown?.let { state.data?.copy(vehicle = it) },
                                    error =
                                        state.error.ifBlank { "차량 상태를 확인하지 못했어요. 다시 연결을 확인해 주세요." },
                                )
                            }
                        }
                    }
                    delay(5_000)
                }
            }
    }

    fun onHostStop() {
        foregroundVehicleJob?.cancel()
        foregroundVehicleJob = null
        try {
            bridge.stop()
        } catch (error: Exception) {
            mutable.update { it.copy(error = "차량 상태 구독 해제를 확인하지 못했어요.") }
        }
    }

    fun reload() = launchAction {
        val boot = repository.bootstrap().let { it.copy(vehicle = Vehicle.parse(bridge.vehicle())) }
        mutable.update {
            it.copy(
                data = boot,
                sites = boot.campsites,
                checkIn = boot.trip?.checkIn ?: it.checkIn,
                checkOut = boot.trip?.checkOut ?: it.checkOut,
                guests = boot.trip?.guests ?: it.guests,
                loading = false,
            )
        }
        loadTrip()
    }

    private suspend fun refresh() {
        val boot = repository.bootstrap().let { it.copy(vehicle = Vehicle.parse(bridge.vehicle())) }
        mutable.update { it.copy(data = boot) }
    }

    private suspend fun loadTrip() {
        val trip = mutable.value.data?.trip ?: return
        val site = mutable.value.tripSite ?: return
        val route = RoutePreview.parse(bridge.navigationPreview(site))
        val weather =
            Weather.parse(
                repository.request(
                    "/weather?campsiteId=${trip.campsiteId}&checkIn=${trip.checkIn}&checkOut=${trip.checkOut}"
                )
            )
        mutable.update { it.copy(route = route, weather = weather) }
    }

    fun tab(value: Int) {
        mutable.update { it.copy(tab = value, error = "") }
        if (value == 1) launchAction { loadTrip() }
    }

    fun dates(checkIn: String, checkOut: String) {
        mutable.update { it.copy(checkIn = checkIn, checkOut = checkOut) }
    }

    fun region(value: String) {
        mutable.update { it.copy(region = value) }
    }

    fun guests(value: Int) {
        mutable.update { it.copy(guests = value.coerceIn(1, 8)) }
    }

    fun carCamping() {
        mutable.update { it.copy(carCamping = !it.carCamping) }
    }

    fun facility(value: String) {
        mutable.update {
            it.copy(
                facilities =
                    if (value in it.facilities) it.facilities - value else it.facilities + value
            )
        }
    }

    fun clearError() {
        mutable.update { it.copy(error = "") }
    }

    fun clearNotice() {
        mutable.update { it.copy(notice = "") }
    }

    fun search() = launchAction {
        val s = mutable.value
        val start = runCatching { LocalDate.parse(s.checkIn) }.getOrNull()
        val end = runCatching { LocalDate.parse(s.checkOut) }.getOrNull()
        require(start != null && end != null && end > start) { "체크아웃은 체크인 다음 날 이후로 선택해 주세요." }
        val params =
            mutableMapOf(
                "checkIn" to s.checkIn,
                "checkOut" to s.checkOut,
                "guests" to s.guests.toString(),
                "carCamping" to s.carCamping.toString(),
            )
        if (s.region != "전체") params["region"] = s.region
        if (s.facilities.isNotEmpty()) params["facilities"] = s.facilities.joinToString(",")
        val sites = repository.campsites(params)
        mutable.update { it.copy(sites = sites) }
    }

    fun resetFilters() {
        mutable.update { it.copy(region = "전체", facilities = emptySet(), carCamping = false) }
        search()
    }

    fun detail(site: Campsite?) {
        mutable.update { it.copy(selectedSite = site, error = "") }
    }

    fun saveTrip() = launchAction {
        val s = mutable.value
        val site = s.selectedSite ?: return@launchAction
        repository.request(
            "/trip",
            "PUT",
            JSONObject()
                .put("campsiteId", site.id)
                .put("checkIn", s.checkIn)
                .put("checkOut", s.checkOut)
                .put("guests", s.guests),
        )
        refresh()
        mutable.update {
            it.copy(
                selectedSite = null,
                tab = 1,
                quote = null,
                notice = "이번 캠핑이 저장됐어요. 여정을 준비해 볼까요?",
            )
        }
        loadTrip()
    }

    fun navigation() = launchAction {
        val site = mutable.value.tripSite ?: return@launchAction
        val result = bridge.navigationStart(site)
        refresh()
        mutable.update {
            it.copy(
                navigationLaunched = result.optBoolean("started"),
                notice =
                    result.text(
                        "message",
                        if (BuildConfig.IS_DEMO) "데모 길안내가 시작됐어요." else "Pleos 내비게이션 요청을 전달했어요.",
                    ),
            )
        }
    }

    fun scene(value: String) {
        mutable.update { it.copy(sceneId = value) }
    }

    fun applyScene() = launchAction {
        val result = bridge.scene(mutable.value.sceneId)
        vehicleResult(result)
        mutable.update {
            it.copy(
                notice =
                    if (result.text("status") == "completed")
                        if (BuildConfig.IS_DEMO) "공간 설정을 적용하고 시뮬레이터 관측값을 확인했어요."
                        else "공간 설정 명령과 지원되는 관측 상태를 확인했어요."
                    else "일부 항목을 확인해 주세요."
            )
        }
    }

    private suspend fun vehicleResult(result: JSONObject) {
        refresh()
        mutable.update {
            it.copy(
                commands = result.objects("results").map(CommandResult::parse),
                commandStatus = result.text("status"),
            )
        }
    }

    fun control(name: String, value: Any, zone: String? = null) = launchAction {
        val body = JSONObject().put("control", name).put("value", value)
        if (zone != null) body.put("zone", zone)
        vehicleResult(bridge.control(body))
    }

    fun cart(open: Boolean) {
        mutable.update { it.copy(cartOpen = open, error = "") }
    }

    fun addProduct(id: String) = launchAction {
        repository.request(
            "/cart/items",
            "POST",
            JSONObject().put("productId", id).put("quantity", 1),
        )
        refresh()
        mutable.update { it.copy(order = null, quote = null, notice = "준비물을 장바구니에 담았어요.") }
    }

    fun quantity(id: String, value: Int) = launchAction {
        if (value <= 0) repository.request("/cart/items/$id", "DELETE")
        else repository.request("/cart/items/$id", "PATCH", JSONObject().put("quantity", value))
        refresh()
        mutable.update { it.copy(quote = null) }
    }

    fun delivery(
        destination: String? = null,
        recipient: String? = null,
        address: String? = null,
        siteReference: String? = null,
    ) {
        orderKey = null
        mutable.update {
            it.copy(
                destination = destination ?: it.destination,
                recipient = recipient ?: it.recipient,
                address = address ?: it.address,
                siteReference = siteReference ?: it.siteReference,
                quote = null,
            )
        }
    }

    private fun deliveryBody(): JSONObject {
        val s = mutable.value
        val trip = s.data?.trip
        return JSONObject()
            .put("destination", s.destination)
            .put("recipientName", s.recipient)
            .put("address", s.address)
            .put("siteReference", s.siteReference)
            .apply {
                trip?.let {
                    put("campsiteId", it.campsiteId)
                    put("checkIn", it.checkIn)
                    put("checkOut", it.checkOut)
                }
            }
    }

    fun quote() = launchAction {
        val s = mutable.value
        require(s.recipient.isNotBlank()) { "받는 분의 이름을 입력해 주세요." }
        if (s.destination == "home") require(s.address.isNotBlank()) { "자택 배송 주소를 입력해 주세요." }
        else require(s.siteReference.isNotBlank()) { "사이트 또는 예약 참조를 입력해 주세요." }
        val quote = Quote.parse(repository.request("/checkout/quote", "POST", deliveryBody()))
        mutable.update { it.copy(quote = quote) }
    }

    fun checkout() = launchAction {
        require(mutable.value.quote?.eligible == true) { "배송 가능 여부를 먼저 확인해 주세요." }
        val key = orderKey ?: UUID.randomUUID().toString().also { orderKey = it }
        val order =
            Order.parse(
                repository.request("/orders", "POST", deliveryBody().put("idempotencyKey", key))
            )
        refresh()
        mutable.update {
            it.copy(order = order, quote = null, notice = "데모 주문이 저장됐어요. 실제 결제는 발생하지 않았습니다.")
        }
        orderKey = null
    }

    fun permissionDenied(kind: String) {
        mutable.update { it.copy(error = "$kind 권한이 없어 기능을 시작하지 못했어요. 설정에서 권한을 허용한 뒤 다시 시도해 주세요.") }
    }

    fun nearby() = launchAction {
        val result = bridge.location()
        val latitude = result.nullNumber("latitude")
        val longitude = result.nullNumber("longitude")
        require(
            result.text("status") == "available" &&
                latitude != null &&
                longitude != null &&
                latitude in -90.0..90.0 &&
                longitude in -180.0..180.0
        ) {
            result.text("message", "마지막으로 확인된 위치를 조회할 수 없어요.")
        }
        val distances =
            mutable.value.sites
                .mapNotNull { site ->
                    if (site.latitude == null || site.longitude == null) null
                    else
                        site.id to
                            campDistanceKm(latitude, longitude, site.latitude, site.longitude)
                }
                .toMap()
        mutable.update {
            it.copy(
                sites =
                    it.sites.sortedBy { site -> distances[site.id] ?: Double.POSITIVE_INFINITY },
                distances = distances,
                locationSummary = "${result.text("source", "기기 위치")} · 마지막으로 확인된 위치 기준 가까운 순서",
                notice = "마지막으로 확인된 위치를 기준으로 캠핑장을 정렬했어요.",
            )
        }
    }

    fun arrivalWatch() = launchAction {
        val site = mutable.value.tripSite ?: error("캠핑장을 먼저 내 캠핑에 담아주세요.")
        val result = bridge.arrivalWatch(site)
        mutable.update {
            it.copy(
                arrivalStatus = result.text("status"),
                arrivalMessage = result.text("message"),
                notice = result.text("message"),
            )
        }
    }

    fun speakBriefing() = launchAction {
        val s = mutable.value
        val site = s.tripSite ?: error("캠핑장을 먼저 내 캠핑에 담아주세요.")
        val weather = s.weather ?: s.data?.weather
        val weatherText =
            if (weather?.status == "available")
                "${if(weather.demo)"시연용 데모 예보입니다. "else""}날씨는 ${weather.summary}. 밤 최저 기온은 ${number(weather.low)}도입니다."
            else "이 기간의 날씨 예보는 확인이 필요합니다."
        val soc = s.data?.vehicle?.soc
        val batteryText =
            if (soc == null) "현재 배터리 상태는 확인이 필요합니다." else "현재 배터리는 ${number(soc)}퍼센트입니다."
        val text =
            "${if(BuildConfig.IS_DEMO)"데모 "else""}${site.name} 여행 안내입니다. 체크인은 ${site.checkInTime}, 체크아웃은 ${site.checkOutTime}입니다. $weatherText $batteryText ${site.rules.joinToString(". ")}"
        val result = bridge.speak(text)
        mutable.update {
            it.copy(
                ttsStatus = result.text("status"),
                notice = result.text("message", "음성 안내 요청을 전달했어요."),
            )
        }
    }

    fun voice(open: Boolean) {
        mutable.update { it.copy(voiceOpen = open, error = "") }
    }

    fun transcript(value: String) {
        mutable.update {
            it.copy(
                transcript = value,
                transcriptSource = "직접 입력한 텍스트",
                voicePreview = "",
                previewTab = null,
                previewScene = null,
            )
        }
    }

    fun listen() = launchAction {
        val result = bridge.listen()
        mutable.update {
            it.copy(
                sttStatus = result.text("status"),
                transcript =
                    if (result.text("status") == "recognized") result.text("text")
                    else it.transcript,
                transcriptSource =
                    if (result.text("status") == "recognized") "음성 인식 결과"
                    else "음성 인식 불가 · 직접 입력 가능",
                voicePreview = "",
                previewTab = null,
                previewScene = null,
                notice = result.text("message"),
            )
        }
    }

    fun previewVoice() {
        val text = mutable.value.transcript.replace(" ", "")
        val blocked = listOf("결제", "구매", "주문").any { text.contains(it) }
        val scene =
            when {
                text.contains("취침") || text.contains("잠자") -> "sleep"
                text.contains("독서") || text.contains("책읽") || text.contains("따로쉬") -> "split"
                text.contains("함께쉬") || text.contains("같이쉬") -> "together"
                text.contains("짐정리") -> "load"
                text.contains("출발준비") -> "departure"
                else -> null
            }
        val navigation = text.contains("길안내") || text.contains("내비")
        mutable.update {
            it.copy(
                voicePreview =
                    when {
                        blocked -> "음성으로 주문이나 결제를 실행하지 않습니다. 장바구니에서 직접 확인해 주세요."
                        scene != null ->
                            "차박 화면에서 '${campScenes.first { it.id == scene }.name}'을 미리 선택합니다. 실제 적용은 '이 공간 설정 적용하기' 버튼으로 직접 실행해 주세요."
                        navigation -> "내 캠핑의 이동 계획으로 이동합니다. 실제 안내는 '길안내 시작' 버튼으로 직접 실행해 주세요."
                        else -> "지원하는 표현을 확인해 주세요. 예: 취침 준비, 독서와 휴식, 길안내"
                    },
                previewTab =
                    if (blocked) null else if (scene != null) 2 else if (navigation) 1 else null,
                previewScene = if (blocked) null else scene,
            )
        }
    }

    fun openVoicePreview() {
        val s = mutable.value
        val tab = s.previewTab ?: return
        mutable.update {
            it.copy(tab = tab, sceneId = s.previewScene ?: it.sceneId, voiceOpen = false)
        }
        if (tab == 1) launchAction { loadTrip() }
    }

    override fun onCleared() {
        foregroundVehicleJob?.cancel()
        bridge.close()
        super.onCleared()
    }

    fun closeOrder() {
        mutable.update { it.copy(cartOpen = false, order = null, tab = 1) }
    }
}

/** Great-circle distance; unknown site coordinates are never replaced by zero. */
internal fun campDistanceKm(
    latitude: Double,
    longitude: Double,
    otherLatitude: Double,
    otherLongitude: Double,
): Double {
    val deltaLatitude = Math.toRadians(otherLatitude - latitude)
    val deltaLongitude = Math.toRadians(otherLongitude - longitude)
    val a =
        kotlin.math.sin(deltaLatitude / 2) * kotlin.math.sin(deltaLatitude / 2) +
            kotlin.math.cos(Math.toRadians(latitude)) *
                kotlin.math.cos(Math.toRadians(otherLatitude)) *
                kotlin.math.sin(deltaLongitude / 2) *
                kotlin.math.sin(deltaLongitude / 2)
    return 6371.0 * 2 * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
}
