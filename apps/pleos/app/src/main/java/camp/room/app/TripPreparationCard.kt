@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package camp.room.app

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Preparation is user-owned. This component has no vehicle-control or cloud telemetry port. */
@Composable
fun TripPreparationCard(
    trip: Trip,
    vehicle: Vehicle,
    enabled: Boolean,
    hostSession: Long,
    onCabin: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val tripKey = preparationTripKey(trip.campsiteId, trip.checkIn, trip.checkOut)
    val tripToken = remember(tripKey) { preparationTripToken(tripKey) }
    val store = remember(context) { TripPreparationStore(context) }
    val foodPreferences = remember(context) { foodPrepPreferences(context) }
    var snapshot by remember(tripKey) { mutableStateOf(store.snapshot(tripKey)) }
    val record = snapshot.record
    var template by remember(tripKey) { mutableStateOf(store.template()) }
    var usage by remember { mutableStateOf(store.usage()) }
    var foodIds by remember(tripKey) { mutableStateOf(selectedFoodPreparation(foodPreferences, tripKey)) }
    var expanded by remember(tripKey) { mutableStateOf(false) }
    var message by remember(tripKey) { mutableStateOf(0) }
    var elapsed by remember { mutableStateOf(System.nanoTime() / 1_000_000) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                elapsed = System.nanoTime() / 1_000_000
                delay(1_000)
            }
        }
    }
    DisposableEffect(store, foodPreferences, tripKey) {
        val prepListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                snapshot = store.snapshot(tripKey)
                template = store.template()
                usage = store.usage()
            }
        val foodListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                foodIds = selectedFoodPreparation(foodPreferences, tripKey)
            }
        store.preferences.registerOnSharedPreferenceChangeListener(prepListener)
        foodPreferences.registerOnSharedPreferenceChangeListener(foodListener)
        snapshot = store.snapshot(tripKey)
        template = store.template()
        usage = store.usage()
        foodIds = selectedFoodPreparation(foodPreferences, tripKey)
        onDispose {
            store.preferences.unregisterOnSharedPreferenceChangeListener(prepListener)
            foodPreferences.unregisterOnSharedPreferenceChangeListener(foodListener)
        }
    }
    val observed =
        TripPreparationRules.observation(
            PreparationVehicleContext(vehicle.isConnected, vehicle.socObservation, vehicle.positionAreas, hostSession),
            elapsed,
        )
    val allTasks = TripPreparationRules.allTasks(record, observed, foodIds, trip.guests)
    val currentRecord = TripPreparationRules.reconcile(record, allTasks)
    val tasks = TripPreparationRules.tasks(record.phase, observed, record.supplies, foodIds, trip.guests)
    val inputSnapshot = snapshot
    LaunchedEffect(tripKey, inputSnapshot, currentRecord) {
        if (inputSnapshot.record != currentRecord) {
            store.reconcile(tripKey, inputSnapshot, currentRecord)?.let { applied ->
                // The preference listener may already have delivered a newer user write.
                if (snapshot.revision <= applied.revision) snapshot = applied
            }
        }
    }

    fun update(transform: (TripPreparation) -> TripPreparation) {
        snapshot = store.update(tripKey, transform)
    }

    fun count(action: PreparationUsageAction) {
        val next = usage.record(action)
        if (next != usage) {
            usage = next
            store.saveUsage(next)
        }
    }

    CampSurface(Modifier.fillMaxWidth().testTag("trip-preparation")) {
        Text(tr(R.string.prep_title), fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Text(tr(R.string.prep_manual_explanation), fontSize = 22.sp, color = CampPalette.Muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PreparationPhase.entries.forEach { phase ->
                FilterChip(
                    selected = record.phase == phase,
                    onClick = {
                        if (snapshot.record.phase !=
                            phase
                        ) {
                            update { it.copy(phase = phase) }
                            count(PreparationUsageAction.PHASE_CHANGED)
                        }
                    },
                    enabled = enabled,
                    label = { Text(tr(phase.label()), fontSize = 22.sp) },
                    modifier = Modifier.heightIn(min = 52.dp).testTag("prep-phase-${phase.name.lowercase()}"),
                )
            }
        }
        Text(
            if (observed.batteryPercent != null) {
                tr(R.string.prep_vehicle_battery, number(observed.batteryPercent))
            } else {
                tr(R.string.prep_battery_direct)
            },
            fontSize = 22.sp,
            color = CampPalette.Muted,
            modifier = Modifier.testTag("prep-observation"),
        )
        Text(
            tr(R.string.prep_checked_count, tasks.count { TripPreparationRules.checked(currentRecord, it) }, tasks.size),
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
        )
        tasks.forEach { task ->
            PrepCheckRow(
                text = tr(task.label(observed.batteryAdvice)),
                checked = TripPreparationRules.checked(currentRecord, task),
                enabled = enabled,
                tag = "prep-check-${task.id.name.lowercase()}",
            ) { checked ->
                var wasChecked = false
                update { latest ->
                    val current =
                        TripPreparationRules.reconcile(
                            latest,
                            TripPreparationRules.allTasks(latest, observed, foodIds, trip.guests),
                        )
                    wasChecked = TripPreparationRules.checked(current, task)
                    TripPreparationRules.check(current, task, checked, System.currentTimeMillis())
                }
                if (checked && !wasChecked) count(PreparationUsageAction.CHECKED)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CampSecondary(
                tr(R.string.prep_view_cabin),
                enabled = enabled,
                onClick = onCabin,
                modifier = Modifier.testTag("prep-view-cabin"),
            )
            TextButton(
                onClick = { expanded = !expanded },
                modifier =
                    Modifier
                        .heightIn(min = 52.dp)
                        .testTag("prep-toggle-supplies"),
            ) {
                Text(tr(if (expanded) R.string.prep_hide_next else R.string.prep_next_title), fontSize = 22.sp)
            }
        }
        if (expanded) {
            HorizontalDivider(color = CampPalette.Line)
            Text(tr(R.string.prep_supplies_title), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            PreparationSupply.entries.forEach { supply ->
                PrepCheckRow(tr(supply.label()), supply in record.supplies, enabled, "prep-supply-${supply.name.lowercase()}") { checked ->
                    update { it.copy(supplies = if (checked) it.supplies + supply else it.supplies - supply) }
                }
            }
            Text(tr(R.string.prep_food_selection, foodIds.size), fontSize = 22.sp, color = CampPalette.Muted)
            Text(tr(R.string.prep_next_explanation), fontSize = 22.sp, color = CampPalette.Muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CampSecondary(
                    tr(R.string.prep_save_next),
                    enabled = enabled && (record.supplies.isNotEmpty() || foodIds.isNotEmpty()),
                    modifier = Modifier.testTag("prep-save-next"),
                ) {
                    val latest = store.snapshot(tripKey).record
                    val next =
                        NextTripPreparation(
                            tripToken,
                            trip.guests,
                            latest.supplies,
                            selectedFoodPreparation(foodPreferences, tripKey),
                        )
                    store.saveTemplate(next)
                    template = next
                    message = R.string.prep_next_saved
                }
                val previous = template
                if (previous != null && previous.sourceTripToken != tripToken) {
                    CampSecondary(
                        tr(R.string.prep_import_previous),
                        enabled = enabled,
                        modifier = Modifier.testTag("prep-import-previous"),
                    ) {
                        TripPreparationRules.importNextTrip(previous, tripToken)?.let { imported ->
                            update { imported }
                            importFoodPreparation(foodPreferences, tripKey, previous.foodIds)
                            count(PreparationUsageAction.TEMPLATE_IMPORTED)
                            message =
                                if (previous.guests ==
                                    trip.guests
                                ) {
                                    R.string.prep_previous_imported
                                } else {
                                    R.string.prep_previous_guests_changed
                                }
                        }
                    }
                }
            }
            if (message != 0) Text(tr(message), fontSize = 22.sp, color = CampPalette.Forest)
            HorizontalDivider(color = CampPalette.Line)
            val usageLabel = tr(R.string.prep_usage_title)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(usageLabel, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    Text(tr(R.string.prep_usage_explanation), fontSize = 22.sp, color = CampPalette.Muted)
                }
                Switch(
                    checked = usage.optedIn,
                    enabled = enabled,
                    modifier = Modifier.testTag("prep-usage-opt-in").semantics { contentDescription = usageLabel },
                    onCheckedChange = { accepted ->
                        usage = usage.withOptIn(accepted)
                        store.saveUsage(usage)
                    },
                )
            }
            if (usage.optedIn) {
                Text(
                    tr(R.string.prep_usage_counts, usage.checkedItems, usage.phaseChanges, usage.templateImports),
                    fontSize = 22.sp,
                    color = CampPalette.Muted,
                    modifier = Modifier.testTag("prep-local-counts"),
                )
            }
        }
    }
}

@Composable
private fun PrepCheckRow(
    text: String,
    checked: Boolean,
    enabled: Boolean,
    tag: String,
    change: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = change,
            ).testTag(tag)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = null)
        Text(text, fontSize = 22.sp, modifier = Modifier.weight(1f))
    }
}

