package camp.room.pleos

import kotlin.math.abs
import kotlin.math.round

enum class MotionState { PARKED, MOVING, UNKNOWN }
enum class Zone { FRONT, REAR }
enum class Control { CURRENT_TEMPERATURE, TARGET_TEMPERATURE, READING_LIGHT, AMBIENT_BRIGHTNESS, SMART_GLASS, SEAT_MEMORY, CHARGE_PERCENT }
enum class Confirmation { OBSERVED, OBSERVED_MISMATCH, ACKNOWLEDGED, SENT_UNVERIFIED, FAILED, BLOCKED }
data class Command(val control: Control, val zone: Zone, val value: Double, val areaId: String)
data class CommandUpdate(val command: Command, val confirmation: Confirmation,
    val observed: Double? = null, val message: String? = null)
/** readable is the getter's separately checked capability, not the setter AccessType. */
data class Capability(val readable: Boolean, val writable: Boolean,
    val allowedValues: Set<Double>? = null, val minimum: Double? = null,
    val maximum: Double? = null, val increment: Double? = null,
    val tolerance: Double = 0.001) {
    fun allows(value: Double): Boolean {
        if (!writable || !value.isFinite() || (allowedValues?.contains(value) == false)) return false
        if (minimum != null && value < minimum || maximum != null && value > maximum) return false
        if (increment != null) {
            if (!increment.isFinite() || increment <= 0) return false
            val steps = (value - (minimum ?: 0.0)) / increment
            if (abs(steps - round(steps)) > 0.000001) return false
        }
        return true
    }
}
/** Populate from actual check*Capabilities + permissions, area, temperature increment. */
fun interface CapabilityRegistry { fun get(control: Control, areaId: String): Capability? }
/** Must come from trusted host motion state; brake/cruise mode is insufficient. */
fun interface MotionProvider { fun current(): MotionState }
interface VehiclePort : AutoCloseable {
    fun start()
    fun stop()
    fun apply(command: Command, onUpdate: (CommandUpdate) -> Unit)
}
