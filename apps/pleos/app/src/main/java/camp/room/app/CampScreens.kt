@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package camp.room.app

import camp.room.app.icons.CampIcons

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneOffset

@Composable
fun DiscoverScreen(s: CampUiState, vm: CampViewModel, onNearby: () -> Unit) {
    DiscoverHeading(s, onNearby)
    CampSurface {
        CampsiteSearchForm(s, vm)
        HorizontalDivider(color = CampPalette.Line)
        CampsiteFilters(s, vm)
    }
    if (s.catalogPageNumber > 0) {
        val sourceTotal = s.catalogSourceTotal
        val hasFilters = s.region != "전체" || s.carCamping || s.facilities.isNotEmpty()
        Text(
            when {
                sourceTotal != null && sourceTotal != s.catalogTotal ->
                    tr(R.string.camp_catalog_results_of_total, s.catalogTotal, sourceTotal)
                sourceTotal != null && !hasFilters ->
                    tr(R.string.camp_catalog_total, sourceTotal)
                else -> tr(R.string.camp_catalog_results, s.catalogTotal)
            },
            fontSize = 22.sp,
            color = CampPalette.Muted,
            modifier = Modifier.testTag("catalog-count-summary"),
        )
    }
    if (s.locationSummary.isNotBlank())
        Text(
            s.locationSummary,
            fontSize = 22.sp,
            color = CampPalette.Muted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    CampCatalogPager(s,vm)
    if (s.sites.isEmpty() && !s.catalogLoading)
        CampSurface(Modifier.fillMaxWidth()) {
            Text(
                if(s.catalogNeedsSearch)tr(R.string.camp_catalog_search_changed) else tr(camp.room.app.R.string.camp_no_campsites_match_these_filters),
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
        Column(
            Modifier.fillMaxWidth().padding(21.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(CampIcons.Compass, null, tint = CampPalette.Muted)
            Column(Modifier.fillMaxWidth()) {
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
private fun DiscoverHeading(s: CampUiState, onNearby: () -> Unit) {
    val title = tr(R.string.camp_where_will_you_unwind_this_weekend)
    val subtitle = tr(R.string.camp_find_a_place_that_calls_to_you)
    val nearby = tr(R.string.camp_near_me)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val titleStyle = LocalTextStyle.current.copy(
        fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.7).sp,
    )
    val subtitleStyle = LocalTextStyle.current.copy(fontSize = 22.sp)
    val textWidth = with(density) {
        maxOf(
            measurer.measure(title, titleStyle, softWrap = false, maxLines = 1).size.width,
            measurer.measure(subtitle, subtitleStyle, softWrap = false, maxLines = 1).size.width,
        ).toDp() + 2.dp
    }
    val actionWidth = with(density) {
        measurer.measure(nearby, subtitleStyle, softWrap = false, maxLines = 1).size.width.toDp()
    } + 24.dp + 7.dp + 48.dp
    val heading: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = titleStyle)
            Text(subtitle, style = subtitleStyle, color = CampPalette.Muted)
        }
    }
    val action: @Composable () -> Unit = {
        CampSecondary(
            nearby,
            CampIcons.Crosshair,
            enabled = !s.busy && !s.catalogLoading && s.sites.isNotEmpty(),
            modifier = Modifier.testTag("nearby-campsites"),
            onClick = onNearby,
        )
    }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("discover-heading")) {
        if (maxWidth >= textWidth + actionWidth + 24.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                heading(Modifier.weight(1f))
                action()
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                heading(Modifier.fillMaxWidth())
                Box(Modifier.align(Alignment.End)) { action() }
            }
        }
    }
}

@Composable
private fun CampsiteSearchForm(s: CampUiState, vm: CampViewModel) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = LocalTextStyle.current.copy(fontSize = 22.sp)
    val valueStyle = labelStyle.copy(fontWeight = FontWeight.Medium)
    fun textWidth(text: String, emphasized: Boolean = false): Dp = with(density) {
        textMeasurer.measure(
            text = text,
            style = if (emphasized) valueStyle else labelStyle,
            maxLines = 1,
            softWrap = false,
        ).size.width.toDp() + 2.dp
    }

    val checkInLabel = tr(R.string.camp_check_in)
    val checkOutLabel = tr(R.string.camp_check_out)
    // Keep the default prompt readable; longer selected region names may ellipsize.
    val regionWidth = maxOf(
        textWidth(tr(R.string.camp_where_to)),
        textWidth(tr(R.string.camp_all_regions), emphasized = true) + 55.dp,
        48.dp,
    )
    val guestWidth = maxOf(
        textWidth(tr(R.string.camp_who_s_coming)),
        textWidth(guestCount(s.guests), emphasized = true) + 55.dp,
        48.dp,
    )
    val checkInWidth = maxOf(
        textWidth(checkInLabel),
        textWidth(displayDate(s.checkIn), emphasized = true) + 31.dp,
        48.dp,
    )
    val checkOutWidth = maxOf(
        textWidth(checkOutLabel),
        textWidth(displayDate(s.checkOut), emphasized = true) + 31.dp,
        48.dp,
    )
    val fieldGap = 16.dp

    BoxWithConstraints(Modifier.fillMaxWidth().testTag("campsite-search-form")) {
        val availableWidth = maxWidth
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (availableWidth >= regionWidth + guestWidth + checkInWidth + checkOutWidth + fieldGap * 3) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(fieldGap)) {
                    RegionSelector(s.region, vm::region, Modifier.weight(regionWidth.value))
                    DateSelector(checkInLabel, s.checkIn, Modifier.weight(checkInWidth.value)) {
                        vm.dates(it, s.checkOut)
                    }
                    DateSelector(checkOutLabel, s.checkOut, Modifier.weight(checkOutWidth.value)) {
                        vm.dates(s.checkIn, it)
                    }
                    GuestSelector(s.guests, vm::guests, Modifier.weight(guestWidth.value))
                }
            } else {
                CampsiteSearchPair(
                    firstWidth = regionWidth,
                    secondWidth = guestWidth,
                    first = { RegionSelector(s.region, vm::region, it) },
                    second = { GuestSelector(s.guests, vm::guests, it) },
                )
                CampsiteSearchPair(
                    firstWidth = checkInWidth,
                    secondWidth = checkOutWidth,
                    first = { modifier ->
                        DateSelector(checkInLabel, s.checkIn, modifier) {
                            vm.dates(it, s.checkOut)
                        }
                    },
                    second = { modifier ->
                        DateSelector(checkOutLabel, s.checkOut, modifier) {
                            vm.dates(s.checkIn, it)
                        }
                    },
                )
            }
            CampPrimary(
                tr(R.string.camp_find_campsites),
                CampIcons.Search,
                busy = s.busy || s.catalogLoading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("search-campsites"),
                onClick = vm::search,
            )
        }
    }
}

