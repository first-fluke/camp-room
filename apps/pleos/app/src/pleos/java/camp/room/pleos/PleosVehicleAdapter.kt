package camp.room.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import android.os.Handler
import android.os.Looper
import camp.room.app.SdkCleanupQuarantine
import camp.room.app.SdkReleaseLease
import camp.room.app.SdkRuntimeTrace
import camp.room.app.SdkVehicleReadFailure
import camp.room.app.SdkVehicleReadFailureClass
import camp.room.app.SocCapabilityReader
import camp.room.app.SocObservedSample
import camp.room.app.SocSnapshotReader
import camp.room.app.VehicleListenerCleanup
import camp.room.app.VehicleScheduledTask
import camp.room.app.VehicleSdkIo
import kotlin.math.abs

/** Real-SDK flavor. Host supplies checked mappings and marshals callbacks to UI dispatcher. */
class PleosVehicleAdapter(
    private val vehicle: Vehicle,
    private val seatAreas: (Control, String) -> SeatArea?,
    private val readSeatAreas: (Control, String) -> SeatArea?,
    private val windowAreas: (String) -> WindowArea?,
    private val temperatureUnit: TemperatureUnit,
    private val lightSwitch: (Boolean) -> LightSwitch,
    private val lightState: (LightState) -> Boolean?,
    private val capabilities: CapabilityRegistry,
    private val motion: MotionProvider,
    private val onSoc: (Int?) -> Unit,
    private val onError: (Exception) -> Unit,
) : VehiclePort {
    private companion object {
        // Retired ViewModels cannot be the only owners of failed native cleanup.
        val releaseQuarantine = SdkCleanupQuarantine<Vehicle>()
    }

    private val stateLock = Any()
    private val traceOwner = SdkRuntimeTrace.newOwner("vehicle_adapter")

    private fun traceVehicle(
        event: String,
        vararg fields: Pair<String, Any?>,
    ) = SdkRuntimeTrace.ownedEvent("Vehicle", event, traceOwner, *fields)

    private var started = false
    private var initialized = false
    private val nativeRelease = SdkReleaseLease(vehicle, stateLock)
    private var closed = false
    private var generation = 0
    private var registeredSoc: EvBatteryStateOfChargeListener? = null
    private val socTimeoutHandler = Handler(Looper.getMainLooper())

    // Only a listener reserved by the serial vendor lane can enter this set. Every
    // new vendor entry drains it before registering the next generation.
    private val pendingUnregister = VehicleListenerCleanup<EvBatteryStateOfChargeListener>()
    private val io = VehicleSdkIo(onFailure = onError)
    private val requests = mutableMapOf<Pair<Control, String>, Long>()
    private val socSnapshots =
        SocSnapshotReader<EvBatteryStateOfChargeListener>(
            lock = stateLock,
            read = { done, failed -> vehicle.getEvBattery().getEvBatteryStateOfCharge(done, failed) },
            onValue = onSoc,
            onError = onError,
            trace = { event, sample ->
                traceVehicle(
                    event,
                    "adapterInstance" to System.identityHashCode(this),
                    "epoch" to sample.epoch,
                    "requestNamespace" to "soc_snapshot",
                    "requestId" to sample.requestId,
                    "reason" to sample.reason,
                    "eventRevision" to sample.eventRevision,
                    "current" to sample.current,
                    "accepted" to sample.accepted,
                    "supersededBySubscription" to sample.supersededByEvent,
                    "value" to sample.value,
                    "errorClass" to sample.errorClass,
                    "onMainThread" to (Looper.myLooper() == Looper.getMainLooper()),
                )
            },
        )
    private val socReader =
        SocCapabilityReader(
            check = { done, failed ->
                vehicle.getEvBattery().checkEvBatteryStateOfChargeCapabilities(
                    { config -> done(config.accessType.name in setOf("READ", "READ_WRITE")) },
                    failed,
                )
            },
            schedule = { delay, action ->
                val task = Runnable(action)
                socTimeoutHandler.postDelayed(task, delay)
                VehicleScheduledTask { socTimeoutHandler.removeCallbacks(task) }
            },
            dispatch = { task ->
                io.request("soc.retry") {
                    drainCleanup()
                    task()
                }
            },
            onReadable = { epoch -> followup("soc.register") { registerSoc(epoch) } },
            onError = { epoch, error -> synchronized(stateLock) { if (active(epoch)) onError(error) } },
            isActive = { epoch -> synchronized(stateLock) { active(epoch) } },
            isRetryable = { error ->
                error !is SecurityException && error !is UnsupportedOperationException &&
                    SdkVehicleReadFailure.from(error).failureClass !in
                    setOf(
                        SdkVehicleReadFailureClass.SECURITY_PERMISSION,
                        SdkVehicleReadFailureClass.UNSUPPORTED_PROPERTY,
                        SdkVehicleReadFailureClass.UNSUPPORTED_VALUE_TYPE,
                        SdkVehicleReadFailureClass.UNSUPPORTED_AREA,
                        SdkVehicleReadFailureClass.DRIVING_RESTRICTED,
                    )
            },
            traceContext = { event, epoch, attempt, delay ->
                traceVehicle(
                    "soc.$event",
                    "epoch" to epoch,
                    "requestId" to attempt.toLong(),
                    "requestNamespace" to "soc_capability",
                    "attempt" to attempt,
                    "retryDelayMs" to delay,
                )
            },
        )

    /** Atomic original sample; no host poll is allowed to refresh its age or relabel its epoch. */
    internal fun socObservation(): Pair<String, SocObservedSample>? =
        synchronized(stateLock) {
            if (!active(generation)) {
                null
            } else {
                socSnapshots.currentSample(generation, registeredSoc)?.let { traceOwner.id to it }
            }
        }

    private fun <T> vendor(block: () -> T): T =
        io.run {
            drainCleanup()
            block()
        }

    private fun followup(
        key: String,
        block: () -> Unit,
    ) {
        if (!io.request(key) {
                drainCleanup()
                block()
            }
        ) {
            onError(IllegalStateException("Vehicle follow-up capacity unavailable"))
        }
    }

    override fun start() =
        vendor {
            check(releaseQuarantine.drain(vehicle)) { "Previous vehicle release is unresolved" }
            check(!pendingUnregister.hasPending()) { "Previous SOC listener cleanup is unresolved" }
            val session =
                synchronized(stateLock) {
                    check(!closed) { "Adapter is closed" }
                    val reused = started
                    if (!reused) {
                        started = true
                        generation++
                    }
                    Triple(generation, reused, registeredSoc)
                }
            val (epoch, reused, listener) = session
            traceVehicle(
                "soc.start",
                "adapterInstance" to System.identityHashCode(this),
                "epoch" to epoch,
                "reused" to reused,
                "listenerPresent" to (listener != null),
                "onMainThread" to (Looper.myLooper() == Looper.getMainLooper()),
            )
            try {
                socSnapshots.startOrRefresh(epoch, reused, listener) {
                    if (!synchronized(stateLock) { initialized }) {
                        // initialize can bind before throwing; close must still release that exact object.
                        nativeRelease.markAcquired()
                        vehicle.initialize()
                        synchronized(stateLock) { initialized = true }
                    }
                    if (synchronized(stateLock) { active(epoch) }) {
                        socReader.start(epoch)
                    } else {
                        drainCleanup()
                    }
                }
            } catch (error: Exception) {
                synchronized(stateLock) {
                    if (active(epoch)) invalidateLocked()
                }
                nativeRelease.requestRelease()
                requestReleaseCleanup()
                throw error
            }
        }

    /** Runs on the vendor lane; reserve identity before registration can synchronously callback. */
    private fun registerSoc(epoch: Int) {
        val subscriptionId = SdkRuntimeTrace.newOwner("soc_subscription").id
        val listener =
            object : EvBatteryStateOfChargeListener {
                override fun onFailed(e: Exception) {
                    synchronized(stateLock) {
                        val accepted = active(epoch) && registeredSoc === this
                        traceVehicle(
                            "soc.subscription.error",
                            "epoch" to epoch,
                            "subscriptionId" to subscriptionId,
                            "errorClass" to e.javaClass.simpleName,
                            "current" to accepted,
                        )
                        if (accepted) onError(e)
                    }
                }

                override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
                    synchronized(stateLock) {
                        val accepted = active(epoch) && registeredSoc === this
                        traceVehicle(
                            "soc.subscription",
                            "epoch" to epoch,
                            "subscriptionId" to subscriptionId,
                            "value" to value,
                            "current" to accepted,
                        )
                        if (accepted) socSnapshots.onEvent(epoch, this, value)
                    }
                }
            }
        synchronized(stateLock) {
            if (!active(epoch) || registeredSoc != null || pendingUnregister.hasPending()) return
            registeredSoc = listener
            socSnapshots.bind(epoch, listener)
        }
        try {
            vehicle.getEvBattery().registerEvBatteryStateOfCharge(listener)
            if (!synchronized(stateLock) { active(epoch) && registeredSoc === listener }) {
                drainCleanup()
                return
            }
            socSnapshots.refresh(epoch, listener, "initial")
        } catch (error: Exception) {
            synchronized(stateLock) {
                if (registeredSoc === listener) {
                    registeredSoc = null
                    socSnapshots.invalidate(epoch, listener)
                }
                pendingUnregister.retain(listener)
            }
            drainCleanup()
            if (synchronized(stateLock) { active(epoch) }) onError(error)
        }
    }

    /** No vendor IPC, and no waiting for vendor IPC, while holding stateLock. */
    private fun invalidateLocked() {
        started = false
        generation++
        registeredSoc?.let { pendingUnregister.retain(it) }
        registeredSoc = null
        socSnapshots.invalidate()
        requests.clear()
    }

    /** Called only on the serial vendor lane; cleanup cannot be dropped by coalescing. */
    private fun drainCleanup() {
        pendingUnregister.drain({ listener -> vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(listener) }, onError)
        if (pendingUnregister.hasPending()) return
        val released =
            nativeRelease.releaseIfRequested { exactVehicle ->
                traceVehicle(
                    "release.attempt",
                    "adapterInstance" to System.identityHashCode(this),
                    "vehicleInstance" to System.identityHashCode(vehicle),
                    "epoch" to synchronized(stateLock) { generation },
                    "complete" to false,
                    "onMainThread" to (Looper.myLooper() == Looper.getMainLooper()),
                )
                exactVehicle.release()
            }
        if (released) {
            synchronized(stateLock) { initialized = false }
            traceVehicle(
                "release.returned",
                "adapterInstance" to System.identityHashCode(this),
                "vehicleInstance" to System.identityHashCode(vehicle),
                "epoch" to synchronized(stateLock) { generation },
                "complete" to true,
                "onMainThread" to (Looper.myLooper() == Looper.getMainLooper()),
            )
        }
    }

    override fun stop() {
        val epoch = synchronized(stateLock) { generation.also { invalidateLocked() } }
        socReader.stop(epoch)
        vendor { Unit }
    }

    override fun close() {
        synchronized(stateLock) {
            if (!closed) {
                closed = true
                invalidateLocked()
            }
        }
        nativeRelease.requestRelease()
        socReader.stop()
        requestReleaseCleanup()
    }

    private fun requestReleaseCleanup() {
        traceVehicle(
            "release.request",
            "adapterInstance" to System.identityHashCode(this),
            "vehicleInstance" to System.identityHashCode(vehicle),
            "epoch" to synchronized(stateLock) { generation },
            "pending" to true,
            "onMainThread" to (Looper.myLooper() == Looper.getMainLooper()),
        )
        releaseQuarantine.retain(
            vehicle,
            this,
            cleanup = {
                io.run {
                    drainCleanup()
                    check(!pendingUnregister.hasPending()) { "SOC listener cleanup is unresolved" }
                    check(!nativeRelease.hasResource()) { "Vehicle release is unresolved" }
                }
            },
            retry = { delay, task ->
                check(
                    socTimeoutHandler.postDelayed({
                        if (!io.request("vehicle.release.retry", task)) {
                            onError(IllegalStateException("Vehicle cleanup retry capacity unavailable; cleanup retained"))
                        }
                    }, delay),
                ) { "Vehicle cleanup timer unavailable; cleanup retained" }
            },
            onFailure = { error ->
                traceVehicle(
                    "release.pending",
                    "adapterInstance" to System.identityHashCode(this),
                    "vehicleInstance" to System.identityHashCode(vehicle),
                    "errorClass" to error.javaClass.simpleName,
                    "epoch" to synchronized(stateLock) { generation },
                    "complete" to false,
                    "retained" to true,
                    "onMainThread" to (Looper.myLooper() == Looper.getMainLooper()),
                )
                onError(error)
            },
            // Automatic retries remain capped. A later explicit close or same-object
            // admission can rearm exhausted SOC cleanup instead of staying poisoned forever.
            onExplicitRetry = pendingUnregister::rearmExhausted,
        )
        releaseQuarantine.drain(vehicle)
    }

    private fun active(epoch: Int) = started && !closed && generation == epoch

    override fun apply(
        command: Command,
        onUpdate: (CommandUpdate) -> Unit,
    ) {
        val cap = capabilities.get(command.control, command.areaId)
        val integral = command.control in setOf(Control.READING_LIGHT, Control.AMBIENT_BRIGHTNESS, Control.SMART_GLASS, Control.SEAT_MEMORY)
        val numericValid =
            command.value.isFinite() &&
                (!integral || (command.value % 1.0 == 0.0 && command.value >= Int.MIN_VALUE && command.value <= Int.MAX_VALUE)) &&
                command.value.toFloat().isFinite() &&
                (command.control != Control.READING_LIGHT || command.value in setOf(0.0, 1.0))
        if (cap == null || !numericValid || !cap.allows(command.value)) {
            onUpdate(CommandUpdate(command, Confirmation.BLOCKED, message = "Unsupported permission, area or value"))
            return
        }
        if (motion.current() != MotionState.PARKED) {
            onUpdate(CommandUpdate(command, Confirmation.BLOCKED, message = "Trusted parked signal required"))
            return
        }
        val epoch =
            synchronized(stateLock) {
                if (!started || closed) {
                    onUpdate(CommandUpdate(command, Confirmation.BLOCKED, message = "Vehicle session unavailable"))
                    return
                }
                generation
            }
        // Capture the separately capability-checked getter object before any async callback.
        val originalReadSeat = readSeatAreas(command.control, command.areaId)
        val key = command.control to command.areaId
        val token = synchronized(stateLock) { ((requests[key] ?: 0L) + 1).also { requests[key] = it } }

        fun current() = active(epoch) && requests[key] == token

        fun guarded(block: () -> Unit): Unit =
            synchronized(stateLock) {
                if (current()) block()
                Unit
            }

        fun emit(update: CommandUpdate) = guarded { onUpdate(update) }

        fun failed(error: Exception) = emit(CommandUpdate(command, Confirmation.FAILED, message = error.message))

        fun observed(value: Double?) =
            emit(
                CommandUpdate(
                    command,
                    if (value == null) {
                        Confirmation.ACKNOWLEDGED
                    } else if (abs(value - command.value) <= cap.tolerance) {
                        Confirmation.OBSERVED
                    } else {
                        Confirmation.OBSERVED_MISMATCH
                    },
                    value,
                    if (value != null &&
                        abs(value - command.value) > cap.tolerance
                    ) {
                        "Observed value differs from requested target"
                    } else {
                        null
                    },
                ),
            )

        fun seat(): SeatArea? =
            seatAreas(command.control, command.areaId).also {
                if (it == null) emit(CommandUpdate(command, Confirmation.BLOCKED, message = "Seat area unavailable"))
            }

        fun confirm(block: () -> Unit) =
            followup("confirm:${command.control}:${command.areaId}") {
                if (synchronized(stateLock) { current() } && motion.current() == MotionState.PARKED) block()
            }
        vendor {
            if (!synchronized(stateLock) { current() } || motion.current() != MotionState.PARKED) {
                emit(CommandUpdate(command, Confirmation.BLOCKED, message = "Trusted parked signal required"))
                return@vendor
            }
            when (command.control) {
                Control.CURRENT_TEMPERATURE -> {
                    emit(CommandUpdate(command, Confirmation.BLOCKED, message = "Measured temperature is read-only"))
                }

                Control.TARGET_TEMPERATURE -> {
                    val area = seat() ?: return@vendor
                    vehicle.getHVAC().setHvacTemperature(
                        area,
                        command.value.toFloat(),
                        temperatureUnit,
                        { _, _ ->
                            confirm {
                                emit(CommandUpdate(command, Confirmation.ACKNOWLEDGED))
                                if (cap.readable) {
                                    vehicle.getHVAC().getHvacTemperature(
                                        area,
                                        temperatureUnit,
                                        { observed(it?.toDouble()) },
                                        ::failed,
                                    )
                                }
                            }
                        },
                        ::failed,
                    )
                }

                Control.READING_LIGHT -> {
                    val area = seat() ?: return@vendor
                    vehicle.getLight().setReadingLightSwitch(
                        area,
                        lightSwitch(command.value != 0.0),
                        { _ ->
                            confirm {
                                emit(CommandUpdate(command, Confirmation.ACKNOWLEDGED))
                                // readable comes from checkReadingLightStateCapabilities, independently of switch capability.
                                if (cap.readable && originalReadSeat != null) {
                                    vehicle.getLight().getCurrentReadingLightState(
                                        originalReadSeat,
                                        { observed(it?.let(lightState)?.let { state -> if (state) 1.0 else 0.0 }) },
                                        ::failed,
                                    )
                                } else if (cap.readable) {
                                    emit(CommandUpdate(command, Confirmation.SENT_UNVERIFIED, message = "Getter area unavailable"))
                                }
                            }
                        },
                        ::failed,
                    )
                }

                Control.AMBIENT_BRIGHTNESS -> {
                    val area = seat() ?: return@vendor
                    vehicle.getLight().setAmbientLightBrightness(
                        area,
                        command.value.toInt(),
                        { _, _ ->
                            confirm {
                                emit(CommandUpdate(command, Confirmation.ACKNOWLEDGED))
                                if (cap.readable) vehicle.getLight().getAmbientLightBrightness(area, { observed(it?.toDouble()) }, ::failed)
                            }
                        },
                        ::failed,
                    )
                }

                Control.SMART_GLASS -> {
                    val area = windowAreas(command.areaId)
                    if (area ==
                        null
                    ) {
                        emit(CommandUpdate(command, Confirmation.BLOCKED, message = "Window area unavailable"))
                        return@vendor
                    }
                    vehicle.getWindow().setSmartGlassLevel(
                        area,
                        command.value.toInt(),
                        { _, _ ->
                            confirm {
                                emit(CommandUpdate(command, Confirmation.ACKNOWLEDGED))
                                if (cap.readable) {
                                    vehicle.getWindow().getCurrentSmartGlassLevel(
                                        area,
                                        { observed(it?.toDouble()) },
                                        ::failed,
                                    )
                                }
                            }
                        },
                        ::failed,
                    )
                }

                Control.SEAT_MEMORY -> {
                    val area = seat() ?: return@vendor
                    emit(CommandUpdate(command, Confirmation.SENT_UNVERIFIED))
                    vehicle.getSeat().selectSeatMemory(area, command.value.toInt(), ::failed)
                }

                Control.CHARGE_PERCENT -> {
                    vehicle.getEvBattery().setEvChargePercentLimit(
                        command.value.toFloat(),
                        { value ->
                            emit(
                                CommandUpdate(
                                    command,
                                    Confirmation.ACKNOWLEDGED,
                                    message =
                                        if (value ==
                                            null
                                        ) {
                                            "Callback received; accepted value unknown; no target getter"
                                        } else {
                                            "Setter returned target; no separate target getter"
                                        },
                                ),
                            )
                        },
                        ::failed,
                    )
                }
            }
        }
    }
}
