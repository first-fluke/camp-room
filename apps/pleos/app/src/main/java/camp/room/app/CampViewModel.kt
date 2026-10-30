package camp.room.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import camp.room.app.observability.Action
import camp.room.app.observability.Dimensions
import camp.room.app.observability.Event
import camp.room.app.observability.Outcome
import camp.room.app.observability.Screen
import camp.room.app.observability.SdkOperation
import camp.room.app.observability.Telemetry
import dagger.hilt.android.lifecycle.HiltViewModel
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/** Only app-authored, localized validation text may be displayed verbatim. */
private class CampUserMessageException(
    message: String,
) : IllegalArgumentException(message)

@OptIn(ExperimentalContracts::class)
private inline fun requireUserInput(
    condition: Boolean,
    message: () -> String,
) {
    contract { returns() implies condition }
    if (!condition) throw CampUserMessageException(message())
}

data class CampUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val interactionAllowed: Boolean = BuildConfig.IS_DEMO,
    val data: Bootstrap? = null,
    val sites: List<Campsite> = emptyList(),
    val catalogLoading: Boolean = false,
    val catalogError: String = "",
    val catalogWarning: String = "",
    val catalogPageNumber: Int = 0,
    val catalogTotal: Int = 0,
    val catalogSourceTotal: Int? = null,
    val catalogHasNext: Boolean = false,
    val catalogHasPrevious: Boolean = false,
    val catalogNeedsSearch: Boolean = false,
    val tab: Int = 0,
    val error: String = "",
    val notice: String = "",
    val checkIn: String =
        LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.SATURDAY)).toString(),
    val checkOut: String =
        LocalDate
            .now()
            .with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
            .plusDays(1)
            .toString(),
    val guests: Int = 2,
    val region: String = "전체",
    val carCamping: Boolean = false,
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
    val recipient: String = "",
    val address: String = "",
    val siteReference: String = "",
    val quote: Quote? = null,
    val order: Order? = null,
    val locationSummary: String = "",
    val distances: Map<String, Double> = emptyMap(),
    val arrivalStatus: String = "",
    val arrivalMessage: String = "",
    val ttsStatus: String = "",
    val sttStatus: String = "",
    val ttsMessage: String = "",
    val sttMessage: String = "",
    val ttsRetryable: Boolean = true,
    val sttRetryable: Boolean = true,
    val voiceOpen: Boolean = false,
    val transcript: String = "",
    val transcriptSource: String = "",
    val voicePreview: String = "",
    val previewTab: Int? = null,
    val previewScene: String? = null,
    val vehicleObservationSession: Long = System.nanoTime().coerceAtLeast(1),
) {
    val tripSite: Campsite?
        get() = (data?.campsites.orEmpty() + sites + listOfNotNull(data?.tripCampsite)).firstOrNull { it.id == data?.trip?.campsiteId }
}

/** A quote is valid only for the exact delivery and cart facts sent with it. */
internal data class DeliveryQuoteSnapshot private constructor(
    val delivery: DeliveryDetails,
    private val cart: Cart?,
) {
    companion object {
        fun capture(
            delivery: DeliveryDetails,
            cart: Cart?,
        ) = DeliveryQuoteSnapshot(delivery, cart?.copy(items = cart.items.toList()))
    }
}

/** Only the currently owned user action can be cancelled; read-only loads are excluded. */
internal class CampActionJobs {
    private var action: Action? = null
    private var job: Job? = null

