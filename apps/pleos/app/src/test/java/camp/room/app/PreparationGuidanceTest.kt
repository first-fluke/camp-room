package camp.room.app

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparationGuidanceTest {
    private val now = 100_000L

    private fun soc(
        value: Double = 20.0,
        at: Long = 99_000,
    ) = PreparationSocSample(value, at, "owner-a", 3, true)

    private fun area(
        raw: Int = 0,
        status: VehicleAreaStatus = VehicleAreaStatus.OBSERVED,
        reason: String? = null,
        at: Long = 99_000,
        min: Int? = 0,
        max: Int? = 100,
        access: String = "READ",
        closed: Boolean? = null,
    ) = VehicleAreaObservation(
        VehicleAreaKey(VehicleAreaKind.WINDOW, "window:ROW_1_LEFT"),
        setOf("ROW_1_LEFT"),
        raw,
        min,
        max,
        access,
        status,
        reason,
        at,
        at,
        3,
        closed,
    )

    private fun vehicle(
        sample: PreparationSocSample? = soc(),
        areas: List<VehicleAreaObservation> = listOf(area()),
        connected: Boolean = true,
        hostSession: Long = 7,
    ) = PreparationVehicleContext(connected, sample, areas, hostSession)

    @Test fun lowBatteryExplainsDifferentArrivalAndStayActionsWithOriginalEvidence() {
        val expected =
            listOf(
                Triple(PreparationPhase.ARRIVAL, PreparationGuidanceReason.ARRIVAL_CHARGING, PreparationTaskId.ARRIVAL_POWER),
                Triple(PreparationPhase.STAY, PreparationGuidanceReason.STAY_CHARGING, PreparationTaskId.STAY_POWER),
            )
        expected.forEach { (phase, reason, action) ->
            val guidance = TripPreparationPolicy.evaluate(phase, vehicle(), now)
            assertEquals(reason, guidance.reason)
            assertEquals(action, guidance.manualTask)
            assertEquals(PreparationEvidenceSource.VEHICLE_BATTERY, guidance.evidence.source)
            assertEquals(PreparationEvidenceStatus.CURRENT, guidance.evidence.status)
            assertEquals(20.0, guidance.evidence.batteryPercent!!, 0.0)
            assertEquals(99_000L, guidance.evidence.sampledAtElapsedMillis)
            assertEquals(1_000L, guidance.evidence.ageMillis)
            val observation = TripPreparationRules.observation(vehicle(), now)
            assertEquals(action, TripPreparationRules.tasks(phase, observation, emptySet(), emptySet(), 2).first().id)
        }
    }

    @Test fun normalSocAndItsStaleCopyProduceDifferentGuidanceAtTheExistingBoundary() {
        val sample = soc(80.0, 1_000)
        val current = TripPreparationPolicy.evaluate(PreparationPhase.STAY, vehicle(sample), 61_000)
        val stale = TripPreparationPolicy.evaluate(PreparationPhase.STAY, vehicle(sample), 61_001)
        assertEquals(PreparationEvidenceStatus.CURRENT, current.evidence.status)
        assertEquals(PreparationGuidanceReason.STAY_POWER, current.reason)
        assertEquals(PreparationEvidenceStatus.STALE, stale.evidence.status)
        assertEquals(PreparationGuidanceReason.POWER_DIRECT, stale.reason)
        assertEquals(current.evidence.batteryPercent, stale.evidence.batteryPercent)
        assertEquals(1_000L, stale.evidence.sampledAtElapsedMillis)
        assertEquals(60_001L, stale.evidence.ageMillis)
    }

    @Test fun missingSocDoesNotInventAnErrorOrTimeoutThatTheModelDoesNotProvide() {
        val missing = TripPreparationPolicy.evaluate(PreparationPhase.ARRIVAL, vehicle(sample = null), now)
        val disconnected = TripPreparationPolicy.evaluate(PreparationPhase.ARRIVAL, vehicle(connected = false), now)
        assertEquals(PreparationEvidenceStatus.UNAVAILABLE, missing.evidence.status)
        assertEquals(PreparationEvidenceStatus.DISCONNECTED, disconnected.evidence.status)
        assertEquals(PreparationGuidanceReason.POWER_DIRECT, missing.reason)
        assertEquals(PreparationGuidanceReason.POWER_DIRECT, disconnected.reason)
        assertNull(missing.evidence.batteryPercent)
    }

    @Test fun theSameRawPositionHasDifferentCurrentErrorTimeoutAndStaleEvidence() {
        val cases =
            listOf(
                area() to PreparationEvidenceStatus.CURRENT,
                area(status = VehicleAreaStatus.ERROR, reason = "read_failed") to PreparationEvidenceStatus.ERROR,
                area(status = VehicleAreaStatus.ERROR, reason = "timeout") to PreparationEvidenceStatus.TIMEOUT,
                area(status = VehicleAreaStatus.ERROR, reason = "backoff:timeout") to PreparationEvidenceStatus.TIMEOUT,
                area(status = VehicleAreaStatus.ERROR, reason = "not_timeout") to PreparationEvidenceStatus.ERROR,
                area(status = VehicleAreaStatus.STALE, reason = "stale_sample") to PreparationEvidenceStatus.STALE,
                area(at = now - TripPreparationRules.POSITION_MAX_AGE_MILLIS - 1) to PreparationEvidenceStatus.STALE,
                area(status = VehicleAreaStatus.UNAVAILABLE) to PreparationEvidenceStatus.UNAVAILABLE,
            )
        cases.forEach { (input, expected) ->
            val result = TripPreparationPolicy.evaluate(PreparationPhase.DEPARTURE, vehicle(areas = listOf(input)), now)
            assertEquals(expected, result.evidence.status)
            assertEquals(PreparationEvidenceSource.VEHICLE_OPENINGS, result.evidence.source)
            assertEquals(PreparationTaskId.DEPARTURE_OPENINGS, result.manualTask)
            assertEquals(
                0,
                result.evidence.areas
                    .single()
                    .rawPosition,
            )
            assertEquals(
                input.sampledAtMillis,
                result.evidence.areas
                    .single()
                    .sampledAtElapsedMillis,
            )
            assertEquals(
                input.status,
                result.evidence.areas
                    .single()
                    .reportedStatus,
            )
            assertEquals(
                input.reason,
                result.evidence.areas
                    .single()
                    .reportedReason,
            )
            assertEquals(
                if (expected ==
                    PreparationEvidenceStatus.CURRENT
                ) {
                    PreparationGuidanceReason.DEPARTURE_POSITION_RECORDED
                } else {
                    PreparationGuidanceReason.DEPARTURE_DIRECT
                },
                result.reason,
            )
        }
    }

    @Test fun invalidBoundsUnownedClockAndUnreadablePositionsCannotBecomeCurrentEvidence() {
        val invalid =
            listOf(
                area(min = null),
                area(max = null),
                area(min = 0, max = 0),
                area(raw = 101),
                area(access = "WRITE"),
                area(at = -1),
                area(at = now + 1),
                area().copy(epoch = -1),
                area().copy(members = emptySet()),
                area().copy(key = VehicleAreaKey(VehicleAreaKind.WINDOW, "")),
            )
        invalid.forEach { input ->
            assertEquals(
                PreparationEvidenceStatus.INVALID,
                TripPreparationPolicy.evaluate(PreparationPhase.DEPARTURE, vehicle(areas = listOf(input)), now).evidence.status,
            )
        }
        assertEquals(
            PreparationEvidenceStatus.INVALID,
            TripPreparationPolicy.evaluate(PreparationPhase.DEPARTURE, vehicle(hostSession = Long.MIN_VALUE), now).evidence.status,
        )
        assertEquals(0, TripPreparationRules.observation(vehicle(areas = listOf(area(min = 0, max = 0))), now).freshPositionAreaCount)
    }

    @Test fun anOldOrFailedMemberDoesNotBecomeAnAllCurrentAggregate() {
        val current = area()
        val old = area(at = now - 15_001).copy(key = VehicleAreaKey(VehicleAreaKind.DOOR, "door:ROW_1_LEFT"))
        val failed = old.copy(status = VehicleAreaStatus.ERROR, reason = "timeout")
        assertEquals(
            PreparationEvidenceStatus.STALE,
            TripPreparationPolicy.evaluate(PreparationPhase.DEPARTURE, vehicle(areas = listOf(current, old)), now).evidence.status,
        )
        val result = TripPreparationPolicy.evaluate(PreparationPhase.DEPARTURE, vehicle(areas = listOf(current, failed)), now)
        assertEquals(PreparationEvidenceStatus.TIMEOUT, result.evidence.status)
        assertEquals(1, result.evidence.areas.count { it.status == PreparationEvidenceStatus.CURRENT })
        assertEquals(PreparationGuidanceReason.DEPARTURE_DIRECT, result.reason)
    }

    @Test fun rawEndpointsAndEvenALegacyClosedFlagCannotCreateASafetyVerdict() {
        for (raw in listOf(0, 100)) {
            for (legacyClosed in listOf(null, true, false)) {
                val input = vehicle(areas = listOf(area(raw = raw, closed = legacyClosed)))
                val result = TripPreparationPolicy.evaluate(PreparationPhase.DEPARTURE, input, now)
                assertEquals(PreparationGuidanceReason.DEPARTURE_POSITION_RECORDED, result.reason)
                assertEquals(PreparationTaskId.DEPARTURE_OPENINGS, result.manualTask)
                val record = TripPreparation(phase = PreparationPhase.DEPARTURE)
                assertTrue(
                    TripPreparationRules
                        .reconcile(
                            record,
                            TripPreparationRules.allTasks(record, TripPreparationRules.observation(input, now), emptySet(), 2),
                        ).checks
                        .isEmpty(),
                )
            }
        }
    }

    @Test fun allPhasesAndObservationStatesLeaveManualCompletionAndPhaseUserOwned() {
        val contexts =
            listOf(
                vehicle(),
                vehicle(sample = soc(80.0)),
                vehicle(sample = null),
                vehicle(connected = false),
                vehicle(areas = listOf(area(status = VehicleAreaStatus.ERROR, reason = "timeout"))),
                vehicle(areas = listOf(area(status = VehicleAreaStatus.STALE))),
                vehicle(hostSession = -1),
            )
        for (phase in PreparationPhase.entries) {
            for (input in contexts) {
                val original = TripPreparation(phase = phase, supplies = setOf(PreparationSupply.LANTERN))
                val before = Json.encodeToString(original)
                val guidance = TripPreparationPolicy.evaluate(phase, input, now)
                val observed = TripPreparationRules.observation(input, now)
                assertTrue(
                    TripPreparationRules.tasks(phase, observed, original.supplies, emptySet(), 2).any { it.id == guidance.manualTask },
                )
                val after = TripPreparationRules.reconcile(original, TripPreparationRules.allTasks(original, observed, emptySet(), 2))
                assertEquals(before, Json.encodeToString(after))
                assertTrue(after.checks.isEmpty())
            }
        }
    }

    @Test fun positionFreshnessUsesTheExistingProductPolicyAndOriginalClock() {
        val input = vehicle(areas = listOf(area(at = 1_000)))
        assertEquals(
            PreparationEvidenceStatus.CURRENT,
            TripPreparationPolicy.evaluate(PreparationPhase.DEPARTURE, input, 16_000).evidence.status,
        )
        val expired = TripPreparationPolicy.evaluate(PreparationPhase.DEPARTURE, input, 16_001)
        assertEquals(PreparationEvidenceStatus.STALE, expired.evidence.status)
        assertEquals(1_000L, expired.evidence.sampledAtElapsedMillis)
        assertEquals(15_001L, expired.evidence.ageMillis)
        assertEquals(expired, TripPreparationPolicy.evaluate(PreparationPhase.DEPARTURE, input, 16_001))
    }

    @Test fun goldenArrivalStayDepartureAndNextTripReuseOnlyExplicitSelections() {
        val observed = TripPreparationRules.observation(vehicle(), now)
        var record = TripPreparation(supplies = setOf(PreparationSupply.LANTERN))
        val snapshots = mutableListOf<String>()

        fun snapshot() {
            snapshots +=
                "${record.phase}|${record.checks.keys.map { it.name }.sorted().joinToString(
                    ",",
                )}|${record.supplies.map { it.name }.sorted().joinToString(",")}"
        }

        fun check(id: PreparationTaskId) {
            val task = TripPreparationRules.allTasks(record, observed, setOf("pork_belly"), 2).single { it.id == id }
            record = TripPreparationRules.check(record, task, true, 100_000)
        }
        check(PreparationTaskId.ARRIVAL_SITE)
        snapshot()
        record = record.copy(phase = PreparationPhase.STAY)
        check(PreparationTaskId.STAY_ENVIRONMENT)
        snapshot()
        record = record.copy(phase = PreparationPhase.DEPARTURE)
        check(PreparationTaskId.DEPARTURE_SITE)
        snapshot()
        val template = NextTripPreparation("old-trip", 2, record.supplies, setOf("pork_belly"))
        val imported = TripPreparationRules.importNextTrip(template, "new-trip")!!
        record = imported
        snapshot()
        assertEquals(
            listOf(
                "ARRIVAL|ARRIVAL_SITE|LANTERN",
                "STAY|ARRIVAL_SITE,STAY_ENVIRONMENT|LANTERN",
                "DEPARTURE|ARRIVAL_SITE,DEPARTURE_SITE,STAY_ENVIRONMENT|LANTERN",
                "ARRIVAL||LANTERN",
            ),
            snapshots,
        )
        assertEquals(setOf("pork_belly"), template.foodIds)
        val encoded = Json.encodeToString(imported)
        listOf("old-trip", "sourceTripToken", "checkedAtMillis", "batteryPercent", "sampledAt", "owner-a", "rawPosition").forEach {
            assertFalse(encoded.contains(it))
        }
        assertTrue(imported.checks.isEmpty())
        assertEquals(PreparationPhase.ARRIVAL, imported.phase)
    }

    @Test fun everyPhaseAndSupportedEvidenceStateTargetsExactlyOneExistingManualRow() {
        val contexts =
            listOf(
                vehicle(),
                vehicle(sample = soc(80.0)),
                vehicle(sample = null),
                vehicle(sample = soc().copy(current = false)),
                vehicle(sample = soc().copy(value = null)),
                vehicle(sample = soc(Double.NaN)),
                vehicle(sample = soc(at = now - TripPreparationRules.SOC_MAX_AGE_MILLIS - 1)),
                vehicle(sample = soc(at = now + 1)),
                vehicle(connected = false),
                vehicle(hostSession = -1),
                vehicle(areas = emptyList()),
                vehicle(areas = listOf(area(status = VehicleAreaStatus.UNAVAILABLE))),
                vehicle(areas = listOf(area(status = VehicleAreaStatus.ERROR, reason = "read_failed"))),
                vehicle(areas = listOf(area(status = VehicleAreaStatus.ERROR, reason = "timeout"))),
                vehicle(areas = listOf(area(at = now - TripPreparationRules.POSITION_MAX_AGE_MILLIS - 1))),
                vehicle(areas = listOf(area(min = null))),
                vehicle(areas = listOf(area(), area(status = VehicleAreaStatus.ERROR, reason = "timeout"))),
            )
        val expectedTarget =
            mapOf(
                PreparationPhase.ARRIVAL to PreparationTaskId.ARRIVAL_POWER,
                PreparationPhase.STAY to PreparationTaskId.STAY_POWER,
                PreparationPhase.DEPARTURE to PreparationTaskId.DEPARTURE_OPENINGS,
            )
        PreparationPhase.entries.forEach { phase ->
            val covered = mutableSetOf<PreparationEvidenceStatus>()
            contexts.forEachIndexed { index, input ->
                val guidance = TripPreparationPolicy.evaluate(phase, input, now)
                covered += guidance.evidence.status
                val observation = TripPreparationRules.observation(input, now)
                val tasks = TripPreparationRules.tasks(phase, observation, emptySet(), emptySet(), 2)
                val label = "$phase case $index ${guidance.evidence.status}"
                assertEquals(label, expectedTarget.getValue(phase), guidance.manualTask)
                assertEquals(label, 1, tasks.count { it.id == guidance.manualTask })
                assertEquals(label, 3, tasks.size)
                // Related-task emphasis must use the existing order, even when charging is not first.
                val expectedOrder =
                    when (phase) {
                        PreparationPhase.ARRIVAL -> {
                            if (observation.batteryAdvice == PreparationBatteryAdvice.REVIEW_CHARGING) {
                                listOf(PreparationTaskId.ARRIVAL_POWER, PreparationTaskId.ARRIVAL_SITE, PreparationTaskId.ARRIVAL_SUPPLIES)
                            } else {
                                listOf(PreparationTaskId.ARRIVAL_SITE, PreparationTaskId.ARRIVAL_POWER, PreparationTaskId.ARRIVAL_SUPPLIES)
                            }
                        }

                        PreparationPhase.STAY -> {
                            if (observation.batteryAdvice == PreparationBatteryAdvice.REVIEW_CHARGING) {
                                listOf(PreparationTaskId.STAY_POWER, PreparationTaskId.STAY_ENVIRONMENT, PreparationTaskId.STAY_MEAL)
                            } else {
                                listOf(PreparationTaskId.STAY_ENVIRONMENT, PreparationTaskId.STAY_POWER, PreparationTaskId.STAY_MEAL)
                            }
                        }

                        PreparationPhase.DEPARTURE -> {
                            listOf(
                                PreparationTaskId.DEPARTURE_CABLES,
                                PreparationTaskId.DEPARTURE_OPENINGS,
                                PreparationTaskId.DEPARTURE_SITE,
                            )
                        }
                    }
                assertEquals(label, expectedOrder, tasks.map { it.id })
                val original = TripPreparation(phase = phase)
                assertTrue(label, TripPreparationRules.reconcile(original, tasks).checks.isEmpty())
            }
            val expectedStates =
                if (phase == PreparationPhase.DEPARTURE) {
                    PreparationEvidenceStatus.entries.toSet()
                } else {
                    setOf(
                        PreparationEvidenceStatus.CURRENT,
                        PreparationEvidenceStatus.UNAVAILABLE,
                        PreparationEvidenceStatus.DISCONNECTED,
                        PreparationEvidenceStatus.STALE,
                        PreparationEvidenceStatus.INVALID,
                    )
                }
            assertEquals("Evidence coverage for $phase", expectedStates, covered)
        }
    }
}
