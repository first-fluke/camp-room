@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package camp.room.app

import camp.room.app.icons.CampIcons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class CampScene(
    val id: String,
    private val nameResource: Int,
    private val subtitleResource: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    val name: String
        get() = CampText.get(nameResource)

    val sub: String
        get() = CampText.get(subtitleResource)
}

val campScenes =
    listOf(
        CampScene(
            "load",
            camp.room.app.R.string.camp_unpack,
            camp.room.app.R.string.camp_ease_into_your_arrival,
            CampIcons.Artwork.Package,
        ),
        CampScene(
            "together",
            camp.room.app.R.string.camp_relax_together,
            camp.room.app.R.string.camp_comfort_for_everyone,
            CampIcons.Artwork.Sun,
        ),
        CampScene(
            "split",
            camp.room.app.R.string.camp_read_and_rest,
            camp.room.app.R.string.camp_reading_in_front_resting_in_the_rear,
            CampIcons.Artwork.BookOpen,
        ),
        CampScene(
            "sleep",
            camp.room.app.R.string.camp_prepare_for_sleep,
            camp.room.app.R.string.camp_a_quieter_end_to_the_day,
            CampIcons.Artwork.MoonStars,
        ),
        CampScene(
            "departure",
            camp.room.app.R.string.camp_prepare_to_leave,
            camp.room.app.R.string.camp_ready_for_the_next_journey,
            CampIcons.Vehicle,
        ),
    )

