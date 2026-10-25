package camp.room.app

import android.app.Application
import camp.room.app.observability.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

data class CampUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val interactionAllowed: Boolean = BuildConfig.IS_DEMO,
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
    val recipient: String = camp.room.app.CampText.get(camp.room.app.R.string.camp_demo_camper),
    val address: String = camp.room.app.CampText.get(camp.room.app.R.string.camp_12_camping_road_unit_101_seoul_fictional),
    val siteReference: String = camp.room.app.CampText.get(camp.room.app.R.string.camp_a_12_demo),
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

/** A quote is valid only for the exact delivery and cart facts sent with it. */
internal data class DeliveryQuoteSnapshot private constructor(
    val delivery: DeliveryDetails,
    private val cart: Cart?,
) {
    companion object {
        fun capture(delivery: DeliveryDetails, cart: Cart?) =
            DeliveryQuoteSnapshot(delivery, cart?.copy(items = cart.items.toList()))
    }
}

/** Only the currently owned user action can be cancelled; read-only loads are excluded. */
internal class CampActionJobs {
    private var action: Action? = null
    private var job: Job? = null

    fun track(action: Action, job: Job) {
        if (action == Action.LOAD) return
        this.action = action
        this.job = job
    }

    fun complete(job: Job) {
        if (this.job === job) {
            this.action = null
            this.job = null
        }
    }

    fun cancelRecognition() {
        if (action == Action.LISTEN) job?.cancel()
    }

    fun cancelUserAction() {
        job?.cancel()
    }
}

