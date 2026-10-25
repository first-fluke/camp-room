package camp.room.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import kotlin.math.abs

/** Real-SDK flavor. Host supplies checked mappings and marshals callbacks to UI dispatcher. */
class PleosVehicleAdapter(
    private val vehicle: Vehicle,
    private val seatAreas: (Control, Zone) -> SeatArea?,
    private val windowAreas: Map<Zone, WindowArea>,
    private val temperatureUnit: TemperatureUnit,
    private val lightSwitch: (Boolean) -> LightSwitch,
    private val lightState: (LightState) -> Boolean?,
    private val capabilities: CapabilityRegistry,
    private val motion: MotionProvider,
    private val onSoc: (Int?) -> Unit,
    private val onError: (Exception) -> Unit,
) : VehiclePort {
    private var started = false
    private var initialized = false
    private var closed = false
    private var generation = 0
    private var liveSocSeen = false
    private var registeredSoc: EvBatteryStateOfChargeListener? = null
    private val requests = mutableMapOf<Pair<Control, Zone>, Long>()

    @Synchronized override fun start() {
        check(!closed) { "Adapter is closed" }
        if (started) return
        if (!initialized) { vehicle.initialize(); initialized = true }
        started = true
        liveSocSeen = false
        val epoch = ++generation
        val listener = object : EvBatteryStateOfChargeListener {
            override fun onFailed(e: Exception) {
                synchronized(this@PleosVehicleAdapter) { if (active(epoch)) onError(e) }
            }
            override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
                synchronized(this@PleosVehicleAdapter) {
                    if (active(epoch)) { liveSocSeen = true; onSoc(value) }
                }
            }
        }
        registeredSoc = listener
        try {
            vehicle.getEvBattery().registerEvBatteryStateOfCharge(listener)
            vehicle.getEvBattery().getEvBatteryStateOfCharge(
                { value -> synchronized(this) { if (active(epoch) && !liveSocSeen) onSoc(value) } },
                { error -> synchronized(this) { if (active(epoch)) onError(error) } },
            )
        } catch (error: Exception) {
            started = false; generation++
            try { vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(listener) }
            finally { registeredSoc = null }
            throw error
        }
    }

    @Synchronized override fun stop() {
        started = false; generation++
        val listener = registeredSoc ?: return
        try { vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(listener) }
        finally { registeredSoc = null }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true; started = false; generation++
        try {
            registeredSoc?.let { vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(it) }
        } finally {
            registeredSoc = null
            if (initialized) { initialized = false; vehicle.release() }
        }
    }

    private fun active(epoch: Int) = started && !closed && generation == epoch

    @Synchronized override fun apply(command: Command, onUpdate: (CommandUpdate) -> Unit) {
        val cap = capabilities.get(command.control, command.zone)
        val integral = command.control in setOf(Control.READING_LIGHT, Control.AMBIENT_BRIGHTNESS, Control.SMART_GLASS, Control.SEAT_MEMORY)
        val numericValid = command.value.isFinite() &&
            (!integral || command.value % 1.0 == 0.0 && command.value >= Int.MIN_VALUE && command.value <= Int.MAX_VALUE) &&
            command.value.toFloat().isFinite() &&
            (command.control != Control.READING_LIGHT || command.value in setOf(0.0, 1.0))
        if (!started || closed || cap == null || !numericValid || !cap.allows(command.value)) {
            onUpdate(CommandUpdate(command, Confirmation.BLOCKED, message = "Unsupported permission, area or value")); return
        }
        if (motion.current() != MotionState.PARKED) {
            onUpdate(CommandUpdate(command, Confirmation.BLOCKED, message = "Trusted parked signal required")); return
        }
        val epoch = generation
        val key = command.control to command.zone
        val token = (requests[key] ?: 0L) + 1
        requests[key] = token
        fun current() = active(epoch) && requests[key] == token
        fun guarded(block: () -> Unit): Unit = synchronized(this) { if (current()) block(); Unit }
        fun emit(update: CommandUpdate) = guarded { onUpdate(update) }
        fun failed(error: Exception) = emit(CommandUpdate(command, Confirmation.FAILED, message = error.message))
        fun observed(value: Double?) = emit(CommandUpdate(command,
            if (value == null) Confirmation.ACKNOWLEDGED
            else if (abs(value - command.value) <= cap.tolerance) Confirmation.OBSERVED
            else Confirmation.OBSERVED_MISMATCH, value,
            if (value != null && abs(value - command.value) > cap.tolerance) "Observed value differs from requested target" else null))
        fun seat(): SeatArea? = seatAreas(command.control, command.zone).also {
            if (it == null) emit(CommandUpdate(command, Confirmation.BLOCKED, message = "Seat area unavailable"))
        }
        when (command.control) {
            Control.TARGET_TEMPERATURE -> {
                val area = seat() ?: return
                vehicle.getHVAC().setHvacTemperature(area, command.value.toFloat(), temperatureUnit,
                    { _, _ -> guarded {
                        emit(CommandUpdate(command, Confirmation.ACKNOWLEDGED))
                        if (cap.readable) vehicle.getHVAC().getHvacTemperature(area, temperatureUnit, { observed(it?.toDouble()) }, ::failed)
                    } }, ::failed)
            }
            Control.READING_LIGHT -> {
                val area = seat() ?: return
                vehicle.getLight().setReadingLightSwitch(area, lightSwitch(command.value != 0.0),
                    { _ -> guarded {
                        emit(CommandUpdate(command, Confirmation.ACKNOWLEDGED))
                        // readable comes from checkReadingLightStateCapabilities, independently of switch capability.
                        if (cap.readable) vehicle.getLight().getCurrentReadingLightState(area,
                            { observed(it?.let(lightState)?.let { state -> if (state) 1.0 else 0.0 }) }, ::failed)
                    } }, ::failed)
            }
            Control.AMBIENT_BRIGHTNESS -> {
                val area = seat() ?: return
                vehicle.getLight().setAmbientLightBrightness(area, command.value.toInt(),
                    { _, _ -> guarded {
                        emit(CommandUpdate(command, Confirmation.ACKNOWLEDGED))
                        if (cap.readable) vehicle.getLight().getAmbientLightBrightness(area, { observed(it?.toDouble()) }, ::failed)
                    } }, ::failed)
            }
            Control.SMART_GLASS -> {
                val area = windowAreas[command.zone]
                if (area == null) { emit(CommandUpdate(command, Confirmation.BLOCKED, message = "Window area unavailable")); return }
                vehicle.getWindow().setSmartGlassLevel(area, command.value.toInt(),
                    { _, _ -> guarded {
                        emit(CommandUpdate(command, Confirmation.ACKNOWLEDGED))
                        if (cap.readable) vehicle.getWindow().getCurrentSmartGlassLevel(area, { observed(it?.toDouble()) }, ::failed)
                    } }, ::failed)
            }
            Control.SEAT_MEMORY -> {
                val area = seat() ?: return
                emit(CommandUpdate(command, Confirmation.SENT_UNVERIFIED))
                vehicle.getSeat().selectSeatMemory(area, command.value.toInt(), ::failed)
            }
            Control.CHARGE_PERCENT -> vehicle.getEvBattery().setEvChargePercentLimit(command.value.toFloat(),
                { value -> emit(CommandUpdate(command, Confirmation.ACKNOWLEDGED, message =
                    if (value == null) "Callback received; accepted value unknown; no target getter" else "Setter returned target; no separate target getter")) }, ::failed)
        }
    }
}