@Composable
fun CabinScreen(s: CampUiState, vm: CampViewModel) {
    val vehicle = s.data?.vehicle ?: return
    SectionTitle(
        tr(camp.room.app.R.string.camp_a_little_room_of_our_own_inside),
        tr(camp.room.app.R.string.camp_let_reading_and_resting_happen_comfortably_side),
        action = {
            CampPill(
                if (vehicle.mode == "demo") tr(camp.room.app.R.string.camp_simulator_connected)
                else tr(camp.room.app.R.string.camp_pleos_vehicle_status)
            )
        },
    )
    CampSurface(Modifier.fillMaxWidth()) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VehicleStatus(
                CampIcons.BatteryCharging,
                tr(camp.room.app.R.string.camp_current_battery),
                "${number(vehicle.soc)}%",
            )
            VehicleStatus(
                CampIcons.Thermometer,
                tr(R.string.cabin_layout_front_temperature),
                "${number(vehicle.measuredTemperature)}°C",
            )
            VehicleStatus(
                CampIcons.Door,
                tr(camp.room.app.R.string.camp_doors),
                when (vehicle.doorsClosed) {
                    true -> tr(camp.room.app.R.string.camp_all_closed)
                    false -> tr(camp.room.app.R.string.camp_a_door_is_open)
                    null -> tr(camp.room.app.R.string.camp_check_needed)
                },
            )
            VehicleStatus(
                CampIcons.Wind,
                tr(camp.room.app.R.string.camp_windows),
                when (vehicle.windowsClosed) {
                    true -> tr(camp.room.app.R.string.camp_all_closed)
                    false -> tr(camp.room.app.R.string.camp_a_window_is_open)
                    null -> tr(camp.room.app.R.string.camp_check_needed)
                },
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    tr(
                        camp.room.app.R.string.camp_observed,
                        vehicle.updatedAt.take(16).replace('T', ' '),
                    ),
                    fontSize = 22.sp,
                    color = CampPalette.Muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tr(
                        camp.room.app.R.string.camp_state,
                        if (vehicle.mode == "demo") tr(camp.room.app.R.string.camp_simulated)
                        else "",
                        when (vehicle.motionState) {
                            "parked" -> tr(camp.room.app.R.string.camp_parked)
                            "moving" -> tr(camp.room.app.R.string.camp_moving)
                            else -> tr(camp.room.app.R.string.camp_unknown)
                        },
                    ),
                    fontSize = 22.sp,
                    color = CampPalette.Muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    BoxWithConstraints {
        val wide = maxWidth > 960.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale
        if (wide)
            Row(horizontalArrangement = Arrangement.spacedBy(23.dp)) {
                CabinDiagram(vehicle, Modifier.weight(1.18f))
                ScenePanel(s, vm, Modifier.weight(1f))
            }
        else
            Column(verticalArrangement = Arrangement.spacedBy(23.dp)) {
                CabinDiagram(vehicle, Modifier.fillMaxWidth())
                ScenePanel(s, vm, Modifier.fillMaxWidth())
            }
    }
    CampSurface(Modifier.fillMaxWidth()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                tr(camp.room.app.R.string.camp_a_little_more_just_for_you),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                tr(camp.room.app.R.string.camp_only_settings_you_explicitly_apply_will_run),
                fontSize = 22.sp,
                color = CampPalette.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BoxWithConstraints {
            val controlWidth = if (maxWidth > 1100.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale) (maxWidth - 60.dp) / 3 else maxWidth
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(30.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.width(controlWidth)) {
                    Text(
                        tr(camp.room.app.R.string.camp_target_cabin_temperature),
                        fontSize = 22.sp,
                        color = CampPalette.Muted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 17.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedIconButton(
                            onClick = {
                                vm.control("targetTemperature", (vehicle.targetTemperature ?: 22.0) - 1)
                            },
                            enabled = !s.busy && (vehicle.targetTemperature ?: 22.0) > 16,
                            modifier = Modifier.size(CampDesign.IconTarget),
                        ) {
                            Icon(
                                CampIcons.Minus,
                                tr(camp.room.app.R.string.camp_lower_target_temperature),
                                Modifier.size(24.dp),
                            )
                        }
                        Text(
                            "${number(vehicle.targetTemperature)}°C",
                            fontSize = 31.sp,
                            fontWeight = FontWeight.Light,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        OutlinedIconButton(
                            onClick = {
                                vm.control("targetTemperature", (vehicle.targetTemperature ?: 22.0) + 1)
                            },
                            enabled = !s.busy && (vehicle.targetTemperature ?: 22.0) < 30,
                            modifier = Modifier.size(CampDesign.IconTarget),
                        ) {
                            Icon(
                                CampIcons.Plus,
                                tr(camp.room.app.R.string.camp_raise_target_temperature),
                                Modifier.size(24.dp),
                            )
                        }
                    }
                    Text(
                        tr(camp.room.app.R.string.camp_measured_temperature_and_target_are_different),
                        fontSize = 22.sp,
                        color = CampPalette.Muted,
                        modifier = Modifier.padding(top = 9.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ZoneControls(
                    tr(R.string.cabin_layout_front_targets),
                    "front",
                    vehicle.front,
                    s.busy,
                    vm,
                    Modifier.width(controlWidth),
                )
                ZoneControls(
                    tr(R.string.cabin_layout_rear_targets),
                    "rear",
                    vehicle.rear,
                    s.busy,
                    vm,
                    Modifier.width(controlWidth),
                )
            }
        }
    }
    if (s.commands.isNotEmpty())
        CampSurface(Modifier.fillMaxWidth().testTag("scene-results")) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (s.commandStatus == "completed") CampIcons.CheckCircle
                    else CampIcons.WarningCircle,
                    null,
                    Modifier.size(24.dp),
                    tint = CampPalette.Muted,
                )
                Text(
                    tr(camp.room.app.R.string.camp_cabin_setting_results),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                CampPill(
                    if (s.commandStatus == "completed")
                        tr(camp.room.app.R.string.camp_items_checked)
                    else tr(camp.room.app.R.string.camp_some_items_need_attention)
                )
            }
            s.commands.forEach { result ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.size(5.dp)
                            .background(
                                if (result.status == "applied") CampPalette.Forest
                                else CampPalette.Danger,
                                CircleShape,
                            )
                    )
                    Text(
                        controlLabel(result.control) + if (result.areaId.isNotBlank()) "\n" + cabinTargetLabel(result.areaId) else "",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        result.message,
                        fontSize = 22.sp,
                        color = CampPalette.Muted,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        when (result.confirmation) {
                            "observed" -> tr(camp.room.app.R.string.camp_observed_state_checked)
                            "acknowledged" -> tr(camp.room.app.R.string.camp_command_accepted)
                            else -> tr(camp.room.app.R.string.camp_state_needs_checking)
                        },
                        fontSize = 22.sp,
                        color = CampPalette.Muted,
                    )
                }
                HorizontalDivider(color = CampPalette.Line)
            }
            Text(
                if (vehicle.mode == "demo")
                    tr(
                        camp.room.app.R.string
                            .camp_command_acceptance_and_observed_state_are_different
                    )
                else
                    tr(
                        camp.room.app.R.string
                            .camp_command_acceptance_and_observed_state_are_different_170
                    ),
                fontSize = 22.sp,
                color = CampPalette.Muted,
            )
        }
    if (s.sceneId == "departure")
        CampSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(CampIcons.CheckCircle, null, tint = CampPalette.Muted)
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        tr(camp.room.app.R.string.camp_travel_light_into_the_next_chapter),
                        fontSize = 22.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        tr(
                            camp.room.app.R.string.camp_check_doors_windows_and_battery_at_then,
                            number(vehicle.soc),
                        ),
                        fontSize = 22.sp,
                        color = CampPalette.Muted,
                        modifier = Modifier.padding(top = 7.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                CampSecondary(
                    tr(camp.room.app.R.string.camp_view_travel_plan),
                    CampIcons.ChevronRight,
                    onClick = { vm.tab(1) },
                )
            }
        }
}

