package camp.room.app

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThat
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TripPreparationTest {
    private fun sample(
        value: Double? = 70.0,
        at: Long? = 1_000,
        owner: String = "owner-a",
        epoch: Int = 1,
        current: Boolean = true,
    ) = PreparationSocSample(value, at, owner, epoch, current)

    private fun observe(
        sample: PreparationSocSample? = sample(),
        connected: Boolean = true,
        now: Long = 1_100,
        areas: List<VehicleAreaObservation> = emptyList(),
    ) = TripPreparationRules.observation(PreparationVehicleContext(connected, sample, areas), now)

    private fun tasks(
        record: TripPreparation = TripPreparation(),
        observed: PreparationObservation = observe(),
        food: Set<String> = emptySet(),
        guests: Int = 2,
    ) = TripPreparationRules.allTasks(record, observed, food, guests)

    private fun checked(
        record: TripPreparation,
        id: PreparationTaskId,
        observed: PreparationObservation = observe(),
        food: Set<String> = emptySet(),
        guests: Int = 2,
    ) = TripPreparationRules.check(record, tasks(record, observed, food, guests).first { it.id == id }, true, 100_000)

    @Test fun lowFreshSocChangesPreparationOrderButNeverCompletesAnyTask() {
        val observed = observe(sample(20.0))
        val ordered = TripPreparationRules.tasks(PreparationPhase.ARRIVAL, observed, emptySet(), emptySet(), 2)
        assertEquals(PreparationTaskId.ARRIVAL_POWER, ordered.first().id)
        assertEquals(PreparationBatteryAdvice.REVIEW_CHARGING, observed.batteryAdvice)
        assertEquals(20.0, observed.batteryPercent!!, 0.0)
        assertTrue(ordered.none { TripPreparationRules.checked(TripPreparation(), it) })
    }

    @Test fun freshNormalSocIsNotAPromiseOfRemainingStayDuration() {
        val observed = observe(sample(80.0))
        assertEquals(PreparationBatteryAdvice.REVIEW_POWER_USE, observed.batteryAdvice)
        assertEquals(
            PreparationTaskId.ARRIVAL_SITE,
            TripPreparationRules.tasks(PreparationPhase.ARRIVAL, observed, emptySet(), emptySet(), 2).first().id,
        )
        assertTrue(TripPreparation().checks.isEmpty())
    }

    @Test fun staleDisconnectedFutureInvalidAndUnownedSamplesFallBackToManual() {
        listOf(
            observe(sample(), now = 61_001),
            observe(sample(), connected = false),
            observe(sample(at = 1_101)),
            observe(sample(at = -1)),
            observe(sample(at = null)),
            observe(sample(value = Double.NaN)),
            observe(sample(value = 101.0)),
            observe(sample(value = null)),
            observe(sample(owner = "")),
            observe(sample(epoch = -1)),
            observe(sample(current = false)),
            observe(null),
        ).forEach { observed ->
            assertNull(observed.batteryPercent)
            assertEquals(PreparationBatteryAdvice.DIRECT_CHECK, observed.batteryAdvice)
        }
    }

    @Test fun manualCheckSurvivesRepeatedFreshPollOfSameOwnerEpochAndAdvice() {
        val first = observe(sample(70.0))
        val record = checked(TripPreparation(), PreparationTaskId.ARRIVAL_POWER, first)
        val later = observe(sample(69.0, at = 2_000), now = 2_001)
        assertEquals(record, TripPreparationRules.reconcile(record, tasks(record, later)))
    }

    @Test fun chargingAdviceChangeWithdrawsOnlyTheAffectedManualPowerChecks() {
        val original = checked(checked(TripPreparation(), PreparationTaskId.ARRIVAL_SITE), PreparationTaskId.ARRIVAL_POWER)
        val low = observe(sample(20.0))
        val revised = TripPreparationRules.reconcile(original, tasks(original, low))
        assertTrue(PreparationTaskId.ARRIVAL_SITE in revised.checks)
        assertFalse(PreparationTaskId.ARRIVAL_POWER in revised.checks)
        assertEquals(1, revised.checks.size)
    }

    @Test fun ownerAndEpochChangesCannotReuseAFormerVehicleAcknowledgement() {
        val record = checked(TripPreparation(), PreparationTaskId.STAY_POWER)
        listOf(observe(sample(owner = "owner-b")), observe(sample(epoch = 2))).forEach { newer ->
            assertFalse(PreparationTaskId.STAY_POWER in TripPreparationRules.reconcile(record, tasks(record, newer)).checks)
        }
    }

    @Test fun retainedHostStopStartCannotReuseChecksEvenIfAnActiveAdapterWasReused() {
        val record = checked(TripPreparation(), PreparationTaskId.STAY_POWER)
        // Recomposition may skip the intermediate disconnected UI. The host token
        // still invalidates the old acknowledgement when sample/adapter are reused.
        val resumed = TripPreparationRules.observation(PreparationVehicleContext(true, sample(), hostSession = 1), 1_100)
        assertFalse(PreparationTaskId.STAY_POWER in TripPreparationRules.reconcile(record, tasks(record, resumed)).checks)
        assertEquals(
            PreparationBatteryAdvice.DIRECT_CHECK,
            TripPreparationRules.observation(PreparationVehicleContext(true, sample(), hostSession = Long.MIN_VALUE), 1_100).batteryAdvice,
        )
    }

    @Test fun withdrawnAcknowledgementCannotResurrectAfterReconnect() {
        val first = checked(TripPreparation(), PreparationTaskId.ARRIVAL_POWER)
        val disconnected = TripPreparationRules.reconcile(first, tasks(first, observe(connected = false)))
        val resumed = TripPreparationRules.reconcile(disconnected, tasks(disconnected, observe()))
        assertTrue(resumed.checks.isEmpty())
    }

    @Test fun stageSelectionPreservesOtherStagesManualChecksButDoesNotAdvanceThem() {
        val first = checked(TripPreparation(), PreparationTaskId.ARRIVAL_SITE)
        val staying = first.copy(phase = PreparationPhase.STAY)
        val reconciled = TripPreparationRules.reconcile(staying, tasks(staying))
        assertEquals(PreparationPhase.STAY, reconciled.phase)
        assertTrue(PreparationTaskId.ARRIVAL_SITE in reconciled.checks)
        assertTrue(
            TripPreparationRules
                .tasks(PreparationPhase.STAY, observe(), emptySet(), emptySet(), 2)
                .none { TripPreparationRules.checked(reconciled, it) },
        )
    }

    private fun area(
        status: VehicleAreaStatus = VehicleAreaStatus.OBSERVED,
        sampledAt: Long? = 1_000,
    ) = VehicleAreaObservation(
        VehicleAreaKey(VehicleAreaKind.DOOR, "door:ROW_1_LEFT"),
        setOf("ROW_1_LEFT"),
        0,
        0,
        100,
        "READ",
        status,
        null,
        sampledAt,
        1_000,
        1,
    )

    @Test fun zeroPartialEmptyAndStalePositionValuesNeverCompleteDepartureWalkaround() {
        listOf(
            emptyList(),
            listOf(area()),
            listOf(area(VehicleAreaStatus.STALE)),
            listOf(area(sampledAt = 0)),
        ).forEach { areas ->
            val observed = observe(areas = areas, now = 20_000)
            val departure = TripPreparationRules.tasks(PreparationPhase.DEPARTURE, observed, emptySet(), emptySet(), 2)
            assertEquals(PreparationTaskId.DEPARTURE_OPENINGS, departure[1].id)
            assertTrue(departure.none { TripPreparationRules.checked(TripPreparation(), it) })
        }
        assertEquals(1, observe(areas = listOf(area())).freshPositionAreaCount)
        assertNull(area().closed)
    }

    @Test fun rawZeroDoesNotInvalidateOrCreateADifferentManualClosureClaim() {
        val record = checked(TripPreparation(), PreparationTaskId.DEPARTURE_OPENINGS)
        assertEquals(record, TripPreparationRules.reconcile(record, tasks(record, observe(areas = listOf(area())))))
        // This remains an explicit human walkaround; there is no "SDK closed" field in the preparation result.
        assertEquals("manual-door-window-walkaround", record.checks.getValue(PreparationTaskId.DEPARTURE_OPENINGS).requirement)
    }

    @Test fun supplyFoodOrGuestChangesRequireReviewOfOnlyTheirOwnTasks() {
        val base = checked(checked(TripPreparation(), PreparationTaskId.ARRIVAL_SUPPLIES), PreparationTaskId.STAY_MEAL)
        val withSupplies = base.copy(supplies = setOf(PreparationSupply.COOLER))
        val changedSupplies = TripPreparationRules.reconcile(withSupplies, tasks(withSupplies))
        assertFalse(PreparationTaskId.ARRIVAL_SUPPLIES in changedSupplies.checks)
        assertTrue(PreparationTaskId.STAY_MEAL in changedSupplies.checks)
        val changedFood = TripPreparationRules.reconcile(base, tasks(base, food = setOf("pork_belly")))
        assertFalse(PreparationTaskId.STAY_MEAL in changedFood.checks)
        assertTrue(PreparationTaskId.ARRIVAL_SUPPLIES in changedFood.checks)
        val changedGuests = TripPreparationRules.reconcile(base, tasks(base, guests = 4))
        assertTrue(changedGuests.checks.isEmpty())
    }

    @Test fun tripIdentitySeparatesPlaceAndDatesWithoutDelimiterCollisions() {
        val first = preparationTripToken(preparationTripKey("a|b", "2026-11-01", "2026-11-02"))
        assertEquals(first, preparationTripToken(preparationTripKey("a|b", "2026-11-01", "2026-11-02")))
        assertNotEquals(first, preparationTripToken(preparationTripKey("a", "2026-11-01", "2026-11-02")))
        assertNotEquals(first, preparationTripToken(preparationTripKey("a|b", "2026-11-02", "2026-11-03")))
    }

    @Test fun nextTripImportCarriesIntentionsButNoCompletionPhaseOrObservation() {
        val previous =
            checked(
                TripPreparation(phase = PreparationPhase.DEPARTURE, supplies = setOf(PreparationSupply.LANTERN)),
                PreparationTaskId.DEPARTURE_SITE,
            )
        val template = NextTripPreparation("old-trip", 2, previous.supplies, setOf("pork_belly"))
        val imported = TripPreparationRules.importNextTrip(template, "new-trip")!!
        assertEquals(setOf(PreparationSupply.LANTERN), imported.supplies)
        assertEquals(PreparationPhase.ARRIVAL, imported.phase)
        assertTrue(imported.checks.isEmpty())
        assertEquals(setOf("pork_belly"), template.foodIds)
        assertTrue(previous.checks.isNotEmpty())
        assertNull(TripPreparationRules.importNextTrip(template, "old-trip"))
    }

    @Test fun sameTripSerializationRestoresManualStateWithoutSdkValues() {
        val first =
            checked(
                TripPreparation(phase = PreparationPhase.STAY, supplies = setOf(PreparationSupply.LANTERN)),
                PreparationTaskId.STAY_ENVIRONMENT,
            )
        val encoded = Json.encodeToString(first)
        assertEquals(first, Json.decodeFromString<TripPreparation>(encoded))
        assertFalse(encoded.contains("batteryPercent"))
        assertFalse(encoded.contains("sampledAtElapsedMillis"))
        assertFalse(encoded.contains("positionAreas"))
    }

    @Test fun invalidManualTimestampNeverCountsAsACompletedTask() {
        val task = tasks().first()
        val record = TripPreparationRules.check(TripPreparation(), task, true, 0)
        assertFalse(TripPreparationRules.checked(record, task))
        val corrupt = record.copy(checks = mapOf(task.id to ManualPreparationCheck(task.requirement, -1)))
        assertTrue(TripPreparationRules.reconcile(corrupt, tasks()).checks.isEmpty())
    }

    @Test fun lateReconciliationCannotRestoreChecksAfterNextTripImport() {
        val checked = checked(TripPreparation(), PreparationTaskId.ARRIVAL_POWER)
        val old = TripPreparationSnapshot(1, checked)
        val desired = TripPreparationRules.reconcile(checked, tasks(checked, observe(connected = false)))
        val imported = TripPreparationSnapshot(2, TripPreparation(supplies = setOf(PreparationSupply.LANTERN)))
        assertNull(TripPreparationRules.reconcileIfCurrent(imported, old, desired))
        assertTrue(imported.record.checks.isEmpty())
    }

    @Test fun lateReconciliationCannotOverwritePhaseOrNewerManualAction() {
        val initial = TripPreparationSnapshot(1, checked(TripPreparation(), PreparationTaskId.ARRIVAL_POWER))
        val withdrawn = TripPreparationRules.reconcile(initial.record, tasks(initial.record, observe(connected = false)))
        val newPhase = TripPreparationSnapshot(2, initial.record.copy(phase = PreparationPhase.STAY))
        assertNull(TripPreparationRules.reconcileIfCurrent(newPhase, initial, withdrawn))
        // Even returning to equal values is a newer write (ABA).
        assertNull(TripPreparationRules.reconcileIfCurrent(initial.copy(revision = 3), initial, withdrawn))
        val applied = TripPreparationRules.reconcileIfCurrent(initial, initial, withdrawn)!!
        assertEquals(2, applied.revision)
        assertTrue(applied.record.checks.isEmpty())
        assertNull(TripPreparationRules.reconcileIfCurrent(applied, initial, withdrawn))
    }
}