@Composable
private fun CampsiteSearchPair(
    firstWidth: Dp,
    secondWidth: Dp,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= firstWidth + secondWidth + 16.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                first(Modifier.weight(firstWidth.value))
                second(Modifier.weight(secondWidth.value))
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CampsiteFilters(s: CampUiState, vm: CampViewModel) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        if (maxWidth >= 760.dp * fontScale) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                CampsiteFilterLabel()
                CampsiteFilterActions(s, vm, Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CampsiteFilterLabel()
                CampsiteFilterActions(s, vm, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CampsiteFilterLabel() {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(CampIcons.SlidersHorizontal, null, Modifier.size(24.dp), tint = CampPalette.Muted)
        Text(tr(R.string.camp_facilities_conditions), fontSize = 22.sp, color = CampPalette.Muted)
    }
}

@Composable
private fun CampsiteFilterActions(s: CampUiState, vm: CampViewModel, modifier: Modifier) {
    FlowRow(modifier,
        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CampChip(tr(R.string.camp_car_camping), s.carCamping, CampIcons.Vehicle, vm::carCamping)
        listOf("shower", "electricity", "pets").forEach { code ->
            CampChip(facilityName(code), code in s.facilities,
                when (code) {
                    "shower" -> CampIcons.Shower
                    "electricity" -> CampIcons.Lightning
                    else -> CampIcons.PawPrint
                }) { vm.facility(code) }
        }
    }
}

@Composable
private fun CampCatalogPager(s:CampUiState,vm:CampViewModel) {
    if(s.catalogLoading) {
        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("catalog-loading"))
        Text(tr(R.string.camp_catalog_loading),fontSize=22.sp,color=CampPalette.Muted)
    }
    if(s.catalogPageNumber>0) {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick=vm::previousCampsites,enabled=s.catalogHasPrevious&&!s.catalogLoading&&!s.catalogNeedsSearch,
                modifier=Modifier.heightIn(min=48.dp).testTag("catalog-previous")) {Text(tr(R.string.camp_catalog_previous),fontSize=22.sp)}
            Text(tr(R.string.camp_catalog_page,s.catalogPageNumber),fontSize=22.sp,color=CampPalette.Muted,
                modifier=Modifier.padding(vertical=12.dp).testTag("catalog-page-info"))
            OutlinedButton(onClick=vm::nextCampsites,enabled=s.catalogHasNext&&!s.catalogLoading&&!s.catalogNeedsSearch,
                modifier=Modifier.heightIn(min=48.dp).testTag("catalog-next")) {Text(tr(R.string.camp_catalog_next),fontSize=22.sp)}
        }
        if(!s.catalogHasNext&&!s.catalogLoading)Text(tr(R.string.camp_catalog_last),fontSize=22.sp,color=CampPalette.Muted)
    }
    if(s.catalogError.isNotBlank()) {
        Text(s.catalogError,fontSize=22.sp,color=CampPalette.Muted)
        CampSecondary(tr(R.string.camp_catalog_retry),CampIcons.Refresh,enabled=!s.catalogLoading,
            modifier=Modifier.testTag("catalog-retry"),onClick=vm::retryCampsites)
    }
    if(s.catalogWarning.isNotBlank())Text(s.catalogWarning,fontSize=22.sp,color=CampPalette.Muted)
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
                maxLines = 1,
                softWrap = false,
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
                    modifier = Modifier.weight(1f).padding(start = 7.dp),
                    maxLines = 1,
                    softWrap = false,
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
                maxLines = 1,
                softWrap = false,
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
                    modifier = Modifier.weight(1f).padding(start = 7.dp),
                    maxLines = 1,
                    softWrap = false,
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
    var showPicker by remember { mutableStateOf(false) }
    Column(
        modifier
            .heightIn(min = 56.dp)
            .clickable { showPicker = true }
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
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(CampIcons.Calendar, null, Modifier.size(24.dp), tint = CampPalette.Muted)
            Text(
                displayDate(date),
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).padding(start = 7.dp),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (showPicker) {
        val initial = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        val maxPickerHeight = (LocalConfiguration.current.screenHeightDp * .68f).dp
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let {
                        onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    }
                    showPicker = false
                }, enabled = picker.selectedDateMillis != null,
                    modifier = Modifier.heightIn(min = CampDesign.IconTarget).testTag("date-confirm")) {
                    Text(tr(R.string.camp_date_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false },
                    modifier = Modifier.heightIn(min = CampDesign.IconTarget).testTag("date-cancel")) {
                    Text(tr(R.string.camp_date_cancel))
                }
            },
        ) {
            DatePicker(state = picker,
                modifier = Modifier.heightIn(max = maxPickerHeight).verticalScroll(rememberScrollState()))
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

private fun campsiteRegionLabel(region: String): String {
    val plain = region.substringBefore(" · ").substringBefore(" 연동 데이터")
        .substringBefore(" Connected data").substringBefore(" Demo").trim()
    val key = plain.lowercase(java.util.Locale.ROOT)
        .replace("-", "").replace("_", "").replace(" ", "")
    val resource = when (key) {
        "서울", "서울시", "서울특별시", "seoul" -> R.string.camp_region_seoul
        "부산", "부산시", "부산광역시", "busan" -> R.string.camp_region_busan
        "대구", "대구시", "대구광역시", "daegu" -> R.string.camp_region_daegu
        "인천", "인천시", "인천광역시", "incheon" -> R.string.camp_region_incheon
        "광주", "광주시", "광주광역시", "gwangju" -> R.string.camp_region_gwangju
        "대전", "대전시", "대전광역시", "daejeon" -> R.string.camp_region_daejeon
        "울산", "울산시", "울산광역시", "ulsan" -> R.string.camp_region_ulsan
        "세종", "세종시", "세종특별자치시", "sejong" -> R.string.camp_region_sejong
        "경기", "경기도", "gyeonggi", "gyeonggido" -> R.string.camp_gyeonggi
        "강원", "강원도", "강원특별자치도", "gangwon", "gangwondo" -> R.string.camp_gangwon
        "충북", "충청북도", "chungbuk", "chungcheongbukdo" -> R.string.camp_chungbuk
        "충남", "충청남도", "chungnam", "chungcheongnamdo" -> R.string.camp_region_chungnam
        "전북", "전라북도", "전북특별자치도", "jeonbuk", "jeollabukdo" -> R.string.camp_region_jeonbuk
        "전남", "전라남도", "jeonnam", "jeollanamdo" -> R.string.camp_region_jeonnam
        "경북", "경상북도", "gyeongbuk", "gyeongsangbukdo" -> R.string.camp_gyeongbuk
        "경남", "경상남도", "gyeongnam", "gyeongsangnamdo" -> R.string.camp_region_gyeongnam
        "제주", "제주도", "제주특별자치도", "jeju", "jejudo" -> R.string.camp_jeju
        else -> null
    }
    return resource?.let { CampText.get(it) }
        ?: plain.ifBlank { CampText.get(R.string.camp_region_unknown) }
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
                CampCampsiteImage(site, Modifier.fillMaxSize())
            }
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(CampIcons.MapPin, null, Modifier.size(24.dp), tint = CampPalette.Muted)
                    Text(campsiteRegionLabel(site.region), fontSize = 22.sp,
                        color = CampPalette.Muted, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (distance != null) {
                    Text(tr(R.string.camp_km_from_the_last_observed_location, number(distance)).trim().removePrefix("·").trim(),
                        fontSize = 22.sp, color = CampPalette.Muted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
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

                    )
                    Icon(
                        CampIcons.ArrowUpRight,
                        null,
                        Modifier.size(24.dp),
                        tint = CampPalette.Muted,
                    )
                }
                Text(site.description, fontSize = 22.sp, color = CampPalette.Muted, maxLines=3, overflow=TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    site.facilities.take(3).forEach { CampPill(facilityName(it)) }
                }
                HorizontalDivider(color = CampPalette.Line)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val verifiedPrice = site.price.takeUnless { site.sourceMode == "demo" }
                        Text(won(verifiedPrice), fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        if (verifiedPrice != null) {
                            Text(tr(R.string.camp_night), fontSize = 22.sp, color = CampPalette.Muted)
                        }
                    }
                    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                        Text(tr(R.string.camp_view_details), fontSize = 22.sp)
                        Spacer(Modifier.width(6.dp))
                        Icon(CampIcons.ChevronRight, null, Modifier.size(24.dp))
                    }
                }
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
        BoxWithConstraints(Modifier.fillMaxWidth().testTag("trip-summary")) {
            if (maxWidth > 820.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale) {
                Row(Modifier.fillMaxWidth()) {
                    CampCampsiteImage(site, Modifier.width(240.dp).height(240.dp))
                    TripSiteSummary(site, trip, Modifier.weight(1f))
                }
            } else {
                Column {
                    CampCampsiteImage(site, Modifier.fillMaxWidth().height(150.dp))
                    TripSiteSummary(site, trip, Modifier.fillMaxWidth())
                }
            }
        }
    }
    CampSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.fillMaxWidth()) {
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

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
                enabled = !s.busy && s.ttsRetryable,
                modifier = Modifier.testTag("speak-briefing"),
                onClick = onSpeak,
            )

            }
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
        if (s.ttsMessage.isNotBlank())
            Text(
                s.ttsMessage,
                fontSize = 22.sp,
                color = CampPalette.Muted,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
    }
    BoxWithConstraints {
        if (maxWidth > 1000.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale)
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
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.fillMaxWidth()) {
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            CampSecondary(
                tr(camp.room.app.R.string.camp_set_up_our_cabin),
                CampIcons.ChevronRight,
                onClick = { vm.tab(2) },
            )

            }
        }
    }
    SectionTitle(
        tr(camp.room.app.R.string.camp_a_few_things_worth_bringing_along),
        tr(camp.room.app.R.string.camp_thoughtful_essentials_for_the_facilities_and_weather),
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
    CampFoodChecklist(
        tripKey = "${trip.campsiteId.length}:${trip.campsiteId}|${trip.checkIn}|${trip.checkOut}",
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
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr(R.string.camp_demo_order_confirmed), fontSize = 22.sp,
                        color = CampPalette.Muted)
                    Text(
                        order.id,
                        fontSize = 22.sp,
                        modifier = Modifier.fillMaxWidth(),
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
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
}

@Composable
private fun WeatherCard(weather: Weather, modifier: Modifier) {
    val hasForecast = weather.status != "unavailable" && !weather.demo
    CampSurface(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(CampIcons.Sun, null, tint = CampPalette.Muted, modifier = Modifier.size(24.dp))
            Text(tr(R.string.camp_weather_during_your_stay), fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        if (!hasForecast) {
            Text(if (weather.demo) tr(R.string.camp_a_forecast_isn_t_available_for_these)
                else weather.message.ifBlank { tr(R.string.camp_a_forecast_isn_t_available_for_these) },
                fontSize = 22.sp, color = CampPalette.Muted)
        } else {
            FlowRow(verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("${number(weather.low)}°", fontSize = 43.sp, fontWeight = FontWeight.Light)
                Text(tr(R.string.camp_overnight_low), fontSize = 22.sp,
                    color = CampPalette.Muted, modifier = Modifier.align(Alignment.CenterVertically))
            }
            Text(weather.summary, fontSize = 22.sp, color = CampPalette.Muted)
            HorizontalDivider(color = CampPalette.Line)
            val hours = weather.forecast.take(5)
            if (hours.isNotEmpty()) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val cellWidth = 128.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                    val rowWidth = maxOf(maxWidth, cellWidth * hours.size + 8.dp * (hours.size - 1))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        Row(Modifier.width(rowWidth), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            hours.forEach { forecast ->
                                Column(Modifier.weight(1f),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Text(if (forecast.time.length > 11) forecast.time.substring(11).take(5) else forecast.time,
                                        fontSize = 22.sp, color = CampPalette.Muted,
                                        textAlign = TextAlign.Center)
                                    Text(forecast.condition, fontSize = 22.sp, color = CampPalette.Muted,
                                        textAlign = TextAlign.Center, minLines = 2)
                                    Text("${number(forecast.temperature)}°", fontSize = 22.sp,
                                        textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }
            Text(tr(R.string.camp_rain_probability, number(weather.rain)),
                fontSize = 22.sp, color = CampPalette.Muted)
        }
        if (hasForecast) {
            val attribution = listOf(weather.source.trim(),
                weather.updatedAt.take(16).replace('T', ' ').trim())
                .filter { it.isNotBlank() }.joinToString(" · ")
            if (attribution.isNotBlank()) {
                Text(attribution, fontSize = 22.sp, color = CampPalette.Muted)
            }
        }
    }
}

@Composable
private fun RouteCard(s: CampUiState, vm: CampViewModel, modifier: Modifier) {
    val route = s.route
    val connected = s.data?.vehicle?.isConnected == true
    // A verified SDK preview may omit ETA; keep its known distance/energy visible.
    // Route creation rejects unconfirmed destinations before populating distance.
    val routeReady = connected && route?.distance?.let { it.isFinite() && it >= 0 } == true
    CampSurface(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(CampIcons.NavigationArrow, null, tint = CampPalette.Muted,
                modifier = Modifier.size(24.dp))
            Text(tr(R.string.camp_the_journey_in_your_car), fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        if (!connected) {
            Text(tr(R.string.vehicle_connection_needed), fontSize = 22.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.testTag("route-connection-needed"))
            Text(tr(R.string.vehicle_route_connection_needed), fontSize = 22.sp,
                color = CampPalette.Muted)
        } else if (!routeReady || route == null) {
            Text(tr(R.string.camp_route_not_ready), fontSize = 22.sp, color = CampPalette.Muted)
        } else {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric(tr(R.string.camp_distance), number(route.distance), "km")
                Metric(tr(R.string.camp_estimated_time),
                    route.minutes?.let { tr(R.string.camp_hr_min, it / 60, it % 60) } ?: "—", "")
                Metric(tr(R.string.camp_estimated_arrival_charge), number(route.arrivalSoc), "%")
            }
            Text(tr(R.string.camp_current_soc_estimated_use_kwh_nyour_actual,
                number(s.data?.vehicle?.soc), number(route.consumption)),
                fontSize = 22.sp, color = CampPalette.Muted)
        }
        CampPrimary(
            if (routeReady && (s.navigationLaunched || s.data?.trip?.navigationStarted == true))
                tr(R.string.camp_guidance_request_sent)
            else tr(R.string.camp_start_guidance),
            CampIcons.NavigationArrow,
            enabled = routeReady,
            busy = s.busy || s.catalogLoading,
            modifier = Modifier.fillMaxWidth().testTag("start-navigation"),
            onClick = vm::navigation,
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
            modifier = Modifier.weight(1f).padding(start = 7.dp),
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

                                    )
                                    FlowRow(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            won(product.price),
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (product.stock <= 0)
                                            CampSecondary(
                                                tr(R.string.camp_out_of_stock),
                                                enabled = false,
                                                modifier = Modifier.testTag("product-add-${product.id}"),
                                                onClick = {},
                                            )
                                        else FilledTonalIconButton(
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
                                                Modifier.size(CampDesign.IconTarget)
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

@Composable
private fun TripSiteSummary(site: Campsite, trip: Trip, modifier: Modifier) {
            Column(
                modifier.padding(24.dp),
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