    fun track(
        action: Action,
        job: Job,
    ) {
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
        private val observationAdmission = CampObservationAdmission()
        private val tripReadRequests = CampTripReadRequests()
        private val actionJobs = CampActionJobs()
        private var quotedInput: DeliveryQuoteSnapshot? = null
        private val catalogHistory = CatalogPageHistory<Campsite> { it.id }
        private var catalogJob: Job? = null
        private var failedCatalogRequest: Pair<Int, String?>? = null

        init {
            telemetry.record(Event.SCREEN_VIEWED, Dimensions(screen = Screen.FIND))
            reload()
        }

        private fun launchAction(
            action: Action = Action.LOAD,
            block: suspend () -> Unit,
        ) {
            if (mutable.value.busy || (action != Action.LOAD && !mutable.value.interactionAllowed)) return
            mutable.update { it.copy(busy = true, error = "") }
            val job =
                viewModelScope.launch(start = CoroutineStart.LAZY) {
                    val began = System.nanoTime()
                    try {
                        block()
                        currentCoroutineContext().ensureActive()
                        telemetry.record(
                            Event.ACTION_COMPLETED,
                            Dimensions(
                                action = action,
                                outcome = Outcome.SUCCESS,
                                durationMs =
                                    (System.nanoTime() - began) / 1_000_000,
                            ),
                        )
                    } catch (e: CancellationException) {
                        if (action == Action.LISTEN) mutable.update { it.copy(sttStatus = "stopped") }
                        telemetry.record(Event.ACTION_COMPLETED, Dimensions(action = action, outcome = Outcome.CANCELLED))
                        throw e
                    } catch (e: Exception) {
                        telemetry.failure(e, Dimensions(action = action, durationMs = (System.nanoTime() - began) / 1_000_000))
                        mutable.update {
                            it.copy(
                                error =
                                    when (e) {
                                        is CampUserMessageException -> e.message.orEmpty()
                                        is CampRequestFailure -> e.localizedMessage()
                                        else -> CampText.get(R.string.camp_we_couldn_t_complete_the_request)
                                    },
                            )
                        }
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
            val transition = observationAdmission.onUxAvailabilityChanged(allowed)
            mutable.update {
                if (allowed) {
                    it.copy(interactionAllowed = true)
                } else {
                    it.copy(
                        interactionAllowed = false,
                        selectedSite = null,
                        cartOpen = false,
                        voiceOpen = false,
                        voicePreview = "",
                        previewTab = null,
                        previewScene = null,
                    )
                }
            }
            if (!allowed) actionJobs.cancelUserAction()
            reconcileVehicleObservation(transition)
        }

        /** Native observations require host START and a current UX allow signal. */
        fun onHostStart() {
            reconcileVehicleObservation(observationAdmission.onHostStart())
        }

        private fun reconcileVehicleObservation(transition: CampObservationAdmission.Transition) {
            when (transition) {
                CampObservationAdmission.Transition.START -> observationAdmission.capture()?.let(::startVehicleObservation)
                CampObservationAdmission.Transition.STOP -> stopVehicleObservation()
                CampObservationAdmission.Transition.NONE -> Unit
            }
        }

        private fun startVehicleObservation(token: CampObservationAdmission.Token) {
            if (!observationAdmission.isCurrent(token)) return
            try {
                bridge.start()
            } catch (error: Exception) {
                mutable.update {
                    it.copy(
                        error =
                            camp.room.app.CampText
                                .get(camp.room.app.R.string.camp_we_couldn_t_start_the_vehicle_connection, "")
                                .trim(),
                    )
                }
            }
            if (foregroundVehicleJob?.isActive == true) return
            foregroundVehicleJob =
                viewModelScope.launch {
                    while (isActive && observationAdmission.isCurrent(token)) {
                        if (!mutable.value.busy && mutable.value.data != null) {
                            try {
                                val current = readVehicle()
                                currentCoroutineContext().ensureActive()
                                if (!observationAdmission.isCurrent(token)) break
                                val assist = AssistMessages.status(bridge.assistStatus())
                                mutable.update {
                                    if (!observationAdmission.isCurrent(token)) return@update it
                                    it.copy(
                                        data = it.data?.copy(vehicle = current),
                                        route = it.route.takeIf { current.isConnected },
                                        navigationLaunched = it.navigationLaunched && current.isConnected,
                                        commands = if (current.isConnected) it.commands else emptyList(),
                                        commandStatus = it.commandStatus.takeIf { current.isConnected }.orEmpty(),
                                        arrivalStatus = assist.text("arrivalStatus"),
                                        arrivalMessage = assist.text("arrivalMessage"),
                                        ttsStatus = assist.text("ttsStatus"),
                                        sttStatus = assist.text("sttStatus"),
                                        ttsMessage = assist.text("ttsMessage"),
                                        sttMessage = assist.text("sttMessage"),
                                        ttsRetryable = assist.optBoolean("ttsRetryable", true),
                                        sttRetryable = assist.optBoolean("sttRetryable", true),
                                    )
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                mutable.update { state ->
                                    if (!observationAdmission.isCurrent(token)) return@update state
                                    val unknown = state.data?.vehicle?.disconnected()
                                    state.copy(
                                        data = unknown?.let { state.data?.copy(vehicle = it) },
                                        route = null,
                                        navigationLaunched = false,
                                        commands = emptyList(),
                                        commandStatus = "",
                                        arrivalStatus = "",
                                        arrivalMessage = "",
                                    )
                                }
                            }
                        }
                        delay(5_000)
                    }
                }
        }

        fun onHostStop() {
            actionJobs.cancelUserAction()
            reconcileVehicleObservation(observationAdmission.onHostStop())
        }

        private fun stopVehicleObservation() {
            tripReadRequests.invalidate()
            foregroundVehicleJob?.cancel()
            foregroundVehicleJob = null
            // A retained UI must not present the previous connection sample as current
            // during STOP or the gap before the first resumed SDK poll.
            mutable.update { state ->
                state.copy(
                    data = state.data?.let { it.copy(vehicle = it.vehicle.disconnected()) },
                    route = null,
                    navigationLaunched = false,
                    commands = emptyList(),
                    commandStatus = "",
                    arrivalStatus = "",
                    arrivalMessage = "",
                    vehicleObservationSession =
                        if (state.vehicleObservationSession == Long.MAX_VALUE) {
                            Long.MIN_VALUE
                        } else {
                            state.vehicleObservationSession + 1
                        },
                )
            }
            try {
                bridge.stop()
            } catch (error: Exception) {
                mutable.update {
                    it.copy(
                        error =
                            camp.room.app.CampText
                                .get(camp.room.app.R.string.camp_we_couldn_t_confirm_that_the_vehicle),
                    )
                }
            }
        }

        fun reload() =
            launchAction {
                invalidateCatalog()
                tripReadRequests.invalidate()
                val loaded = repository.bootstrap()
                val observation = observationAdmission.capture()
                val vehicle = readVehicle()
                mutable.update {
                    val boot = loaded.copy(vehicle = vehicleForPublication(observation, vehicle, it.data?.vehicle))
                    it.copy(
                        data = boot,
                        route = it.route.takeIf { boot.vehicle.isConnected },
                        navigationLaunched = it.navigationLaunched && boot.vehicle.isConnected,
                        commands = if (boot.vehicle.isConnected) it.commands else emptyList(),
                        commandStatus = it.commandStatus.takeIf { boot.vehicle.isConnected }.orEmpty(),
                        sites = boot.campsites,
                        checkIn = boot.trip?.checkIn ?: it.checkIn,
                        checkOut = boot.trip?.checkOut ?: it.checkOut,
                        guests = boot.trip?.guests ?: it.guests,
                        loading = false,
                    )
                }
                search()
                loadTrip()
            }

        private suspend fun refresh() {
            tripReadRequests.invalidate()
            val loaded = repository.bootstrap()
            val observation = observationAdmission.capture()
            val vehicle = readVehicle()
            mutable.update {
                val boot = loaded.copy(vehicle = vehicleForPublication(observation, vehicle, it.data?.vehicle))
                val next =
                    it.copy(
                        data = boot,
                        route = it.route.takeIf { boot.vehicle.isConnected },
                        navigationLaunched = it.navigationLaunched && boot.vehicle.isConnected,
                        commands = if (boot.vehicle.isConnected) it.commands else emptyList(),
                        commandStatus = it.commandStatus.takeIf { boot.vehicle.isConnected }.orEmpty(),
                    )
                if (quotedInput == quoteInput(next)) next else next.copy(quote = null)
            }
        }

        private fun unknownVehicle(): Vehicle = Vehicle.parse(JSONObject()).disconnected()

        private fun vehicleForPublication(
            observation: CampObservationAdmission.Token?,
            observed: Vehicle,
            previous: Vehicle?,
        ): Vehicle = observationAdmission.publication(observation, observed, previous ?: unknownVehicle(), unknownVehicle())

        private suspend fun readVehicle(): Vehicle {
            val token = observationAdmission.capture() ?: return unknownVehicle()
            val observed =
                try {
                    Vehicle.parse(sdk(SdkOperation.VEHICLE_STATE) { bridge.vehicle { observationAdmission.isCurrent(token) } })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    unknownVehicle()
                }
            // Rejecting a late result does not promise cancellation of a Binder call
            // that was already dispatched. Native IO/lifecycle ownership is unchanged.
            return if (observationAdmission.isCurrent(token)) observed else unknownVehicle()
        }

        private fun requireConnectedVehicle() {
            requireUserInput(
                mutable.value.data
                    ?.vehicle
                    ?.isConnected == true,
            ) {
                CampText.get(R.string.vehicle_control_connection_needed)
            }
        }

        private suspend fun loadTrip() {
            val trip = mutable.value.data?.trip ?: return
            val site = mutable.value.tripSite ?: return
            val request = tripReadRequests.begin(requireNotNull(CampTripReadKey.from(trip)))
            val observation = observationAdmission.capture()
            val weather = repository.weather(trip.campsiteId, trip.checkIn, trip.checkOut)
            if (!tripReadRequests.isCurrent(request, CampTripReadKey.from(mutable.value.data?.trip))) return
            mutable.update { current ->
                if (tripReadRequests.isCurrent(request, CampTripReadKey.from(current.data?.trip))) {
                    current.copy(weather = weather, route = null)
                } else {
                    current
                }
            }
            if (observation == null || !observationAdmission.isCurrent(observation)) return
            if (!tripReadRequests.isCurrent(request, CampTripReadKey.from(mutable.value.data?.trip))) return
            if (mutable.value.data
                    ?.vehicle
                    ?.isConnected != true
            ) {
                return
            }
            val route =
                try {
                    RoutePreview.parse(
                        sdk(SdkOperation.NAVIGATION_PREVIEW) {
                            bridge.navigationPreview(site) {
                                observationAdmission.isCurrent(observation) &&
                                    tripReadRequests.isCurrent(request, CampTripReadKey.from(mutable.value.data?.trip))
                            }
                        },
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            mutable.update { current ->
                if (observationAdmission.isCurrent(observation) &&
                    tripReadRequests.isCurrent(request, CampTripReadKey.from(current.data?.trip))
                ) {
                    current.copy(route = route.takeIf { current.data?.vehicle?.isConnected == true })
                } else {
                    current
                }
            }
        }

        fun tab(value: Int) {
            if (!mutable.value.interactionAllowed) return
            Screen.entries.getOrNull(value)?.let { telemetry.record(Event.SCREEN_VIEWED, Dimensions(screen = it)) }
            mutable.update { it.copy(tab = value, error = "") }
            if (value == 1) launchAction { loadTrip() }
        }

        fun dates(
            checkIn: String,
            checkOut: String,
        ) {
            if (!mutable.value.interactionAllowed || mutable.value.busy) return
            mutable.update { it.copy(checkIn = checkIn, checkOut = checkOut) }
            invalidateCatalog()
        }

        fun region(value: String) {
            if (!mutable.value.interactionAllowed || mutable.value.busy) return
            mutable.update { it.copy(region = value) }
            invalidateCatalog()
        }

        fun guests(value: Int) {
            if (!mutable.value.interactionAllowed || mutable.value.busy) return
            mutable.update { it.copy(guests = value.coerceIn(1, 8)) }
            invalidateCatalog()
        }

        fun carCamping() {
            if (!mutable.value.interactionAllowed || mutable.value.busy) return
            mutable.update { it.copy(carCamping = !it.carCamping) }
            invalidateCatalog()
        }

        fun facility(value: String) {
            if (!mutable.value.interactionAllowed || mutable.value.busy) return
            mutable.update {
                it.copy(
                    facilities =
                        if (value in it.facilities) it.facilities - value else it.facilities + value,
                )
            }
            invalidateCatalog()
        }

        fun clearError() {
            mutable.update { it.copy(error = "") }
        }

        fun clearNotice() {
            mutable.update { it.copy(notice = "") }
        }

        private fun invalidateCatalog() {
            catalogJob?.cancel()
            catalogHistory.reset()
            failedCatalogRequest = null
            mutable.update {
                it.copy(
                    sites = emptyList(),
                    catalogLoading = false,
                    catalogError = "",
                    catalogWarning = "",
                    catalogPageNumber = 0,
                    catalogTotal = 0,
                    catalogSourceTotal = null,
                    catalogHasNext = false,
                    catalogHasPrevious = false,
                    catalogNeedsSearch = true,
                )
            }
        }

        fun search() {
            val s = mutable.value
            if (s.catalogLoading) return
            val start = runCatching { LocalDate.parse(s.checkIn) }.getOrNull()
            val end = runCatching { LocalDate.parse(s.checkOut) }.getOrNull()
            if (start == null || end == null || end <= start) {
                mutable.update { it.copy(error = CampText.get(R.string.camp_choose_a_check_out_date_after_check)) }
                return
            }
            mutable.update { it.copy(error = "") }
            invalidateCatalog()
            requestCatalogPage(0, null)
        }

        private fun requestCatalogPage(
            number: Int,
            cursor: String?,
        ) {
            catalogHistory.select(number)?.let {
                showCatalogPage(number, it)
                return
            }
            val s = mutable.value
            val owner = catalogHistory.epoch
            val params =
                mutableMapOf(
                    "checkIn" to s.checkIn,
                    "checkOut" to s.checkOut,
                    "guests" to s.guests.toString(),
                )
            if (s.carCamping) params["carCamping"] = "true"
            if (s.region != "전체") params["region"] = s.region
            if (s.facilities.isNotEmpty()) params["facilities"] = s.facilities.joinToString(",")
            catalogJob?.cancel()
            mutable.update { it.copy(catalogLoading = true, catalogError = "", catalogNeedsSearch = false) }
            catalogJob =
                viewModelScope.launch {
                    try {
                        val page = repository.campsitePage(params, cursor)
                        currentCoroutineContext().ensureActive()
                        if (owner != catalogHistory.epoch) return@launch
                        if (!catalogHistory.accept(owner, number, page)) throw CatalogChangedException()
                        showCatalogPage(number, catalogHistory.page(number)!!)
                        failedCatalogRequest = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (changed: CatalogChangedException) {
                        if (owner == catalogHistory.epoch) {
                            mutable.update { it.copy(catalogLoading = false, notice = CampText.get(R.string.camp_catalog_changed)) }
                            search()
                        }
                    } catch (error: Exception) {
                        if (owner == catalogHistory.epoch) {
                            failedCatalogRequest = number to cursor
                            mutable.update {
                                it.copy(
                                    catalogError =
                                        if (error is CampRequestFailure) {
                                            error.localizedMessage()
                                        } else {
                                            CampText.get(
                                                R.string.camp_we_couldn_t_complete_the_request,
                                            )
                                        },
                                )
                            }
                        }
                    } finally {
                        if (owner == catalogHistory.epoch) mutable.update { it.copy(catalogLoading = false) }
                    }
                }
        }

        private fun showCatalogPage(
            number: Int,
            page: CatalogPage<Campsite>,
        ) {
            mutable.update {
                it.copy(
                    sites = page.items,
                    catalogPageNumber = number + 1,
                    catalogTotal = page.total,
                    catalogSourceTotal = page.sourceTotal,
                    catalogHasNext = page.nextCursor != null,
                    catalogHasPrevious = number > 0,
                    catalogLoading = false,
                    catalogError = "",
                    catalogWarning = page.warning,
                    catalogNeedsSearch = false,
                    locationSummary = "",
                    distances = emptyMap(),
                )
            }
        }

        fun nextCampsites() {
            if (mutable.value.catalogLoading || mutable.value.catalogNeedsSearch) return
            val current = catalogHistory.page(catalogHistory.index) ?: return
            current.nextCursor?.let { requestCatalogPage(catalogHistory.index + 1, it) }
        }

        fun previousCampsites() {
            if (!mutable.value.catalogLoading && catalogHistory.index > 0) requestCatalogPage(catalogHistory.index - 1, null)
        }

        fun retryCampsites() {
            if (!mutable.value.catalogLoading) failedCatalogRequest?.let { requestCatalogPage(it.first, it.second) }
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

        fun saveTrip() =
            launchAction(Action.SAVE_TRIP) {
                val s = mutable.value
                val site = s.selectedSite ?: return@launchAction
                repository.saveTrip(site.id, s.checkIn, s.checkOut, s.guests)
                refresh()
                mutable.update {
                    it.copy(
                        selectedSite = null,
                        tab = 1,
                        quote = null,
                        notice =
                            camp.room.app.CampText
                                .get(camp.room.app.R.string.camp_your_trip_is_saved_shall_we_get),
                    )
                }
                loadTrip()
            }

        fun navigation() =
            launchAction(Action.NAVIGATION) {
                requireConnectedVehicle()
                val site = mutable.value.tripSite ?: return@launchAction
                val result = AssistMessages.result("navigation", sdk(SdkOperation.NAVIGATION_START) { bridge.navigationStart(site) })
                refresh()
                mutable.update {
                    it.copy(
                        navigationLaunched = result.optBoolean("started") && it.data?.vehicle?.isConnected == true,
                        notice =
                            if (it.data?.vehicle?.isConnected != true) {
                                CampText.get(R.string.vehicle_control_connection_needed)
                            } else {
                                result.text("message", CampText.get(R.string.camp_the_request_was_sent_to_pleos_navigation))
                            },
                    )
                }
            }

        fun scene(value: String) {
            if (mutable.value.busy || !mutable.value.interactionAllowed) return
            mutable.update { it.copy(sceneId = value) }
        }

        fun applyScene() =
            launchAction(Action.APPLY_SCENE) {
                requireConnectedVehicle()
                val result = sdk(SdkOperation.VEHICLE_SCENE) { bridge.scene(mutable.value.sceneId) }
                if (!vehicleResult(result)) return@launchAction
                mutable.update {
                    it.copy(
                        notice =
                            if (result.text("status") == "completed") {
                                camp.room.app.CampText
                                    .get(camp.room.app.R.string.camp_cabin_commands_and_supported_observations_were_checked)
                            } else {
                                camp.room.app.CampText
                                    .get(camp.room.app.R.string.camp_please_check_the_remaining_items)
                            },
                    )
                }
            }

        private suspend fun vehicleResult(raw: JSONObject): Boolean {
            val result = AssistMessages.vehicle(raw)
            refresh()
            if (mutable.value.data
                    ?.vehicle
                    ?.isConnected != true
            ) {
                mutable.update {
                    it.copy(
                        commands = emptyList(),
                        commandStatus = "",
                        notice = CampText.get(R.string.vehicle_control_connection_needed),
                    )
                }
                return false
            }
            mutable.update {
                it.copy(
                    commands = result.objects("results").map(CommandResult::parse),
                    commandStatus = result.text("status"),
                )
            }
            return true
        }

        fun control(
            name: String,
            value: Any,
            zone: String? = null,
        ) = launchAction(Action.CONTROL) {
            requireConnectedVehicle()
            val body = JSONObject().put("control", name).put("value", value)
            if (zone != null) body.put("zone", zone)
            vehicleResult(sdk(SdkOperation.VEHICLE_CONTROL) { bridge.control(body) })
        }

        fun cart(open: Boolean) {
            if (open && !mutable.value.interactionAllowed) return
            if (open) telemetry.record(Event.SCREEN_VIEWED, Dimensions(screen = Screen.CART))
            mutable.update { it.copy(cartOpen = open, error = "") }
        }

        fun addProduct(id: String) =
            launchAction(Action.ADD_PRODUCT) {
                repository.addProduct(id)
                refresh()
                mutable.update {
                    it.copy(
                        order = null,
                        quote = null,
                        notice =
                            camp.room.app.CampText
                                .get(camp.room.app.R.string.camp_the_essential_was_added_to_your_cart),
                    )
                }
            }

        fun quantity(
            id: String,
            value: Int,
        ) = launchAction(Action.CART_QUANTITY) {
            repository.quantity(id, value)
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

        private fun deliveryDetails(s: CampUiState): DeliveryDetails {
            val trip = s.data?.trip
            return DeliveryDetails(s.destination, s.recipient, s.address, s.siteReference, trip?.campsiteId, trip?.checkIn, trip?.checkOut)
        }

        private fun quoteInput(s: CampUiState) = DeliveryQuoteSnapshot.capture(deliveryDetails(s), s.data?.cart)

        fun quote() =
            launchAction(Action.QUOTE) {
                val s = mutable.value
                requireUserInput(s.recipient.isNotBlank()) {
                    camp.room.app.CampText
                        .get(camp.room.app.R.string.camp_enter_the_recipient_s_name)
                }
                if (s.destination ==
                    "home"
                ) {
                    requireUserInput(
                        s.address.isNotBlank(),
                    ) {
                        camp.room.app.CampText
                            .get(camp.room.app.R.string.camp_enter_your_home_delivery_address)
                    }
                } else {
                    requireUserInput(s.siteReference.isNotBlank()) {
                        camp.room.app.CampText
                            .get(camp.room.app.R.string.camp_enter_a_pitch_or_reservation_reference)
                    }
                }
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

        fun checkout() =
            launchAction(Action.CHECKOUT) {
                throw CampUserMessageException(CampText.get(R.string.camp_checkout_example_context))
            }

        private suspend fun sdk(
            operation: SdkOperation,
            request: suspend () -> JSONObject,
        ): JSONObject {
            val began = System.nanoTime()
            try {
                val result = request()
                currentCoroutineContext().ensureActive()
                val outcome =
                    when (result.text("status")) {
                        "available", "completed", "recognized", "registered" -> Outcome.SUCCESS
                        "started", "inside", "entered", "exited" -> Outcome.STARTED
                        "blocked" -> Outcome.BLOCKED
                        "partial" -> Outcome.PARTIAL
                        "failed" -> Outcome.FAILED
                        "unavailable", "unsupported" -> Outcome.UNAVAILABLE
                        else -> Outcome.UNVERIFIED
                    }
                telemetry.record(
                    Event.SDK_COMPLETED,
                    Dimensions(sdk = operation, outcome = outcome, durationMs = (System.nanoTime() - began) / 1_000_000),
                )
                return result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                telemetry.failure(error, Dimensions(sdk = operation, durationMs = (System.nanoTime() - began) / 1_000_000))
                throw error
            }
        }

        fun setLanguage(tag: String) {
            bridge.setLanguage(tag)
            val assist = AssistMessages.status(bridge.assistStatus())
            mutable.update {
                it.copy(
                    error = "",
                    notice = "",
                    commands = emptyList(),
                    commandStatus = "",
                    voicePreview = "",
                    transcriptSource = "",
                    arrivalMessage = "",
                    ttsStatus = assist.text("ttsStatus"),
                    sttStatus = assist.text("sttStatus"),
                    ttsMessage = assist.text("ttsMessage"),
                    sttMessage = assist.text("sttMessage"),
                    ttsRetryable = assist.optBoolean("ttsRetryable", true),
                    sttRetryable = assist.optBoolean("sttRetryable", true),
                )
            }
        }

        fun permissionDenied(kind: String) {
            mutable.update {
                it.copy(
                    error =
                        camp.room.app.CampText
                            .get(camp.room.app.R.string.camp_permission_is_missing_allow_it_in_settings, kind),
                )
            }
        }

        fun nearby() =
            launchAction(Action.LOCATION) {
                val result = AssistMessages.result("location", sdk(SdkOperation.LOCATION) { bridge.location() })
                val latitude = result.nullNumber("latitude")
                val longitude = result.nullNumber("longitude")
                requireUserInput(
                    result.text("status") == "available" &&
                        latitude != null &&
                        longitude != null &&
                        latitude in -90.0..90.0 &&
                        longitude in -180.0..180.0,
                ) {
                    result.text(
                        "message",
                        camp.room.app.CampText
                            .get(camp.room.app.R.string.camp_we_couldn_t_read_the_last_known),
                    )
                }
                val distances =
                    mutable.value.sites
                        .mapNotNull { site ->
                            if (site.latitude == null || site.longitude == null) {
                                null
                            } else {
                                site.id to
                                    campDistanceKm(latitude, longitude, site.latitude, site.longitude)
                            }
                        }.toMap()
                mutable.update {
                    it.copy(
                        sites =
                            it.sites.sortedBy { site -> distances[site.id] ?: Double.POSITIVE_INFINITY },
                        distances = distances,
                        locationSummary =
                            CampText.get(
                                R.string.camp_nearby_current_page,
                                result.text("source", CampText.get(R.string.camp_device_location)),
                            ),
                        notice = CampText.get(R.string.camp_nearby_current_page_notice),
                    )
                }
            }

        fun arrivalWatch() =
            launchAction(Action.ARRIVAL_WATCH) {
                val site =
                    mutable.value.tripSite
                        ?: throw CampUserMessageException(
                            camp.room.app.CampText
                                .get(camp.room.app.R.string.camp_save_a_campsite_to_my_trip_first),
                        )
                val result = AssistMessages.result("arrival", sdk(SdkOperation.ARRIVAL_WATCH) { bridge.arrivalWatch(site) })
                mutable.update {
                    it.copy(
                        arrivalStatus = result.text("status"),
                        arrivalMessage = result.text("message"),
                        notice = result.text("message"),
                    )
                }
            }

        fun speakBriefing() =
            launchAction(Action.BRIEFING) {
                val s = mutable.value
                val site =
                    s.tripSite
                        ?: throw CampUserMessageException(
                            camp.room.app.CampText
                                .get(camp.room.app.R.string.camp_save_a_campsite_to_my_trip_first),
                        )
                val weather = s.weather ?: s.data?.weather
                val weatherText =
                    if (weather?.status == "available") {
                        camp.room.app.CampText.get(
                            camp.room.app.R.string.camp_the_forecast_is_the_overnight_low_is,
                            if (weather.demo) {
                                camp.room.app.CampText
                                    .get(camp.room.app.R.string.camp_this_is_a_demo_forecast)
                            } else {
                                ""
                            },
                            weather.summary,
                            number(weather.low),
                        )
                    } else {
                        camp.room.app.CampText
                            .get(camp.room.app.R.string.camp_the_forecast_for_these_dates_needs_checking)
                    }
                val soc = s.data?.vehicle?.soc
                val batteryText =
                    if (soc ==
                        null
                    ) {
                        camp.room.app.CampText
                            .get(camp.room.app.R.string.camp_the_current_battery_state_needs_checking)
                    } else {
                        camp.room.app.CampText
                            .get(camp.room.app.R.string.camp_the_current_battery_is_percent, number(soc))
                    }
                val text =
                    camp.room.app.CampText.get(
                        camp.room.app.R.string.camp_trip_briefing_check_in_is_and_check,
                        "",
                        site.name,
                        site.checkInTime,
                        site.checkOutTime,
                        weatherText,
                        batteryText,
                        site.rules.joinToString(". "),
                    )
                val result = AssistMessages.result("tts", sdk(SdkOperation.TTS) { bridge.speak(text) })
                mutable.update {
                    it.copy(
                        ttsStatus = result.text("status"),
                        ttsMessage = result.text("message"),
                        ttsRetryable = result.optBoolean("retryable", true),
                        notice =
                            result.text(
                                "message",
                                camp.room.app.CampText
                                    .get(camp.room.app.R.string.camp_the_spoken_briefing_request_was_sent),
                            ),
                    )
                }
            }

        fun voice(open: Boolean) {
            if (open && !mutable.value.interactionAllowed) return
            if (!open) actionJobs.cancelRecognition()
            if (open) telemetry.record(Event.SCREEN_VIEWED, Dimensions(screen = Screen.VOICE))
            mutable.update { it.copy(voiceOpen = open, error = "") }
        }

        fun transcript(value: String) {
            if (mutable.value.busy || !mutable.value.interactionAllowed) return
            mutable.update {
                it.copy(
                    transcript = value,
                    transcriptSource =
                        camp.room.app.CampText
                            .get(camp.room.app.R.string.camp_manually_typed_text),
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
                        sttMessage = result.text("message"),
                        sttRetryable = result.optBoolean("retryable", true),
                        transcript =
                            if (result.text("status") == "recognized") {
                                result.text("text")
                            } else {
                                it.transcript
                            },
                        transcriptSource =
                            if (result.text("status") ==
                                "recognized"
                            ) {
                                camp.room.app.CampText
                                    .get(camp.room.app.R.string.camp_speech_recognition_result)
                            } else {
                                camp.room.app.CampText
                                    .get(camp.room.app.R.string.camp_voice_unavailable_you_can_type_instead)
                            },
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
            val text =
                mutable.value.transcript
                    .lowercase(java.util.Locale.ROOT)
                    .filterNot { it.isWhitespace() }

            fun contains(vararg words: String) = words.any { text.contains(it) }
            val blocked = contains("결제", "구매", "주문", "payment", "pay", "purchase", "checkout", "order", "buy", "reserve")
            val scene =
                when {
                    contains("취침", "잠자", "sleep", "bedtime") -> "sleep"
                    contains("독서", "책읽", "따로쉬", "reading", "read", "split") -> "split"
                    contains("함께쉬", "같이쉬", "together", "relax") -> "together"
                    contains("짐정리", "unpack", "unload", "organize") -> "load"
                    contains("출발준비", "departure", "depart", "packup") -> "departure"
                    else -> null
                }
            val navigation = contains("길안내", "내비", "navigate", "navigation", "directions", "route")
            mutable.update {
                it.copy(
                    voicePreview =
                        when {
                            blocked -> {
                                camp.room.app.CampText
                                    .get(camp.room.app.R.string.camp_voice_doesn_t_place_orders_or_make)
                            }

                            scene != null -> {
                                if (it.data?.vehicle?.isConnected == true) {
                                    camp.room.app.CampText.get(
                                        camp.room.app.R.string.camp_preview_on_the_cabin_screen_apply_it,
                                        campScenes
                                            .first {
                                                it.id ==
                                                    scene
                                            }.name,
                                    )
                                } else {
                                    CampText.get(R.string.vehicle_scene_voice_walkthrough, campScenes.first { it.id == scene }.name)
                                }
                            }

                            navigation -> {
                                camp.room.app.CampText
                                    .get(camp.room.app.R.string.camp_open_the_travel_plan_in_my_trip)
                            }

                            else -> {
                                camp.room.app.CampText
                                    .get(camp.room.app.R.string.camp_try_a_supported_phrase_prepare_for_sleep)
                            }
                        },
                    previewTab =
                        if (blocked) {
                            null
                        } else if (scene != null) {
                            2
                        } else if (navigation) {
                            1
                        } else {
                            null
                        },
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
            observationAdmission.onHostStop()
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
