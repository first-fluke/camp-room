package camp.room.app

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable enum class PreparationPhase { ARRIVAL, STAY, DEPARTURE }

@Serializable enum class PreparationSupply { CHARGING_CABLE, COOLER, LANTERN, WASTE_BAGS }

@Serializable enum class PreparationTaskId {
    ARRIVAL_SITE,
    ARRIVAL_POWER,
    ARRIVAL_SUPPLIES,
    STAY_ENVIRONMENT,
    STAY_POWER,
    STAY_MEAL,
    DEPARTURE_CABLES,
    DEPARTURE_OPENINGS,
    DEPARTURE_SITE,
}

/** Current immutable adapter sample. A fresh vehicle() response is not a fresh SOC sample. */
data class PreparationSocSample(
    val value: Double?,
    val sampledAtElapsedMillis: Long?,
    val ownerId: String,
    val epoch: Int,
    val current: Boolean,
)

data class PreparationVehicleContext(
    val connected: Boolean,
    val soc: PreparationSocSample?,
    val positionAreas: List<VehicleAreaObservation> = emptyList(),
    val hostSession: Long = 0,
)

enum class PreparationBatteryAdvice { DIRECT_CHECK, REVIEW_CHARGING, REVIEW_POWER_USE }

data class PreparationObservation(
    val batteryPercent: Double?,
    val batteryAdvice: PreparationBatteryAdvice,
    val batteryRequirement: String,
    val freshPositionAreaCount: Int,
)

@Serializable
data class ManualPreparationCheck(
    val requirement: String,
    val checkedAtMillis: Long,
)

/** Contains user intentions/checks, never a persisted SDK reading or a safety verdict. */
@Serializable
data class TripPreparation(
    val phase: PreparationPhase = PreparationPhase.ARRIVAL,
    val supplies: Set<PreparationSupply> = emptySet(),
    val checks: Map<PreparationTaskId, ManualPreparationCheck> = emptyMap(),
)

@Serializable
data class TripPreparationSnapshot(
    val revision: Long = 0,
    val record: TripPreparation = TripPreparation(),
)

@Serializable
data class NextTripPreparation(
    val sourceTripToken: String,
    val guests: Int,
    val supplies: Set<PreparationSupply>,
    val foodIds: Set<String>,
)

data class PreparationTask(
    val id: PreparationTaskId,
    val requirement: String,
)

fun preparationTripKey(
    campsiteId: String,
    checkIn: String,
    checkOut: String,
): String = "${campsiteId.length}:$campsiteId|$checkIn|$checkOut"