@Composable
private fun VehicleStatus(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(24.dp), tint = CampPalette.Muted)
        Column(Modifier.padding(start = 10.dp)) {
            Text(
                label,
                fontSize = 22.sp,
                color = CampPalette.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                value,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 5.dp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ZoneControls(
    label: String,
    zone: String,
    value: CabinZone,
    busy: Boolean,
    vm: CampViewModel,
    modifier: Modifier,
) {
    var blind by remember(value.blind) { mutableFloatStateOf((value.blind ?: 0).toFloat()) }
    Column(modifier) {
        Text(
            tr(camp.room.app.R.string.camp_zone, label),
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth().heightIn(min = CampDesign.IconTarget), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (value.reading == null)
                    tr(camp.room.app.R.string.camp_reading_light_check_needed)
                else tr(camp.room.app.R.string.camp_reading_light),
                fontSize = 22.sp,
                color = CampPalette.Muted,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Switch(
                checked = value.reading == true,
                onCheckedChange = { vm.control("readingLight", it, zone) },
                enabled = !busy && value.reading != null,
                modifier = Modifier.sizeIn(minWidth = CampDesign.IconTarget, minHeight = CampDesign.IconTarget),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Text(
                tr(camp.room.app.R.string.camp_shade),
                fontSize = 22.sp,
                color = CampPalette.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (value.blind == null) tr(camp.room.app.R.string.camp_check_needed)
                else "${blind.toInt()}%",
                fontSize = 22.sp,
                color = CampPalette.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Slider(
            value = blind,
            onValueChange = { blind = it },
            onValueChangeFinished = { vm.control("blindLevel", blind.toInt(), zone) },
            valueRange = 0f..100f,
            steps = 9,
            enabled = !busy && value.blind != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = CampDesign.IconTarget),
        )
    }
}

@Composable
private fun ScenePanel(s: CampUiState, vm: CampViewModel, modifier: Modifier) {
    CampSurface(modifier) {

        Text(
            tr(camp.room.app.R.string.camp_what_kind_of_rest_do_you_need),
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        campScenes.forEach { scene ->
            val selected = scene.id == s.sceneId
            Surface(
                onClick = { vm.scene(scene.id) },
                enabled = !s.busy,
                color = if (selected) CampPalette.Sage else CampPalette.Paper,
                shape = RoundedCornerShape(8.dp),
                border =
                    androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (selected) CampPalette.Outline else CampPalette.Line,
                    ),
                modifier = Modifier.fillMaxWidth().testTag("scene-${scene.id}"),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(35.dp)
                            .background(
                                if (selected) CampPalette.Selected else CampPalette.Canvas,
                                RoundedCornerShape(7.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(scene.icon, null, Modifier.size(24.dp), tint = CampPalette.Muted)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            scene.name,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            scene.sub,
                            fontSize = 22.sp,
                            color = CampPalette.Muted,
                            modifier = Modifier.padding(top = 3.dp),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    RadioButton(
                        selected = selected,
                        onClick = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Text(
            when (s.sceneId) {
                "split" -> tr(camp.room.app.R.string.camp_keep_the_front_bright_for_reading_and)
                "sleep" -> tr(camp.room.app.R.string.camp_lower_the_light_and_add_shade_for)
                "departure" ->
                    tr(camp.room.app.R.string.camp_reset_shade_and_lighting_then_check_before)
                "load" -> tr(camp.room.app.R.string.camp_light_both_zones_so_you_can_unpack)
                else ->
                    tr(camp.room.app.R.string.camp_soft_lighting_and_a_comfortable_temperature_for)
            },
            fontSize = 22.sp,
            color = CampPalette.Muted,
            lineHeight = 28.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        CampPrimary(
            tr(camp.room.app.R.string.camp_apply_these_cabin_settings),
            CampIcons.Check,
            busy = s.busy,
            modifier = Modifier.fillMaxWidth().testTag("apply-scene"),
            onClick = vm::applyScene,
        )
        Text(
            tr(camp.room.app.R.string.camp_after_supported_commands_run_the_simulator_state),
            fontSize = 22.sp,
            color = CampPalette.Muted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CabinDiagram(vehicle: Vehicle, modifier: Modifier) {
    CampSurface(modifier.testTag("cabin-control-layout")) {
        Text(tr(R.string.cabin_layout_title), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (vehicle.layoutSource == "demo_synthetic") tr(R.string.cabin_layout_demo_short)
            else tr(R.string.cabin_layout_model_unknown_short),
            fontSize = 22.sp, color = CampPalette.Muted,
        )
        if (vehicle.controlAreas.isEmpty()) {
            Text(tr(R.string.cabin_layout_unknown), fontSize = 22.sp, color = CampPalette.Muted)
        } else {
            // Spatial headings sort presentation only. Exact ordered SDK members/IDs stay untouched.
            val sections = vehicle.controlAreas.groupBy { group ->
                val member = group.area.members.singleOrNull()
                Regex("ROW_([123])_(LEFT|CENTER|RIGHT)").matchEntire(member.orEmpty())?.groupValues?.get(1)
                    ?: if (group.area.kind == "demo") "demo" else "groups"
            }
            for (section in sections.keys.sorted()) {
                if (section != "demo") Text(
                    if (section == "groups") tr(R.string.cabin_layout_linked_groups)
                    else tr(R.string.cabin_layout_row_heading, section),
                    fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                )
                BoxWithConstraints {
                    val cardWidth = if (maxWidth > 760.dp) (maxWidth - 16.dp) / 2 else maxWidth
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val cards = sections.getValue(section).groupBy { it.area.id }.values.sortedBy {
                            when (it.first().area.members.singleOrNull()?.substringAfterLast('_')) { "LEFT" -> 0; "CENTER" -> 1; "RIGHT" -> 2; else -> 3 }
                        }
                        for (controls in cards) {
                            val area = controls.first().area
                            Surface(
                                modifier = Modifier.width(cardWidth).testTag("cabin-area-${area.id}"),
                                color = CampPalette.Sage, shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, CampPalette.Line),
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(cabinAreaLabel(area), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                                    for (group in controls) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text(cabinFeatureLabel(group.feature), modifier = Modifier.weight(1f), fontSize = 22.sp)
                                            val value = group.observed
                                            Text(
                                                when {
                                                    value == null -> tr(R.string.cabin_layout_value_unknown_short)
                                                    group.feature == "readingLight" -> when (value) { 1.0 -> tr(R.string.cabin_layout_on); 0.0 -> tr(R.string.cabin_layout_off); else -> tr(R.string.cabin_layout_value_unknown_short) }
                                                    group.feature in listOf("targetTemperature", "cabinTemperature") -> "${number(value)}°C"
                                                    else -> "${number(value)}%"
                                                },
                                                fontSize = 22.sp, fontWeight = FontWeight.Medium,
                                            )
                                        }
                                        if (!group.writable || !group.readable) Text(
                                            when {
                                                group.readable -> tr(R.string.cabin_layout_read_only)
                                                group.writable -> tr(R.string.cabin_layout_write_only)
                                                else -> tr(R.string.cabin_layout_access_unknown)
                                            },
                                            fontSize = 22.sp, color = CampPalette.Muted,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (vehicle.unknownFeatures.isNotEmpty()) {
            Text(tr(R.string.cabin_layout_unconfirmed_features), fontSize = 22.sp, fontWeight = FontWeight.Medium)
            vehicle.unknownFeatures.forEach { Text(cabinFeatureLabel(it), fontSize = 22.sp, color = CampPalette.Muted) }
        }
    }
}

@Composable
private fun cabinFeatureLabel(feature: String): String = when (feature) {
    "cabinTemperature" -> tr(R.string.camp_measured_cabin_temperature)
    "targetTemperature" -> tr(R.string.camp_target_cabin_temperature)
    "readingLight" -> tr(R.string.camp_reading_light)
    "ambientBrightness" -> tr(R.string.camp_ambient_brightness)
    "blindLevel" -> tr(R.string.camp_shade)
    else -> feature
}

@Composable
private fun cabinAreaLabel(area: CabinArea): String {
    if (area.members.isEmpty()) return tr(R.string.cabin_layout_unspecified)
    if (area.kind == "demo") return if (area.members.contains("DEMO_FRONT_ZONE")) tr(R.string.cabin_layout_demo_front) else tr(R.string.cabin_layout_demo_rear)
    val labels = mutableListOf<String>()
    for (member in area.members) {
        val match = Regex("ROW_([123])_(LEFT|CENTER|RIGHT)").matchEntire(member)
        labels += if (match == null) when (member) {
            "FRONT_WINDSHIELD" -> tr(R.string.cabin_layout_front_windshield)
            "REAR_WINDSHIELD" -> tr(R.string.cabin_layout_rear_windshield)
            "ROOF_TOP_1" -> tr(R.string.cabin_layout_roof_1)
            "ROOF_TOP_2" -> tr(R.string.cabin_layout_roof_2)
            else -> tr(R.string.cabin_layout_unspecified)
        } else {
            val side = when (match.groupValues[2]) {
                "LEFT" -> tr(R.string.cabin_layout_left)
                "CENTER" -> tr(R.string.cabin_layout_center)
                else -> tr(R.string.cabin_layout_right)
            }
            tr(R.string.cabin_layout_row_position, match.groupValues[1], side)
        }
    }
    return labels.joinToString(" · ")
}

@Composable
private fun cabinTargetLabel(targetId: String): String {
    val descriptor = targetId.substringBefore('|').split(':', limit = 2)
    if (descriptor.size != 2) return tr(R.string.cabin_layout_unspecified)
    val members = if (descriptor[0] == "demo") listOf("DEMO_${descriptor[1].uppercase()}_ZONE")
        else descriptor[1].takeUnless { it == "<unspecified>" }?.split('+') ?: emptyList()
    return cabinAreaLabel(CabinArea(descriptor[0], members))
}

fun controlLabel(value: String): String {
    val zone =
        if (value.startsWith("front"))
            camp.room.app.CampText.get(camp.room.app.R.string.camp_front_201)
        else if (value.startsWith("rear"))
            camp.room.app.CampText.get(camp.room.app.R.string.camp_rear_202)
        else ""
    return zone +
        when (value.substringAfterLast('.')) {
            "readingLight" -> camp.room.app.CampText.get(camp.room.app.R.string.camp_reading_light)
            "ambientBrightness" ->
                camp.room.app.CampText.get(camp.room.app.R.string.camp_ambient_brightness)
            "blindLevel" -> camp.room.app.CampText.get(camp.room.app.R.string.camp_shade)
            "targetTemperature" ->
                camp.room.app.CampText.get(camp.room.app.R.string.camp_target_temperature)
            else -> value
        }
}

@Composable
fun CampLandscape(id: String, modifier: Modifier = Modifier) {
    Canvas(modifier.clipToBounds()) {
        val shore = id == "solbaram" || id == "island"
        val sky = if (shore) Color(0xFFD7E6DF) else Color(0xFFDDE2CF)
        val mountain = if (shore) Color(0xFF87AAA0) else Color(0xFF93A68C)
        drawRect(sky)
        drawCircle(
            Color(0xFFF0E4BC),
            size.height * .13f,
            Offset(size.width * .76f, size.height * .23f),
        )
        val back =
            Path().apply {
                moveTo(0f, size.height * .65f)
                lineTo(size.width * .18f, size.height * .2f)
                lineTo(size.width * .38f, size.height * .63f)
                lineTo(size.width * .58f, size.height * .12f)
                lineTo(size.width * .83f, size.height * .61f)
                lineTo(size.width, size.height * .32f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
        drawPath(back, mountain)
        val front =
            Path().apply {
                moveTo(0f, size.height * .7f)
                lineTo(size.width * .18f, size.height * .49f)
                lineTo(size.width * .43f, size.height * .77f)
                lineTo(size.width * .73f, size.height * .43f)
                lineTo(size.width, size.height * .67f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
        drawPath(front, Color(0xFF5D806A))
        drawRect(
            if (shore) Color(0xFF9EC2B4) else Color(0xFFAAC1AA),
            Offset(0f, size.height * .72f),
            Size(size.width, size.height * .28f),
        )
        val grass =
            Path().apply {
                moveTo(0f, size.height * .91f)
                quadraticTo(
                    size.width * .3f,
                    size.height * .75f,
                    size.width * .6f,
                    size.height * .87f,
                )
                quadraticTo(size.width * .8f, size.height * .9f, size.width, size.height * .76f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
        drawPath(grass, Color(0xFFA7AF87))
        fun tree(x: Float, y: Float, h: Float) {
            drawLine(Color(0xFF34543D), Offset(x, y), Offset(x, y - h * .7f), size.width * .008f)
            val p =
                Path().apply {
                    moveTo(x, y - h)
                    lineTo(x - h * .2f, y - h * .38f)
                    lineTo(x - h * .11f, y - h * .38f)
                    lineTo(x - h * .27f, y - h * .12f)
                    lineTo(x + h * .27f, y - h * .12f)
                    lineTo(x + h * .11f, y - h * .38f)
                    lineTo(x + h * .2f, y - h * .38f)
                    close()
                }
            drawPath(p, Color(0xFF345942))
        }
        tree(size.width * .09f, size.height * .97f, size.height * .86f)
        tree(size.width * .19f, size.height * .95f, size.height * .6f)
        tree(size.width * .91f, size.height * .94f, size.height * .86f)
        tree(size.width * .99f, size.height * .95f, size.height * .61f)
        val tent =
            Path().apply {
                moveTo(size.width * .31f, size.height * .92f)
                lineTo(size.width * .48f, size.height * .56f)
                lineTo(size.width * .69f, size.height * .93f)
                close()
            }
        drawPath(tent, Color(0xFFDCAD7D))
        val side =
            Path().apply {
                moveTo(size.width * .48f, size.height * .56f)
                lineTo(size.width * .49f, size.height * .93f)
                lineTo(size.width * .69f, size.height * .93f)
                close()
            }
        drawPath(side, Color(0xFFB78A60))
        val entrance =
            Path().apply {
                moveTo(size.width * .38f, size.height * .92f)
                lineTo(size.width * .478f, size.height * .63f)
                lineTo(size.width * .49f, size.height * .93f)
                close()
            }
        drawPath(entrance, Color(0xFF34543C))
        drawLine(
            Color(0xFFE7CDA4),
            Offset(size.width * .31f, size.height * .92f),
            Offset(size.width * .69f, size.height * .93f),
            size.height * .008f,
        )
    }
}

@Composable
fun ProductArtwork(id: String, modifier: Modifier = Modifier) {
    // Category illustrations are deliberately not presented as exact product photography.
    val icon =
        when {
            id.contains("sleep") || id.contains("blanket") -> CampIcons.Artwork.Bed
            id.contains("coffee") -> CampIcons.Artwork.Coffee
            id.contains("lantern") || id.contains("light") -> CampIcons.Artwork.Flashlight
            id.contains("chair") -> CampIcons.Artwork.Armchair
            else -> CampIcons.Artwork.Backpack
        }
    BoxWithConstraints(
        modifier.background(CampPalette.Sage),
        contentAlignment = Alignment.Center,
    ) {
        val glyphSize = (minOf(maxWidth, maxHeight) * .5f).coerceIn(24.dp, 56.dp)
        Canvas(Modifier.fillMaxSize()) {
            drawOval(
                Color(0xFF747954).copy(alpha = .12f),
                Offset(size.width * .31f, size.height * .73f),
                Size(size.width * .38f, size.height * .1f),
            )
        }
        Icon(
            icon,
            null,
            Modifier.size(glyphSize),
            tint = CampPalette.Forest,
        )
    }
}
