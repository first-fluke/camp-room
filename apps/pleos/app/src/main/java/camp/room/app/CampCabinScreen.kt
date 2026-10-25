@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package camp.room.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            Icons.Outlined.Inventory2,
        ),
        CampScene(
            "together",
            camp.room.app.R.string.camp_relax_together,
            camp.room.app.R.string.camp_comfort_for_everyone,
            Icons.Outlined.WbSunny,
        ),
        CampScene(
            "split",
            camp.room.app.R.string.camp_read_and_rest,
            camp.room.app.R.string.camp_reading_in_front_resting_in_the_rear,
            Icons.Outlined.MenuBook,
        ),
        CampScene(
            "sleep",
            camp.room.app.R.string.camp_prepare_for_sleep,
            camp.room.app.R.string.camp_a_quieter_end_to_the_day,
            Icons.Outlined.Bedtime,
        ),
        CampScene(
            "departure",
            camp.room.app.R.string.camp_prepare_to_leave,
            camp.room.app.R.string.camp_ready_for_the_next_journey,
            Icons.Outlined.DirectionsCar,
        ),
    )

@Composable
fun CabinScreen(s: CampUiState, vm: CampViewModel) {
    val vehicle = s.data?.vehicle ?: return
    SectionTitle(
        tr(camp.room.app.R.string.camp_a_little_room_of_our_own_inside),
        tr(camp.room.app.R.string.camp_let_reading_and_resting_happen_comfortably_side),
        "MAKE ROOM FOR REST",
        action = {
            CampPill(
                if (vehicle.mode == "demo") tr(camp.room.app.R.string.camp_simulator_connected)
                else tr(camp.room.app.R.string.camp_pleos_vehicle_status)
            )
        },
    )
    CampSurface(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VehicleStatus(
                Icons.Outlined.BatteryChargingFull,
                tr(camp.room.app.R.string.camp_current_battery),
                "${number(vehicle.soc)}%",
            )
            VehicleStatus(
                Icons.Outlined.Thermostat,
                tr(camp.room.app.R.string.camp_measured_cabin_temperature),
                "${number(vehicle.measuredTemperature)}°C",
            )
            VehicleStatus(
                Icons.Outlined.DoorFront,
                tr(camp.room.app.R.string.camp_doors),
                when (vehicle.doorsClosed) {
                    true -> tr(camp.room.app.R.string.camp_all_closed)
                    false -> tr(camp.room.app.R.string.camp_a_door_is_open)
                    null -> tr(camp.room.app.R.string.camp_check_needed)
                },
            )
            VehicleStatus(
                Icons.Outlined.Air,
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
                    fontSize = 12.sp,
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
                    fontSize = 12.sp,
                    color = CampPalette.Muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    BoxWithConstraints {
        val wide = maxWidth > 720.dp
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr(camp.room.app.R.string.camp_a_little_more_just_for_you),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                tr(camp.room.app.R.string.camp_only_settings_you_explicitly_apply_will_run),
                fontSize = 12.sp,
                color = CampPalette.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr(camp.room.app.R.string.camp_target_cabin_temperature),
                    fontSize = 12.sp,
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
                            Icons.Outlined.Remove,
                            tr(camp.room.app.R.string.camp_lower_target_temperature),
                            Modifier.size(16.dp),
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
                            Icons.Outlined.Add,
                            tr(camp.room.app.R.string.camp_raise_target_temperature),
                            Modifier.size(16.dp),
                        )
                    }
                }
                Text(
                    tr(camp.room.app.R.string.camp_measured_temperature_and_target_are_different),
                    fontSize = 12.sp,
                    color = CampPalette.Muted,
                    modifier = Modifier.padding(top = 9.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ZoneControls(
                tr(camp.room.app.R.string.camp_front_seats),
                "front",
                vehicle.front,
                s.busy,
                vm,
                Modifier.weight(1f),
            )
            ZoneControls(
                tr(camp.room.app.R.string.camp_rear_seats),
                "rear",
                vehicle.rear,
                s.busy,
                vm,
                Modifier.weight(1f),
            )
        }
    }
    if (s.commands.isNotEmpty())
        CampSurface(Modifier.fillMaxWidth().testTag("scene-results")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (s.commandStatus == "completed") Icons.Outlined.CheckCircle
                    else Icons.Outlined.ErrorOutline,
                    null,
                    Modifier.size(21.dp),
                    tint = CampPalette.Muted,
                )
                Text(
                    tr(camp.room.app.R.string.camp_cabin_setting_results),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                CampPill(
                    if (s.commandStatus == "completed")
                        tr(camp.room.app.R.string.camp_items_checked)
                    else tr(camp.room.app.R.string.camp_some_items_need_attention)
                )
            }
            s.commands.forEach { result ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(5.dp)
                            .background(
                                if (result.status == "applied") CampPalette.Forest
                                else CampPalette.Danger,
                                CircleShape,
                            )
                    )
                    Text(
                        controlLabel(result.control),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(140.dp).padding(start = 8.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        result.message,
                        fontSize = 12.sp,
                        color = CampPalette.Muted,
                        modifier = Modifier.weight(1f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when (result.confirmation) {
                            "observed" -> tr(camp.room.app.R.string.camp_observed_state_checked)
                            "acknowledged" -> tr(camp.room.app.R.string.camp_command_accepted)
                            else -> tr(camp.room.app.R.string.camp_state_needs_checking)
                        },
                        fontSize = 12.sp,
                        color = CampPalette.Muted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
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
                fontSize = 12.sp,
                color = CampPalette.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    if (s.sceneId == "departure")
        CampSurface(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, null, tint = CampPalette.Muted)
                Column(Modifier.weight(1f).padding(horizontal = 15.dp)) {
                    Text(
                        tr(camp.room.app.R.string.camp_travel_light_into_the_next_chapter),
                        fontSize = 18.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        tr(
                            camp.room.app.R.string.camp_check_doors_windows_and_battery_at_then,
                            number(vehicle.soc),
                        ),
                        fontSize = 12.sp,
                        color = CampPalette.Muted,
                        modifier = Modifier.padding(top = 7.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                CampSecondary(
                    tr(camp.room.app.R.string.camp_view_travel_plan),
                    Icons.Outlined.ArrowForward,
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
                fontSize = 12.sp,
                color = CampPalette.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                value,
                fontSize = 18.sp,
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
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (value.reading == null)
                    tr(camp.room.app.R.string.camp_reading_light_check_needed)
                else tr(camp.room.app.R.string.camp_reading_light),
                fontSize = 12.sp,
                color = CampPalette.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Switch(
                checked = value.reading == true,
                onCheckedChange = { vm.control("readingLight", it, zone) },
                enabled = !busy && value.reading != null,
                modifier = Modifier.height(28.dp),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Text(
                tr(camp.room.app.R.string.camp_shade),
                fontSize = 12.sp,
                color = CampPalette.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (value.blind == null) tr(camp.room.app.R.string.camp_check_needed)
                else "${blind.toInt()}%",
                fontSize = 12.sp,
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
            modifier = Modifier.fillMaxWidth().height(30.dp),
        )
    }
}

@Composable
private fun ScenePanel(s: CampUiState, vm: CampViewModel, modifier: Modifier) {
    CampSurface(modifier) {
        Text(
            "MOMENTS IN YOUR ROOM",
            fontSize = 12.sp,
            letterSpacing = 1.4.sp,
            color = CampPalette.Muted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            tr(camp.room.app.R.string.camp_what_kind_of_rest_do_you_need),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        campScenes.forEach { scene ->
            val selected = scene.id == s.sceneId
            Surface(
                onClick = { vm.scene(scene.id) },
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
                        Icon(scene.icon, null, Modifier.size(23.dp), tint = CampPalette.Muted)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            scene.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            scene.sub,
                            fontSize = 12.sp,
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
            fontSize = 12.sp,
            color = CampPalette.Muted,
            lineHeight = 17.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        CampPrimary(
            tr(camp.room.app.R.string.camp_apply_these_cabin_settings),
            Icons.Outlined.Check,
            busy = s.busy,
            modifier = Modifier.fillMaxWidth().testTag("apply-scene"),
            onClick = vm::applyScene,
        )
        Text(
            tr(camp.room.app.R.string.camp_after_supported_commands_run_the_simulator_state),
            fontSize = 12.sp,
            color = CampPalette.Muted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CabinDiagram(vehicle: Vehicle, modifier: Modifier) {
    Surface(
        modifier = modifier.height(566.dp),
        color = CampPalette.Sage,
        shape = RoundedCornerShape(11.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CampPalette.Line),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.padding(25.dp)) {
                Text(
                    "MY LITTLE RETREAT",
                    fontSize = 12.sp,
                    letterSpacing = 1.5.sp,
                    color = CampPalette.Muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (vehicle.sceneId == "split")
                        tr(camp.room.app.R.string.camp_different_moments_shared_comfortably)
                    else if (vehicle.sceneId == "sleep")
                        tr(camp.room.app.R.string.camp_a_softer_landing_at_the_end_of)
                    else tr(camp.room.app.R.string.camp_a_little_room_for_today_s_rest),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Canvas(
                Modifier.fillMaxWidth().height(440.dp).align(Alignment.Center).padding(top = 35.dp)
            ) {
                val scale = size.height / 440f
                val carW = 185f * scale
                val carH = 389f * scale
                val left = size.width / 2 - carW / 2
                val top = (size.height - carH) / 2 + 5f
                fun rect(color: Color, x: Float, y: Float, w: Float, h: Float, r: Float = 8f) {
                    drawRoundRect(
                        color,
                        Offset(left + x * scale, top + y * scale),
                        Size(w * scale, h * scale),
                        CornerRadius(r * scale),
                    )
                }
                rect(Color(0xFF8F9F80), -5f, 0f, 195f, 389f, 60f)
                rect(Color(0xFFB6C5A6), 2f, 7f, 181f, 375f, 57f)
                rect(Color(0xFF52705D), 15f, 26f, 155f, 62f, 24f)
                rect(Color(0xFF9DAC90), 18f, 94f, 149f, 23f, 6f)
                drawCircle(
                    Color(0xFF6D825E),
                    10f * scale,
                    Offset(left + 49f * scale, top + 106f * scale),
                    style = Stroke(3f * scale),
                )
                rect(Color(0xFF617B5F), 84f, 99f, 37f, 12f, 3f)
                rect(
                    if (vehicle.front.reading == true) Color(0xFFE1D5A0) else Color(0xFFBAC8A8),
                    14f,
                    127f,
                    157f,
                    124f,
                    8f,
                )
                rect(Color(0xFF94A688), 26f, 140f, 46f, 65f, 13f)
                rect(Color(0xFF94A688), 112f, 140f, 46f, 65f, 13f)
                rect(Color(0xFFB8C8A6), 34f, 135f, 30f, 12f, 5f)
                rect(Color(0xFFB8C8A6), 120f, 135f, 30f, 12f, 5f)
                rect(Color(0xFF9DAD8D), 84f, 147f, 17f, 51f, 5f)
                rect(
                    if (vehicle.rear.reading == true) Color(0xFFD7D29E) else Color(0xFF9FAF93),
                    14f,
                    260f,
                    157f,
                    88f,
                    8f,
                )
                rect(Color(0xFF8F9F80), 26f, 271f, 133f, 34f, 9f)
                rect(Color(0xFFB2C2A3), 30f, 266f, 58f, 10f, 4f)
                rect(Color(0xFFB2C2A3), 97f, 266f, 58f, 10f, 4f)
                rect(Color(0xFF607E64), 27f, 356f, 131f, 18f, 10f)
                rect(Color(0xFF8F9F80), -19f, 104f, 15f, 30f, 5f)
                rect(Color(0xFF8F9F80), 189f, 104f, 15f, 30f, 5f)
                drawLine(
                    Color(0xFF9EB18B),
                    Offset(left - 67f * scale, top + 174f * scale),
                    Offset(left + 15f * scale, top + 174f * scale),
                    1.2f,
                )
                drawLine(
                    Color(0xFF9EB18B),
                    Offset(left + 170f * scale, top + 300f * scale),
                    Offset(left + 243f * scale, top + 300f * scale),
                    1.2f,
                )
            }
            Column(
                Modifier.align(Alignment.Center).offset(y = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(60.dp))
                Text(
                    tr(
                        camp.room.app.R.string.camp_front,
                        when (vehicle.front.reading) {
                            true -> tr(camp.room.app.R.string.camp_reading)
                            false -> tr(camp.room.app.R.string.camp_resting)
                            null -> tr(camp.room.app.R.string.camp_check_needed)
                        },
                    ),
                    fontSize = 12.sp,
                    color = Color(0xFF193F33),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tr(
                        camp.room.app.R.string.camp_light_shade,
                        vehicle.front.ambient?.let { "${it}%" }
                            ?: tr(camp.room.app.R.string.camp_check_needed),
                        vehicle.front.blind?.let { "${it}%" }
                            ?: tr(camp.room.app.R.string.camp_check_needed),
                    ),
                    fontSize = 12.sp,
                    color = Color(0xFF43543B),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(72.dp))
                Text(
                    tr(
                        camp.room.app.R.string.camp_rear,
                        when (vehicle.rear.reading) {
                            true -> tr(camp.room.app.R.string.camp_relax_together)
                            false -> tr(camp.room.app.R.string.camp_resting)
                            null -> tr(camp.room.app.R.string.camp_check_needed)
                        },
                    ),
                    fontSize = 12.sp,
                    color = Color(0xFF193F33),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tr(
                        camp.room.app.R.string.camp_light_shade,
                        vehicle.rear.ambient?.let { "${it}%" }
                            ?: tr(camp.room.app.R.string.camp_check_needed),
                        vehicle.rear.blind?.let { "${it}%" }
                            ?: tr(camp.room.app.R.string.camp_check_needed),
                    ),
                    fontSize = 12.sp,
                    color = Color(0xFF43543B),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                Modifier.align(Alignment.CenterStart)
                    .widthIn(max = 125.dp)
                    .padding(start = 19.dp)
                    .offset(y = (-45).dp)
            ) {
                Icon(Icons.Outlined.Lightbulb, null, Modifier.size(19.dp), tint = CampPalette.Muted)
                Text(
                    tr(camp.room.app.R.string.camp_a_light_to_focus_by),
                    fontSize = 12.sp,
                    color = CampPalette.Muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when (vehicle.front.reading) {
                        true -> tr(camp.room.app.R.string.camp_reading_light_on)
                        false -> tr(camp.room.app.R.string.camp_reading_light_off)
                        null -> tr(camp.room.app.R.string.camp_state_needs_checking)
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                Modifier.align(Alignment.CenterEnd)
                    .widthIn(max = 125.dp)
                    .padding(end = 19.dp)
                    .offset(y = 105.dp)
            ) {
                Icon(Icons.Outlined.Air, null, Modifier.size(20.dp), tint = CampPalette.Muted)
                Text(
                    tr(camp.room.app.R.string.camp_a_comfortable_temperature),
                    fontSize = 12.sp,
                    color = CampPalette.Muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tr(camp.room.app.R.string.camp_target_c, number(vehicle.targetTemperature)),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    null,
                    Modifier.size(14.dp),
                    tint = CampPalette.Muted,
                )
                Text(
                    if (vehicle.mode == "demo")
                        tr(camp.room.app.R.string.camp_demo_simulator_observations)
                    else tr(camp.room.app.R.string.camp_pleos_vehicle_observations),
                    fontSize = 12.sp,
                    color = CampPalette.Muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
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
    Canvas(modifier) {
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
    val icon =
        when {
            id.contains("lantern") || id.contains("light") -> Icons.Outlined.Light
            id.contains("sleep") || id.contains("blanket") -> Icons.Outlined.Bedtime
            id.contains("chair") -> Icons.Outlined.Weekend
            else -> Icons.Outlined.Backpack
        }
    Box(
        modifier.background(if (id.contains("sleep")) Color(0xFFE4E9D8) else Color(0xFFEDECDD)),
        contentAlignment = Alignment.Center,
    ) {
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
            Modifier.size(65.dp),
            tint = if (id.contains("sleep")) Color(0xFF8D9F74) else Color(0xFFAE9670),
        )
    }
}