fun preparationTripToken(key: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Read-only advice. No SDK ports, automatic completion, phase advance or vehicle setters. */
object TripPreparationRules {
    const val SOC_MAX_AGE_MILLIS = 60_000L
    const val POSITION_MAX_AGE_MILLIS = 15_000L
    const val CHARGING_REVIEW_PERCENT = 30.0

    fun observation(
        vehicle: PreparationVehicleContext,
        nowElapsedMillis: Long,
    ): PreparationObservation {
        val sample = vehicle.soc
        val value = sample?.value
        val fresh =
            vehicle.connected && vehicle.hostSession >= 0 && sample != null && sample.current && sample.ownerId.isNotBlank() &&
                sample.epoch >= 0 && value != null && value.isFinite() && value in 0.0..100.0 &&
                fresh(sample.sampledAtElapsedMillis, nowElapsedMillis, SOC_MAX_AGE_MILLIS)
        val advice =
            when {
                !fresh -> PreparationBatteryAdvice.DIRECT_CHECK
                value!! < CHARGING_REVIEW_PERCENT -> PreparationBatteryAdvice.REVIEW_CHARGING
                else -> PreparationBatteryAdvice.REVIEW_POWER_USE
            }
        val requirement =
            if (fresh) {
                "${sample!!.ownerId.length}:${sample.ownerId}|${sample.epoch}|${vehicle.hostSession}|${advice.name}"
            } else {
                "direct-battery-check|${vehicle.hostSession}"
            }
        val positionCount =
            if (!vehicle.connected) {
                0
            } else {
                vehicle.positionAreas.count { area ->
                    area.status == VehicleAreaStatus.OBSERVED && area.epoch >= 0 && area.members.isNotEmpty() &&
                        area.access in setOf("READ", "READ_WRITE") && area.minPosition != null && area.maxPosition != null &&
                        area.maxPosition >= area.minPosition && area.rawPosition != null &&
                        area.rawPosition in area.minPosition..area.maxPosition &&
                        fresh(area.sampledAtMillis, nowElapsedMillis, POSITION_MAX_AGE_MILLIS)
                }
            }
        return PreparationObservation(value.takeIf { fresh }, advice, requirement, positionCount)
    }

    private fun fresh(
        sampledAt: Long?,
        now: Long,
        maxAge: Long,
    ): Boolean = sampledAt != null && sampledAt >= 0 && now >= sampledAt && now - sampledAt <= maxAge

    fun tasks(
        phase: PreparationPhase,
        observation: PreparationObservation,
        supplies: Set<PreparationSupply>,
        foodIds: Set<String>,
        guests: Int,
    ): List<PreparationTask> {
        val supplyKey = supplies.sortedBy { it.name }.joinToString(",") { it.name }
        val foodKey = foodIds.sorted().joinToString(",")
        val powerKey = observation.batteryRequirement
        return when (phase) {
            PreparationPhase.ARRIVAL -> {
                listOf(
                    PreparationTask(PreparationTaskId.ARRIVAL_SITE, "site-walkaround"),
                    PreparationTask(PreparationTaskId.ARRIVAL_POWER, powerKey),
                    PreparationTask(PreparationTaskId.ARRIVAL_SUPPLIES, "$guests|$supplyKey"),
                ).let { tasks ->
                    if (observation.batteryAdvice == PreparationBatteryAdvice.REVIEW_CHARGING) {
                        listOf(tasks[1], tasks[0], tasks[2])
                    } else {
                        tasks
                    }
                }
            }

            PreparationPhase.STAY -> {
                listOf(
                    PreparationTask(PreparationTaskId.STAY_ENVIRONMENT, "manual-environment"),
                    PreparationTask(PreparationTaskId.STAY_POWER, powerKey),
                    PreparationTask(PreparationTaskId.STAY_MEAL, "$guests|$foodKey"),
                ).let { tasks ->
                    if (observation.batteryAdvice == PreparationBatteryAdvice.REVIEW_CHARGING) {
                        listOf(tasks[1], tasks[0], tasks[2])
                    } else {
                        tasks
                    }
                }
            }

            PreparationPhase.DEPARTURE -> {
                listOf(
                    PreparationTask(PreparationTaskId.DEPARTURE_CABLES, "manual-cables-and-cargo"),
                    PreparationTask(PreparationTaskId.DEPARTURE_OPENINGS, "manual-door-window-walkaround"),
                    PreparationTask(PreparationTaskId.DEPARTURE_SITE, "manual-belongings-and-waste"),
                )
            }
        }
    }

    fun allTasks(
        record: TripPreparation,
        observation: PreparationObservation,
        foodIds: Set<String>,
        guests: Int,
    ) = PreparationPhase.entries.flatMap { tasks(it, observation, record.supplies, foodIds, guests) }

    /** Withdraw an obsolete manual acknowledgement; an SDK update can never create one. */
    fun reconcile(
        record: TripPreparation,
        tasks: List<PreparationTask>,
    ): TripPreparation {
        val requirements = tasks.associate { it.id to it.requirement }
        return record.copy(
            checks =
                record.checks.filter { (id, check) ->
                    check.checkedAtMillis > 0 && requirements[id] == check.requirement
                },
        )
    }

    /** A delayed UI effect may withdraw old checks only while its input is still owned. */
    fun reconcileIfCurrent(
        current: TripPreparationSnapshot,
        expected: TripPreparationSnapshot,
        desired: TripPreparation,
    ): TripPreparationSnapshot? =
        if (current != expected || desired == expected.record || current.revision == Long.MAX_VALUE) {
            null
        } else {
            TripPreparationSnapshot(current.revision + 1, desired)
        }

    fun checked(
        record: TripPreparation,
        task: PreparationTask,
    ): Boolean = record.checks[task.id]?.let { it.checkedAtMillis > 0 && it.requirement == task.requirement } == true

    fun check(
        record: TripPreparation,
        task: PreparationTask,
        checked: Boolean,
        nowWallMillis: Long,
    ): TripPreparation =
        record.copy(
            checks =
                if (checked && nowWallMillis > 0) {
                    record.checks + (task.id to ManualPreparationCheck(task.requirement, nowWallMillis))
                } else {
                    record.checks - task.id
                },
        )

    /** Intention import is explicit and cannot carry completion, stage or observed values. */
    fun importNextTrip(
        template: NextTripPreparation,
        destinationTripToken: String,
    ): TripPreparation? =
        if (template.sourceTripToken == destinationTripToken || template.sourceTripToken.isBlank() || destinationTripToken.isBlank()) {
            null
        } else {
            TripPreparation(supplies = template.supplies)
        }
}
