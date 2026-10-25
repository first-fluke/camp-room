@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package camp.room.app

import camp.room.app.icons.CampIcons

import android.app.DatePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

@Composable
fun DiscoverScreen(s: CampUiState, vm: CampViewModel, onNearby: () -> Unit) {
    SectionTitle(
        tr(camp.room.app.R.string.camp_where_will_you_unwind_this_weekend),
        tr(camp.room.app.R.string.camp_find_a_place_that_calls_to_you),
        "FIND YOUR NEXT ESCAPE",
        action = {
            CampSecondary(
                tr(camp.room.app.R.string.camp_near_me),
                CampIcons.Crosshair,
                enabled = !s.busy,
                modifier = Modifier.testTag("nearby-campsites"),
                onClick = onNearby,
            )
        },
    )
    CampSurface {
        BoxWithConstraints {
            val wide = maxWidth > 720.dp
            if (wide)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RegionSelector(s.region, vm::region, Modifier.weight(1.2f))
                    DateSelector(
                        tr(camp.room.app.R.string.camp_check_in),
                        s.checkIn,
                        Modifier.weight(1f),
                    ) {
                        vm.dates(it, s.checkOut)
                    }
                    DateSelector(
                        tr(camp.room.app.R.string.camp_check_out),
                        s.checkOut,
                        Modifier.weight(1f),
                    ) {
                        vm.dates(s.checkIn, it)
                    }
                    GuestSelector(s.guests, vm::guests, Modifier.weight(.8f))
                    CampPrimary(
                        tr(camp.room.app.R.string.camp_find_campsites),
                        CampIcons.Search,
                        busy = s.busy,
                        modifier = Modifier.testTag("search-campsites"),
                        onClick = vm::search,
                    )
                }
            else
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                        RegionSelector(s.region, vm::region, Modifier.weight(1f))
                        GuestSelector(s.guests, vm::guests, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                        DateSelector(
                            tr(camp.room.app.R.string.camp_check_in),
                            s.checkIn,
                            Modifier.weight(1f),
                        ) {
                            vm.dates(it, s.checkOut)
                        }
                        DateSelector(
                            tr(camp.room.app.R.string.camp_check_out),
                            s.checkOut,
                            Modifier.weight(1f),
                        ) {
                            vm.dates(s.checkIn, it)
                        }
                    }
                    CampPrimary(
                        tr(camp.room.app.R.string.camp_find_campsites),
                        CampIcons.Search,
                        busy = s.busy,
                        modifier = Modifier.fillMaxWidth().testTag("search-campsites"),
                        onClick = vm::search,
                    )
                }
        }
        HorizontalDivider(color = CampPalette.Line)
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(CampIcons.SlidersHorizontal, null, Modifier.size(24.dp), tint = CampPalette.Muted)
            Text(
                tr(camp.room.app.R.string.camp_make_it_yours),
                fontSize = 22.sp,
                color = CampPalette.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            CampChip(
                tr(camp.room.app.R.string.camp_car_camping),
                s.carCamping,
                CampIcons.Vehicle,
                vm::carCamping,
            )
            listOf("shower", "electricity", "pets").forEach { code ->
                CampChip(
                    facilityName(code),
                    code in s.facilities,
                    when (code) {
                        "shower" -> CampIcons.Shower
                        "electricity" -> CampIcons.Lightning
                        else -> CampIcons.PawPrint
                    },
                ) {
                    vm.facility(code)
                }
            }
        }
    }
    SectionTitle(
        tr(camp.room.app.R.string.camp_places_to_slow_down, s.sites.size),
        tr(camp.room.app.R.string.camp_discover_a_place_that_makes_car_camping),
        action = {
            CampPill(
                if (s.data?.mode == "live") tr(camp.room.app.R.string.camp_external_data_connected)
                else tr(camp.room.app.R.string.camp_demo_campsite_collection)
            )
        },
    )
    if (s.locationSummary.isNotBlank())
        Text(
            s.locationSummary,
            fontSize = 22.sp,
            color = CampPalette.Muted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    if (s.sites.isEmpty())
        CampSurface(Modifier.fillMaxWidth()) {
            Text(
                tr(camp.room.app.R.string.camp_no_campsites_match_these_filters),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                tr(camp.room.app.R.string.camp_try_a_wider_region_or_fewer_facilities),
                color = CampPalette.Muted,
                fontSize = 22.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            CampSecondary(
                tr(camp.room.app.R.string.camp_reset_filters),
                CampIcons.Refresh,
                onClick = vm::resetFilters,
            )
        }
    else
        BoxWithConstraints {
            val columns = if (maxWidth > 1180.dp) 3 else if (maxWidth > 760.dp) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(21.dp)) {
                s.sites.chunked(columns).forEach { chunk ->
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        chunk.forEach { site ->
                            CampsiteCard(site, Modifier.weight(1f), s.distances[site.id]) {
                                vm.detail(site)
                            }
                        }
                        repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    Surface(color = CampPalette.Sage, shape = RoundedCornerShape(10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(21.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(CampIcons.Compass, null, tint = CampPalette.Muted)
            Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
                Text(
                    tr(camp.room.app.R.string.camp_found_your_place_let_the_rest_of),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tr(
                        camp.room.app.R.string.camp_weather_your_route_cabin_settings_and_essentials
                    ),
                    fontSize = 22.sp,
                    color = CampPalette.Muted,
                    modifier = Modifier.padding(top = 5.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { vm.tab(1) }) {
                Text(
                    tr(camp.room.app.R.string.camp_explore_my_trip),
                    fontSize = 22.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(CampIcons.ChevronRight, null, Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun RegionSelector(
    region: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable { expanded = true }
                .padding(vertical = 4.dp)
        ) {
            Text(
                tr(camp.room.app.R.string.camp_where_to),
                fontSize = 22.sp,
                color = CampPalette.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 5.dp),
            ) {
                Icon(
                    CampIcons.MapPin,
                    null,
                    Modifier.size(24.dp),
                    tint = CampPalette.Muted,
                )
                Text(
                    if (region == CampRegions.ALL) tr(camp.room.app.R.string.camp_all_regions)
                    else CampRegions.label(region),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 7.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    CampIcons.ChevronDown,
                    null,
                    Modifier.size(24.dp),
                    tint = CampPalette.Muted,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CampRegions.options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            tr(option.titleResource),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        onChange(option.apiValue)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun GuestSelector(guests: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier.heightIn(min = 56.dp)
                .clickable { expanded = true }
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Text(
                tr(camp.room.app.R.string.camp_who_s_coming),
                fontSize = 22.sp,
                color = CampPalette.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    CampIcons.Users,
                    null,
                    Modifier.size(24.dp),
                    tint = CampPalette.Muted,
                )
                Text(
                    guestCount(guests),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 7.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    CampIcons.ChevronDown,
                    null,
                    Modifier.size(24.dp),
                    tint = CampPalette.Muted,
                )
            }
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            (1..8).forEach { n ->
                DropdownMenuItem(
                    text = { Text(guestCount(n), maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        onChange(n)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun DateSelector(
    label: String,
    date: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier
            .heightIn(min = 56.dp)
            .clickable {
                val d = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
                DatePickerDialog(
                        context,
                        { _, year, month, day ->
                            onChange(LocalDate.of(year, month + 1, day).toString())
                        },
                        d.year,
                        d.monthValue - 1,
                        d.dayOfMonth,
                    )
                    .show()
            }
            .padding(vertical = 4.dp)
            .testTag(
                if (label == tr(camp.room.app.R.string.camp_check_in)) "date-check-in"
                else "date-check-out"
            )
    ) {
        Text(
            label,
            fontSize = 22.sp,
            color = CampPalette.Muted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(CampIcons.Calendar, null, Modifier.size(24.dp), tint = CampPalette.Muted)
            Text(
                displayDate(date),
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 7.dp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CampChip(
    text: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.heightIn(min = CampDesign.IconTarget),
        label = { Text(text, fontSize = 22.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(icon, null, Modifier.size(24.dp)) },
        shape = RoundedCornerShape(6.dp),
        colors =
            FilterChipDefaults.filterChipColors(
                selectedContainerColor = CampPalette.Sage,
                selectedLabelColor = CampPalette.Forest,
            ),
        border =
            FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selected,
                borderColor = CampPalette.Line,
                selectedBorderColor = CampPalette.Outline,
            ),
    )
}

@Composable
private fun CampsiteCard(
    site: Campsite,
    modifier: Modifier,
    distance: Double?,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.testTag("campsite-card-${site.id}"),
        shape = RoundedCornerShape(11.dp),
        color = CampPalette.Paper,
        border = androidx.compose.foundation.BorderStroke(1.dp, CampPalette.Line),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(165.dp).clickable(onClick = onClick)) {
                CampLandscape(site.id, Modifier.fillMaxSize())
                Box(Modifier.padding(13.dp)) {
                    CampPill(
                        site.availability.label,
                        if (site.availability.status == "limited") CampPalette.Warm
                        else CampPalette.Forest,
                    )
                }
                Text(
                    "CAMP ROOM COLLECTION",
                    fontSize = 22.sp,
                    letterSpacing = 1.4.sp,
                    color = CampPalette.OnSidebar,
                    modifier =
                        Modifier.align(Alignment.BottomStart)
                            .padding(15.dp)
                            .background(CampPalette.Deep, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        CampIcons.MapPin,
                        null,
                        Modifier.size(24.dp),
                        tint = CampPalette.Muted,
                    )
                    Text(
                        site.region +
                            (distance?.let {
                                tr(
                                    camp.room.app.R.string.camp_km_from_the_last_observed_location,
                                    number(it),
                                )
                            } ?: ""),
                        fontSize = 22.sp,
                        color = CampPalette.Muted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (site.sourceMode == "demo") tr(camp.room.app.R.string.camp_demo)
                        else tr(camp.room.app.R.string.camp_connected_data),
                        fontSize = 22.sp,
                        color = CampPalette.Muted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onClick),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        site.name,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        CampIcons.ArrowUpRight,
                        null,
                        Modifier.size(24.dp),
                        tint = CampPalette.Muted,
                    )
                }
                Text(site.description, fontSize = 22.sp, color = CampPalette.Muted, maxLines = 1)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    site.facilities.take(3).forEach { CampPill(facilityName(it)) }
                }
                HorizontalDivider(color = CampPalette.Line)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        won(site.price),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (site.price != null)
                        Text(
                            tr(camp.room.app.R.string.camp_night),
                            fontSize = 22.sp,
                            color = CampPalette.Muted,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) {
                        Text(
                            tr(camp.room.app.R.string.camp_view_details),
                            fontSize = 22.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(CampIcons.ChevronRight, null, Modifier.size(24.dp))
                    }
                }
                Text(
                    tr(
                        camp.room.app.R.string.camp_checked,
                        site.availability.provider,
                        site.availability.checkedAt.take(16).replace('T', ' '),
                    ),
                    fontSize = 22.sp,
                    color = CampPalette.Muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun TripScreen(s: CampUiState, vm: CampViewModel, onArrival: () -> Unit, onSpeak: () -> Unit) {
    val data = s.data ?: return
    val trip = data.trip
    val site = s.tripSite
    SectionTitle(
        tr(camp.room.app.R.string.camp_getting_ready_one_little_step_at_a),
        tr(camp.room.app.R.string.camp_your_place_your_journey_and_your_last),
        "YOUR UPCOMING ESCAPE",
        action = {
            CampSecondary(
                tr(camp.room.app.R.string.camp_change_place_or_dates),
                CampIcons.Pencil,
                onClick = { vm.tab(0) },
            )
        },
    )
    if (trip == null || site == null) {
        CampSurface(Modifier.fillMaxWidth()) {
            Text(
                tr(camp.room.app.R.string.camp_shall_we_plan_our_next_escape),
                fontSize = 22.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            CampPrimary(
                tr(camp.room.app.R.string.camp_find_a_campsite),
                CampIcons.Compass,
                onClick = { vm.tab(0) },
            )
        }
        return
    }
    Surface(
        color = CampPalette.Paper,
        shape = RoundedCornerShape(11.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CampPalette.Line),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 195.dp)) {
            Box(Modifier.width(260.dp).height(210.dp)) {
                CampLandscape(site.id, Modifier.fillMaxSize())
                Text(
                    "OUR NEXT CAMP",
                    fontSize = 22.sp,
                    letterSpacing = 1.5.sp,
                    color = CampPalette.OnSidebar,
                    modifier = Modifier.align(Alignment.BottomStart).padding(22.dp)
                        .background(CampPalette.Deep, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                Modifier.weight(1f).padding(26.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    tr(camp.room.app.R.string.camp_my_upcoming_trip),
                    fontSize = 22.sp,
                    color = CampPalette.Muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    site.name,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    site.address,
                    fontSize = 22.sp,
                    color = CampPalette.Muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconLabel(
                        CampIcons.Calendar,
                        "${displayDate(trip.checkIn)} — ${displayDate(trip.checkOut)}",
                    )
                    IconLabel(CampIcons.Users, guestCount(trip.guests))
                }
                HorizontalDivider(color = CampPalette.Line)
                Text(
                    tr(
                        camp.room.app.R.string.camp_check_in_check_out_trip_saved,
                        site.checkInTime,
                        site.checkOutTime,
                    ),
                    fontSize = 22.sp,
                    color = CampPalette.Muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    CampSurface(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr(camp.room.app.R.string.camp_a_little_closer_to_your_journey),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tr(camp.room.app.R.string.camp_use_location_arrival_alerts_and_spoken_guidance),
                    fontSize = 22.sp,
                    color = CampPalette.Muted,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CampSecondary(
                tr(camp.room.app.R.string.camp_arrival_alert),
                CampIcons.BellRinging,
                enabled = !s.busy,
                modifier = Modifier.testTag("arrival-watch"),
                onClick = onArrival,
            )
            CampSecondary(
                tr(camp.room.app.R.string.camp_hear_trip_briefing),
                CampIcons.Speaker,
                enabled = !s.busy,
                modifier = Modifier.testTag("speak-briefing"),
                onClick = onSpeak,
            )
        }
        if (s.arrivalMessage.isNotBlank())
            Text(
                s.arrivalMessage,
                fontSize = 22.sp,
                color = CampPalette.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        if (s.ttsStatus.isNotBlank())
            Text(
                tr(camp.room.app.R.string.camp_voice_guidance_099, assistStatusLabel(s.ttsStatus)),
                fontSize = 22.sp,
                color = CampPalette.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
    }
    BoxWithConstraints {
        if (maxWidth > 690.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                WeatherCard(s.weather ?: data.weather, Modifier.weight(1f))
                RouteCard(s, vm, Modifier.weight(1f))
            }
        else
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                WeatherCard(s.weather ?: data.weather, Modifier.fillMaxWidth())
                RouteCard(s, vm, Modifier.fillMaxWidth())
            }
    }
    CampSurface(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr(camp.room.app.R.string.camp_a_few_promises_for_a_peaceful_stay),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                site.rules.forEach {
                    Text(
                        "✓  ${it}",
                        fontSize = 22.sp,
                        color = CampPalette.Muted,
                        modifier = Modifier.padding(top = 7.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            CampSecondary(
                tr(camp.room.app.R.string.camp_set_up_our_cabin),
                CampIcons.ChevronRight,
                onClick = { vm.tab(2) },
            )
        }
    }
    SectionTitle(
        tr(camp.room.app.R.string.camp_a_few_things_worth_bringing_along),
        tr(camp.room.app.R.string.camp_thoughtful_essentials_for_the_facilities_and_weather),
        "READY FOR THE OUTDOORS",
        action = {
            TextButton(onClick = { vm.cart(true) }) {
                Text(
                    tr(camp.room.app.R.string.camp_view_cart),
                    fontSize = 22.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(CampIcons.ChevronRight, null, Modifier.size(24.dp))
            }
        },
    )
    ProductGrid(data.products, s.busy, vm::addProduct)
    if (data.orders.isNotEmpty())
        CampSurface(Modifier.fillMaxWidth()) {
            Text(
                tr(camp.room.app.R.string.camp_essentials_orders),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            data.orders.take(3).forEach { order ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CampPill(tr(camp.room.app.R.string.camp_demo_order_confirmed))
                    Text(
                        order.id,
                        fontSize = 22.sp,
                        modifier = Modifier.weight(1f).padding(horizontal = 13.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        tr(camp.room.app.R.string.camp_expected, order.eta),
                        fontSize = 22.sp,
                        color = CampPalette.Muted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        won(order.total),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 20.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
}

@Composable
private fun WeatherCard(weather: Weather, modifier: Modifier) {
    CampSurface(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                CampIcons.Sun,
                null,
                tint = CampPalette.Muted,
                modifier = Modifier.size(24.dp),
            )
            Text(
                tr(camp.room.app.R.string.camp_weather_during_your_stay),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            CampPill(
                if (weather.demo) tr(camp.room.app.R.string.camp_demo_forecast)
                else tr(camp.room.app.R.string.camp_weather_forecast)
            )
        }
        if (weather.status == "unavailable")
            Text(
                weather.message.ifBlank {
                    tr(camp.room.app.R.string.camp_a_forecast_isn_t_available_for_these)
                },
                fontSize = 22.sp,
                color = CampPalette.Muted,
                modifier = Modifier.heightIn(min = 125.dp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(CampIcons.Artwork.Sun, null, Modifier.size(65.dp), tint = CampPalette.Warm)
                Column(Modifier.padding(start = 22.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "${number(weather.low)}°",
                            fontSize = 43.sp,
                            fontWeight = FontWeight.Light,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            tr(camp.room.app.R.string.camp_overnight_low),
                            fontSize = 22.sp,
                            color = CampPalette.Muted,
                            modifier = Modifier.padding(start = 9.dp, bottom = 10.dp),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        weather.summary,
                        fontSize = 22.sp,
                        color = CampPalette.Muted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider(color = CampPalette.Line)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                weather.forecast.take(5).forEachIndexed { index, f ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text(
                            if (f.time.length > 10) f.time.substring(11).take(5) else f.time,
                            fontSize = 22.sp,
                            color = CampPalette.Muted,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            if (index > 2) CampIcons.Artwork.MoonStars else CampIcons.Artwork.Sun,
                            null,
                            Modifier.size(24.dp),
                            tint = CampPalette.Warm,
                        )
                        Text(
                            "${number(f.temperature)}°",
                            fontSize = 22.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Text(
                tr(
                    camp.room.app.R.string.camp_rain_chance_bring_a_warm_sleeping_bag,
                    number(weather.rain),
                ),
                fontSize = 22.sp,
                color = CampPalette.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "${weather.source} · ${weather.updatedAt.take(16).replace('T',' ')}",
            fontSize = 22.sp,
            color = CampPalette.Muted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RouteCard(s: CampUiState, vm: CampViewModel, modifier: Modifier) {
    val route = s.route
    CampSurface(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                CampIcons.NavigationArrow,
                null,
                tint = CampPalette.Muted,
                modifier = Modifier.size(24.dp),
            )
            Text(
                tr(camp.room.app.R.string.camp_the_journey_in_your_car),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            CampPill(
                if (BuildConfig.IS_DEMO) tr(camp.room.app.R.string.camp_demo_route)
                else tr(camp.room.app.R.string.camp_pleos_route)
            )
        }
        Box(
            Modifier.fillMaxWidth()
                .height(106.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(CampPalette.Sage)
        ) {
            val routePaper = CampPalette.Paper
            val routeForest = CampPalette.Forest
            Canvas(Modifier.fillMaxSize()) {
                val p =
                    Path().apply {
                        moveTo(size.width * .13f, size.height * .8f)
                        cubicTo(
                            size.width * .45f,
                            size.height * .1f,
                            size.width * .7f,
                            size.height * 1.2f,
                            size.width * .88f,
                            size.height * .2f,
                        )
                    }
                drawPath(p, Color(0xFF9CB88B), style = Stroke(width = 4f))
                drawCircle(routePaper, 19.dp.toPx(), Offset(size.width * .13f, size.height * .8f))
                drawCircle(routeForest, 19.dp.toPx(), Offset(size.width * .88f, size.height * .2f))
            }
            Icon(
                CampIcons.Vehicle,
                null,
                tint = CampPalette.Muted,
                modifier =
                    Modifier.align(Alignment.BottomStart)
                        .padding(start = 39.dp, bottom = 5.dp)
                        .size(24.dp),
            )
            Icon(
                CampIcons.Tent,
                null,
                tint = CampPalette.OnForest,
                modifier =
                    Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 40.dp).size(24.dp),
            )
            Text(
                if (route?.chargingStop == true)
                    tr(camp.room.app.R.string.camp_includes_a_charging_stop)
                else tr(camp.room.app.R.string.camp_an_easy_ride_to_your_retreat),
                fontSize = 22.sp,
                color = CampPalette.Muted,
                modifier = Modifier.align(Alignment.Center),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric(tr(camp.room.app.R.string.camp_distance), "${number(route?.distance)}", "km")
            Metric(
                tr(camp.room.app.R.string.camp_estimated_time),
                route?.minutes?.let { tr(camp.room.app.R.string.camp_hr_min, it / 60, it % 60) }
                    ?: "—",
                "",
            )
            Metric(
                tr(camp.room.app.R.string.camp_estimated_arrival_charge),
                number(route?.arrivalSoc),
                "%",
            )
        }
        CampPrimary(
            if (s.navigationLaunched || s.data?.trip?.navigationStarted == true)
                if (BuildConfig.IS_DEMO) tr(camp.room.app.R.string.camp_demo_guidance_running)
                else tr(camp.room.app.R.string.camp_guidance_request_sent)
            else tr(camp.room.app.R.string.camp_start_guidance),
            CampIcons.NavigationArrow,
            busy = s.busy,
            modifier = Modifier.fillMaxWidth().testTag("start-navigation"),
            onClick = vm::navigation,
        )
        Text(
            tr(
                camp.room.app.R.string.camp_current_soc_estimated_use_kwh_nyour_actual,
                number(s.data?.vehicle?.soc),
                number(route?.consumption),
            ),
            fontSize = 22.sp,
            color = CampPalette.Muted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String) {
    Column {
        Text(
            label,
            fontSize = 22.sp,
            color = CampPalette.Muted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-.8).sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                unit,
                fontSize = 22.sp,
                color = CampPalette.Muted,
                modifier = Modifier.padding(start = 3.dp, bottom = 5.dp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun IconLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(24.dp), tint = CampPalette.Muted)
        Text(
            text,
            fontSize = 22.sp,
            modifier = Modifier.padding(start = 7.dp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProductGrid(products: List<Product>, busy: Boolean, onAdd: (String) -> Unit) {
    BoxWithConstraints {
        val columns = if (maxWidth > 1180.dp) 3 else if (maxWidth > 760.dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            products.chunked(columns).forEach { chunk ->
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    chunk.forEach { product ->
                        Surface(
                            color = CampPalette.Paper,
                            shape = RoundedCornerShape(10.dp),
                            border =
                                androidx.compose.foundation.BorderStroke(1.dp, CampPalette.Line),
                            modifier = Modifier.weight(1f),
                        ) {
                            Column {
                                ProductArtwork(product.id, Modifier.fillMaxWidth().height(130.dp))
                                Column(
                                    Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    Text(
                                        product.category,
                                        fontSize = 22.sp,
                                        color = CampPalette.Muted,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        product.name,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        product.reason,
                                        fontSize = 22.sp,
                                        color = CampPalette.Muted,
                                        minLines = 2,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            won(product.price),
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.weight(1f))
                                        FilledTonalIconButton(
                                            colors =
                                                IconButtonDefaults.filledTonalIconButtonColors(
                                                    containerColor = CampPalette.Sage,
                                                    contentColor = CampPalette.Forest,
                                                    disabledContainerColor = CampPalette.Canvas,
                                                    disabledContentColor = CampPalette.Muted,
                                                ),
                                            onClick = { onAdd(product.id) },
                                            enabled = !busy && product.stock > 0,
                                            modifier =
                                                Modifier.size(34.dp)
                                                    .testTag("product-add-${product.id}"),
                                            shape = RoundedCornerShape(6.dp),
                                        ) {
                                            Icon(
                                                CampIcons.Plus,
                                                tr(camp.room.app.R.string.camp_add, product.name),
                                                Modifier.size(24.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