@HiltViewModel
class CampViewModel
@Inject
constructor(
    application: Application,
    private val repository: CampRepository,
    private val bridge: PlatformBridge,
    private val telemetry: Telemetry,
) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(CampUiState())
    val state = mutable.asStateFlow()
    private var orderKey: String? = null
    private var foregroundVehicleJob: Job? = null
    private val actionJobs = CampActionJobs()
    private var quotedInput: DeliveryQuoteSnapshot? = null

    init {
        telemetry.record(Event.SCREEN_VIEWED,Dimensions(screen=Screen.FIND))
        reload()
    }

    private fun launchAction(action:Action=Action.LOAD, block: suspend () -> Unit) {
        if (mutable.value.busy || (action != Action.LOAD && !mutable.value.interactionAllowed)) return
        mutable.update { it.copy(busy = true, error = "") }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val began=System.nanoTime()
            try {
                block()
                currentCoroutineContext().ensureActive()
                telemetry.record(Event.ACTION_COMPLETED,Dimensions(action=action,outcome=Outcome.SUCCESS,durationMs=(System.nanoTime()-began)/1_000_000))
            } catch (e: CancellationException) {
                if (action == Action.LISTEN) mutable.update { it.copy(sttStatus = "stopped") }
                telemetry.record(Event.ACTION_COMPLETED,Dimensions(action=action,outcome=Outcome.CANCELLED))
                throw e
            } catch (e: Exception) {
                telemetry.failure(e,Dimensions(action=action,durationMs=(System.nanoTime()-began)/1_000_000))
                mutable.update { it.copy(error = if(e is java.io.IOException || e is io.ktor.serialization.ContentConvertException || e is kotlinx.serialization.SerializationException) CampText.get(R.string.camp_we_couldn_t_complete_the_request) else e.message ?: CampText.get(R.string.camp_we_couldn_t_complete_the_request)) }
            } finally {
                currentCoroutineContext()[Job]?.let(actionJobs::complete)
                mutable.update { it.copy(busy = false, loading = false) }
            }
        }
        actionJobs.track(action, job)
        if (!job.start() && job.isCancelled) {
            actionJobs.complete(job)
            mutable.update { it.copy(busy = false, loading = false) }
        }
    }

    fun onUxAvailabilityChanged(allowed: Boolean) {
        mutable.update {
            if (allowed) it.copy(interactionAllowed = true)
            else it.copy(
                interactionAllowed = false,
                selectedSite = null,
                cartOpen = false,
                voiceOpen = false,
                voicePreview = "",
                previewTab = null,
                previewScene = null,
            )
        }
        if (!allowed) actionJobs.cancelUserAction()
    }

    /** SDK subscriptions and polling live only while the Activity is foreground. */
    fun onHostStart() {
        try {
            bridge.start()
        } catch (error: Exception) {
            mutable.update { it.copy(error = camp.room.app.CampText.get(camp.room.app.R.string.camp_we_couldn_t_start_the_vehicle_connection, error.message.orEmpty())) }
        }
        if (foregroundVehicleJob?.isActive == true) return
        foregroundVehicleJob =
            viewModelScope.launch {
                while (isActive) {
                    if (!mutable.value.busy && mutable.value.data != null) {
                        try {
                            val current = Vehicle.parse(sdk(SdkOperation.VEHICLE_STATE) { bridge.vehicle() })
                            val assist = AssistMessages.status(bridge.assistStatus())
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
                                            layoutSource = "unknown",
                                            controlAreas = emptyList(),
                                            unknownFeatures = emptyList(),
                                        )
                                state.copy(
                                    data = unknown?.let { state.data?.copy(vehicle = it) },
                                    error =
                                        state.error.ifBlank { camp.room.app.CampText.get(camp.room.app.R.string.camp_we_couldn_t_read_the_vehicle_state) },
                                )
                            }
                        }
                    }
                    delay(5_000)
                }
            }
    }

    fun onHostStop() {
        actionJobs.cancelRecognition()
        foregroundVehicleJob?.cancel()
        foregroundVehicleJob = null
        try {
            bridge.stop()
        } catch (error: Exception) {
            mutable.update { it.copy(error = camp.room.app.CampText.get(camp.room.app.R.string.camp_we_couldn_t_confirm_that_the_vehicle)) }
        }
    }

    fun reload() = launchAction {
        val boot = repository.bootstrap().let { it.copy(vehicle = Vehicle.parse(sdk(SdkOperation.VEHICLE_STATE) { bridge.vehicle() })) }
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
        val boot = repository.bootstrap().let { it.copy(vehicle = Vehicle.parse(sdk(SdkOperation.VEHICLE_STATE) { bridge.vehicle() })) }
        mutable.update {
            val next = it.copy(data = boot)
            if (quotedInput == quoteInput(next)) next else next.copy(quote = null)
        }
    }

    private suspend fun loadTrip() {
        val trip = mutable.value.data?.trip ?: return
        val site = mutable.value.tripSite ?: return
        val route = RoutePreview.parse(sdk(SdkOperation.NAVIGATION_PREVIEW) { bridge.navigationPreview(site) })
        val weather = repository.weather(trip.campsiteId,trip.checkIn,trip.checkOut)
        mutable.update { it.copy(route = route, weather = weather) }
    }

    fun tab(value: Int) {
        if (!mutable.value.interactionAllowed) return
        Screen.entries.getOrNull(value)?.let { telemetry.record(Event.SCREEN_VIEWED,Dimensions(screen=it)) }
        mutable.update { it.copy(tab = value, error = "") }
        if (value == 1) launchAction { loadTrip() }
    }

    fun dates(checkIn: String, checkOut: String) {
        if (!mutable.value.interactionAllowed || mutable.value.busy) return
        mutable.update { it.copy(checkIn = checkIn, checkOut = checkOut) }
    }

    fun region(value: String) {
        if (!mutable.value.interactionAllowed || mutable.value.busy) return
        mutable.update { it.copy(region = value) }
    }

    fun guests(value: Int) {
        if (!mutable.value.interactionAllowed || mutable.value.busy) return
        mutable.update { it.copy(guests = value.coerceIn(1, 8)) }
    }

    fun carCamping() {
        if (!mutable.value.interactionAllowed || mutable.value.busy) return
        mutable.update { it.copy(carCamping = !it.carCamping) }
    }

    fun facility(value: String) {
        if (!mutable.value.interactionAllowed || mutable.value.busy) return
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

    fun search() = launchAction(Action.SEARCH) {
        val s = mutable.value
        val start = runCatching { LocalDate.parse(s.checkIn) }.getOrNull()
        val end = runCatching { LocalDate.parse(s.checkOut) }.getOrNull()
        require(start != null && end != null && end > start) { camp.room.app.CampText.get(camp.room.app.R.string.camp_choose_a_check_out_date_after_check) }
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
        if (!mutable.value.interactionAllowed || mutable.value.busy) return
        mutable.update { it.copy(region = "전체", facilities = emptySet(), carCamping = false) }
        search()
    }

    fun detail(site: Campsite?) {
        if (site != null && !mutable.value.interactionAllowed) return
        mutable.update { it.copy(selectedSite = site, error = "") }
    }

    fun saveTrip() = launchAction(Action.SAVE_TRIP) {
        val s = mutable.value
        val site = s.selectedSite ?: return@launchAction
        repository.saveTrip(site.id,s.checkIn,s.checkOut,s.guests)
        refresh()
        mutable.update {
            it.copy(
                selectedSite = null,
                tab = 1,
                quote = null,
                notice = camp.room.app.CampText.get(camp.room.app.R.string.camp_your_trip_is_saved_shall_we_get),
            )
        }
        loadTrip()
    }

    fun navigation() = launchAction(Action.NAVIGATION) {
        val site = mutable.value.tripSite ?: return@launchAction
        val result = AssistMessages.result("navigation", sdk(SdkOperation.NAVIGATION_START) { bridge.navigationStart(site) })
        refresh()
        mutable.update {
            it.copy(
                navigationLaunched = result.optBoolean("started"),
                notice =
                    result.text(
                        "message",
                        if (BuildConfig.IS_DEMO) camp.room.app.CampText.get(camp.room.app.R.string.camp_demo_navigation_has_started) else camp.room.app.CampText.get(camp.room.app.R.string.camp_the_request_was_sent_to_pleos_navigation),
                    ),
            )
        }
    }

    fun scene(value: String) {
        if (mutable.value.busy || !mutable.value.interactionAllowed) return
        mutable.update { it.copy(sceneId = value) }
    }

    fun applyScene() = launchAction(Action.APPLY_SCENE) {
        val result = sdk(SdkOperation.VEHICLE_SCENE) { bridge.scene(mutable.value.sceneId) }
        vehicleResult(result)
        mutable.update {
            it.copy(
                notice =
                    if (result.text("status") == "completed")
                        if (BuildConfig.IS_DEMO) camp.room.app.CampText.get(camp.room.app.R.string.camp_cabin_settings_were_applied_and_the_simulator)
                        else camp.room.app.CampText.get(camp.room.app.R.string.camp_cabin_commands_and_supported_observations_were_checked)
                    else camp.room.app.CampText.get(camp.room.app.R.string.camp_please_check_the_remaining_items)
            )
        }
    }

    private suspend fun vehicleResult(raw: JSONObject) {
        val result = AssistMessages.vehicle(raw)
        refresh()
        mutable.update {
            it.copy(
                commands = result.objects("results").map(CommandResult::parse),
                commandStatus = result.text("status"),
            )
        }
    }

    fun control(name: String, value: Any, zone: String? = null) = launchAction(Action.CONTROL) {
        val body = JSONObject().put("control", name).put("value", value)
        if (zone != null) body.put("zone", zone)
        vehicleResult(sdk(SdkOperation.VEHICLE_CONTROL) { bridge.control(body) })
    }

    fun cart(open: Boolean) {
        if (open && !mutable.value.interactionAllowed) return
        if(open)telemetry.record(Event.SCREEN_VIEWED,Dimensions(screen=Screen.CART))
        mutable.update { it.copy(cartOpen = open, error = "") }
    }

    fun addProduct(id: String) = launchAction(Action.ADD_PRODUCT) {
        repository.addProduct(id)
        refresh()
        mutable.update { it.copy(order = null, quote = null, notice = camp.room.app.CampText.get(camp.room.app.R.string.camp_the_essential_was_added_to_your_cart)) }
    }

    fun quantity(id: String, value: Int) = launchAction(Action.CART_QUANTITY) {
        repository.quantity(id,value)
        refresh()
        mutable.update { it.copy(quote = null) }
    }

    fun delivery(
        destination: String? = null,
        recipient: String? = null,
        address: String? = null,
        siteReference: String? = null,
    ) {
        if (mutable.value.busy || !mutable.value.interactionAllowed) return
        orderKey = null
        quotedInput = null
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

    private fun deliveryDetails(s: CampUiState):DeliveryDetails {
        val trip=s.data?.trip
        return DeliveryDetails(s.destination,s.recipient,s.address,s.siteReference,trip?.campsiteId,trip?.checkIn,trip?.checkOut)
    }

    private fun quoteInput(s: CampUiState) =
        DeliveryQuoteSnapshot.capture(deliveryDetails(s), s.data?.cart)

    fun quote() = launchAction(Action.QUOTE) {
        val s = mutable.value
        require(s.recipient.isNotBlank()) { camp.room.app.CampText.get(camp.room.app.R.string.camp_enter_the_recipient_s_name) }
        if (s.destination == "home") require(s.address.isNotBlank()) { camp.room.app.CampText.get(camp.room.app.R.string.camp_enter_your_home_delivery_address) }
        else require(s.siteReference.isNotBlank()) { camp.room.app.CampText.get(camp.room.app.R.string.camp_enter_a_pitch_or_reservation_reference) }
        val requested = quoteInput(s)
        if (quotedInput != requested) orderKey = null
        quotedInput = null
        mutable.update { it.copy(quote = null) }
        val quote = repository.quote(requested.delivery)
        currentCoroutineContext().ensureActive()
        if (requested != quoteInput(mutable.value)) return@launchAction
        quotedInput = requested
        mutable.update { if (requested == quoteInput(it)) it.copy(quote = quote) else it }
    }

    fun checkout() = launchAction(Action.CHECKOUT) {
        val requested = quoteInput(mutable.value)
        require(mutable.value.quote?.eligible == true && quotedInput == requested) { camp.room.app.CampText.get(camp.room.app.R.string.camp_check_delivery_availability_first) }
        val key = orderKey ?: UUID.randomUUID().toString().also { orderKey = it }
        val order = repository.order(requested.delivery,key)
        refresh()
        mutable.update {
            it.copy(order = order, quote = null, notice = camp.room.app.CampText.get(camp.room.app.R.string.camp_the_demo_order_is_saved_no_real))
        }
        orderKey = null
    }

    private suspend fun sdk(operation:SdkOperation, request:suspend ()->JSONObject):JSONObject {
        val began=System.nanoTime()
        try {
            val result=request()
            currentCoroutineContext().ensureActive()
            val outcome=when(result.text("status")) {
                "available","completed","recognized","registered"->Outcome.SUCCESS
                "started","inside","entered","exited"->Outcome.STARTED
                "blocked"->Outcome.BLOCKED
                "partial"->Outcome.PARTIAL
                "failed"->Outcome.FAILED
                "unavailable","unsupported"->Outcome.UNAVAILABLE
                else->Outcome.UNVERIFIED
            }
            telemetry.record(Event.SDK_COMPLETED,Dimensions(sdk=operation,outcome=outcome,durationMs=(System.nanoTime()-began)/1_000_000))
            return result
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(error:Exception) {
            telemetry.failure(error,Dimensions(sdk=operation,durationMs=(System.nanoTime()-began)/1_000_000))
            throw error
        }
    }

    fun setLanguage(tag:String) {
        bridge.setLanguage(tag)
        mutable.update { it.copy(error="",notice="",commands=emptyList(),commandStatus="",voicePreview="",transcriptSource="",arrivalMessage="") }
    }

    fun permissionDenied(kind: String) {
        mutable.update { it.copy(error = camp.room.app.CampText.get(camp.room.app.R.string.camp_permission_is_missing_allow_it_in_settings, kind)) }
    }

    fun nearby() = launchAction(Action.LOCATION) {
        val result = AssistMessages.result("location", sdk(SdkOperation.LOCATION) { bridge.location() })
        val latitude = result.nullNumber("latitude")
        val longitude = result.nullNumber("longitude")
        require(
            result.text("status") == "available" &&
                latitude != null &&
                longitude != null &&
                latitude in -90.0..90.0 &&
                longitude in -180.0..180.0
        ) {
            result.text("message", camp.room.app.CampText.get(camp.room.app.R.string.camp_we_couldn_t_read_the_last_known))
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
                locationSummary = camp.room.app.CampText.get(camp.room.app.R.string.camp_sorted_from_the_last_observed_location, result.text("source", camp.room.app.CampText.get(camp.room.app.R.string.camp_device_location))),
                notice = camp.room.app.CampText.get(camp.room.app.R.string.camp_campsites_were_sorted_from_the_last_observed),
            )
        }
    }

    fun arrivalWatch() = launchAction(Action.ARRIVAL_WATCH) {
        val site = mutable.value.tripSite ?: error(camp.room.app.CampText.get(camp.room.app.R.string.camp_save_a_campsite_to_my_trip_first))
        val result = AssistMessages.result("arrival", sdk(SdkOperation.ARRIVAL_WATCH) { bridge.arrivalWatch(site) })
        mutable.update {
            it.copy(
                arrivalStatus = result.text("status"),
                arrivalMessage = result.text("message"),
                notice = result.text("message"),
            )
        }
    }

    fun speakBriefing() = launchAction(Action.BRIEFING) {
        val s = mutable.value
        val site = s.tripSite ?: error(camp.room.app.CampText.get(camp.room.app.R.string.camp_save_a_campsite_to_my_trip_first))
        val weather = s.weather ?: s.data?.weather
        val weatherText =
            if (weather?.status == "available")
                camp.room.app.CampText.get(camp.room.app.R.string.camp_the_forecast_is_the_overnight_low_is, if(weather.demo)camp.room.app.CampText.get(camp.room.app.R.string.camp_this_is_a_demo_forecast)else"", weather.summary, number(weather.low))
            else camp.room.app.CampText.get(camp.room.app.R.string.camp_the_forecast_for_these_dates_needs_checking)
        val soc = s.data?.vehicle?.soc
        val batteryText =
            if (soc == null) camp.room.app.CampText.get(camp.room.app.R.string.camp_the_current_battery_state_needs_checking) else camp.room.app.CampText.get(camp.room.app.R.string.camp_the_current_battery_is_percent, number(soc))
        val text =
            camp.room.app.CampText.get(camp.room.app.R.string.camp_trip_briefing_check_in_is_and_check, if(BuildConfig.IS_DEMO)camp.room.app.CampText.get(camp.room.app.R.string.camp_demo_310)else"", site.name, site.checkInTime, site.checkOutTime, weatherText, batteryText, site.rules.joinToString(". "))
        val result = AssistMessages.result("tts", sdk(SdkOperation.TTS) { bridge.speak(text) })
        mutable.update {
            it.copy(
                ttsStatus = result.text("status"),
                notice = result.text("message", camp.room.app.CampText.get(camp.room.app.R.string.camp_the_spoken_briefing_request_was_sent)),
            )
        }
    }

    fun voice(open: Boolean) {
        if (open && !mutable.value.interactionAllowed) return
        if (!open) actionJobs.cancelRecognition()
        if(open)telemetry.record(Event.SCREEN_VIEWED,Dimensions(screen=Screen.VOICE))
        mutable.update { it.copy(voiceOpen = open, error = "") }
    }

    fun transcript(value: String) {
        if (mutable.value.busy || !mutable.value.interactionAllowed) return
        mutable.update {
            it.copy(
                transcript = value,
                transcriptSource = camp.room.app.CampText.get(camp.room.app.R.string.camp_manually_typed_text),
                voicePreview = "",
                previewTab = null,
                previewScene = null,
            )
        }
    }

    fun listen() {
        if (!mutable.value.voiceOpen) return
        launchAction(Action.LISTEN) {
            val result = AssistMessages.result("stt", sdk(SdkOperation.STT) { bridge.listen() })
            currentCoroutineContext().ensureActive()
            if (!mutable.value.voiceOpen) return@launchAction
            mutable.update {
                it.copy(
                    sttStatus = result.text("status"),
                    transcript =
                        if (result.text("status") == "recognized") result.text("text")
                        else it.transcript,
                    transcriptSource =
                        if (result.text("status") == "recognized") camp.room.app.CampText.get(camp.room.app.R.string.camp_speech_recognition_result)
                        else camp.room.app.CampText.get(camp.room.app.R.string.camp_voice_unavailable_you_can_type_instead),
                    voicePreview = "",
                    previewTab = null,
                    previewScene = null,
                    notice = result.text("message"),
                )
            }
        }
    }

    fun previewVoice() {
        if (mutable.value.busy || !mutable.value.interactionAllowed) return
        // Intent vocabulary is bilingual input data, not translated presentation strings.
        val text = mutable.value.transcript.lowercase(java.util.Locale.ROOT).filterNot { it.isWhitespace() }
        fun contains(vararg words:String)=words.any { text.contains(it) }
        val blocked=contains("결제","구매","주문","payment","pay","purchase","checkout","order","buy","reserve")
        val scene=when {
            contains("취침","잠자","sleep","bedtime")->"sleep"
            contains("독서","책읽","따로쉬","reading","read","split")->"split"
            contains("함께쉬","같이쉬","together","relax")->"together"
            contains("짐정리","unpack","unload","organize")->"load"
            contains("출발준비","departure","depart","packup")->"departure"
            else->null
        }
        val navigation=contains("길안내","내비","navigate","navigation","directions","route")
        mutable.update {
            it.copy(
                voicePreview =
                    when {
                        blocked -> camp.room.app.CampText.get(camp.room.app.R.string.camp_voice_doesn_t_place_orders_or_make)
                        scene != null ->
                            camp.room.app.CampText.get(camp.room.app.R.string.camp_preview_on_the_cabin_screen_apply_it, campScenes.first { it.id == scene }.name)
                        navigation -> camp.room.app.CampText.get(camp.room.app.R.string.camp_open_the_travel_plan_in_my_trip)
                        else -> camp.room.app.CampText.get(camp.room.app.R.string.camp_try_a_supported_phrase_prepare_for_sleep)
                    },
                previewTab =
                    if (blocked) null else if (scene != null) 2 else if (navigation) 1 else null,
                previewScene = if (blocked) null else scene,
            )
        }
    }

    fun openVoicePreview() {
        if (mutable.value.busy || !mutable.value.interactionAllowed) return
        val s = mutable.value
        val tab = s.previewTab ?: return
        mutable.update {
            it.copy(tab = tab, sceneId = s.previewScene ?: it.sceneId, voiceOpen = false)
        }
        if (tab == 1) launchAction { loadTrip() }
    }

    override fun onCleared() {
        actionJobs.cancelUserAction()
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