private fun PreparationPhase.label() =
    when (this) {
        PreparationPhase.ARRIVAL -> R.string.prep_phase_arrival
        PreparationPhase.STAY -> R.string.prep_phase_stay
        PreparationPhase.DEPARTURE -> R.string.prep_phase_departure
    }

private fun PreparationSupply.label() =
    when (this) {
        PreparationSupply.CHARGING_CABLE -> R.string.prep_supply_cable
        PreparationSupply.COOLER -> R.string.prep_supply_cooler
        PreparationSupply.LANTERN -> R.string.prep_supply_lantern
        PreparationSupply.WASTE_BAGS -> R.string.prep_supply_waste
    }

private fun PreparationTask.label(advice: PreparationBatteryAdvice): Int =
    when (id) {
        PreparationTaskId.ARRIVAL_SITE -> {
            R.string.prep_arrival_site
        }

        PreparationTaskId.ARRIVAL_POWER, PreparationTaskId.STAY_POWER -> {
            when (advice) {
                PreparationBatteryAdvice.DIRECT_CHECK -> R.string.prep_power_direct
                PreparationBatteryAdvice.REVIEW_CHARGING -> R.string.prep_power_charging
                PreparationBatteryAdvice.REVIEW_POWER_USE -> R.string.prep_power_usage
            }
        }

        PreparationTaskId.ARRIVAL_SUPPLIES -> {
            R.string.prep_arrival_supplies
        }

        PreparationTaskId.STAY_ENVIRONMENT -> {
            R.string.prep_stay_environment
        }

        PreparationTaskId.STAY_MEAL -> {
            R.string.prep_stay_meal
        }

        PreparationTaskId.DEPARTURE_CABLES -> {
            R.string.prep_departure_cables
        }

        PreparationTaskId.DEPARTURE_OPENINGS -> {
            R.string.prep_departure_openings
        }

        PreparationTaskId.DEPARTURE_SITE -> {
            R.string.prep_departure_site
        }
    }
