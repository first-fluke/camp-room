@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package camp.room.app

import android.app.DatePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

@Composable
fun DiscoverScreen(s: CampUiState, vm: CampViewModel, onNearby: () -> Unit) {
    SectionTitle(
        "이번 주말, 어디서 쉬어갈까요?",
        "마음이 향하는 곳을 찾고, 나만의 캠핑을 시작하세요.",
        "FIND YOUR NEXT ESCAPE",
        action = {
            CampSecondary(
                "내 주변",
                Icons.Outlined.MyLocation,
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
                    DateSelector("체크인", s.checkIn, Modifier.weight(1f)) { vm.dates(it, s.checkOut) }
                    DateSelector("체크아웃", s.checkOut, Modifier.weight(1f)) {
                        vm.dates(s.checkIn, it)
                    }
                    GuestSelector(s.guests, vm::guests, Modifier.weight(.8f))
                    CampPrimary(
                        "캠핑장 찾기",
                        Icons.Outlined.Explore,
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
                        DateSelector("체크인", s.checkIn, Modifier.weight(1f)) {
                            vm.dates(it, s.checkOut)
                        }
                        DateSelector("체크아웃", s.checkOut, Modifier.weight(1f)) {
                            vm.dates(s.checkIn, it)
                        }
                    }
                    CampPrimary(
                        "캠핑장 찾기",
                        Icons.Outlined.Explore,
                        busy = s.busy,
                        modifier = Modifier.fillMaxWidth().testTag("search-campsites"),
                        onClick = vm::search,
                    )
                }
        }
        HorizontalDivider(color = CampPalette.Line)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Tune, null, Modifier.size(16.dp), tint = CampPalette.Muted)
            Text("내 취향에 맞게", fontSize = 10.sp, color = CampPalette.Muted)
            CampChip("차박 가능", s.carCamping, Icons.Outlined.DirectionsCar, vm::carCamping)
            listOf("shower", "electricity", "pets").forEach { code ->
                CampChip(
                    facilityName(code),
                    code in s.facilities,
                    when (code) {
                        "shower" -> Icons.Outlined.WaterDrop
                        "electricity" -> Icons.Outlined.Bolt
                        else -> Icons.Outlined.Pets
                    },
                ) {
                    vm.facility(code)
                }
            }
        }
    }
    SectionTitle(
        "쉼이 있는 캠핑장  ${s.sites.size}",
        "차박의 설렘을 더해줄 장소들을 만나보세요.",
        action = { CampPill(if (s.data?.mode == "live") "외부 데이터 연동" else "데모 캠핑장 컬렉션") },
    )
    if (s.locationSummary.isNotBlank())
        Text(s.locationSummary, fontSize = 10.sp, color = CampPalette.Muted)
    if (s.sites.isEmpty())
        CampSurface(Modifier.fillMaxWidth()) {
            Text("조건에 맞는 캠핑장이 없어요", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("지역이나 시설 조건을 조금 넓혀보세요.", color = CampPalette.Muted, fontSize = 12.sp)
            CampSecondary("조건 초기화", Icons.Outlined.Refresh, onClick = vm::resetFilters)
        }
    else
        BoxWithConstraints {
            val columns = if (maxWidth > 1000.dp) 3 else if (maxWidth > 560.dp) 2 else 1
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
            Icon(Icons.Outlined.Explore, null, tint = CampPalette.Muted)
            Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
                Text(
                    "좋은 장소를 찾았다면, 여정도 한 번에 이어가세요.",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "날씨, 이동 계획, 차박 공간과 준비물이 내 캠핑에 모입니다.",
                    fontSize = 10.sp,
                    color = CampPalette.Muted,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            TextButton(onClick = { vm.tab(1) }) {
                Text("내 캠핑 둘러보기", fontSize = 10.sp)
                Icon(Icons.Outlined.ArrowForward, null, Modifier.size(16.dp))
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
        Column(Modifier.fillMaxWidth().clickable { expanded = true }.padding(vertical = 4.dp)) {
            Text("어디로 떠날까요", fontSize = 9.sp, color = CampPalette.Muted)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 5.dp),
            ) {
                Icon(
                    Icons.Outlined.LocationOn,
                    null,
                    Modifier.size(18.dp),
                    tint = CampPalette.Muted,
                )
                Text(
                    if (region == "전체") "전국 모든 지역" else region,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 7.dp),
                )
                Icon(
                    Icons.Outlined.ExpandMore,
                    null,
                    Modifier.size(16.dp),
                    tint = CampPalette.Muted,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("전체", "강원", "경기", "충북", "경북", "제주").forEach { label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onChange(label)
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
        Column(Modifier.clickable { expanded = true }.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("함께 가는 인원", fontSize = 9.sp, color = CampPalette.Muted)
            Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.PeopleOutline,
                    null,
                    Modifier.size(18.dp),
                    tint = CampPalette.Muted,
                )
                Text(
                    "${guests}명",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 7.dp),
                )
                Icon(
                    Icons.Outlined.ExpandMore,
                    null,
                    Modifier.size(16.dp),
                    tint = CampPalette.Muted,
                )
            }
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            (1..8).forEach { n ->
                DropdownMenuItem(
                    text = { Text("${n}명") },
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
            .testTag(if (label == "체크인") "date-check-in" else "date-check-out")
    ) {
        Text(label, fontSize = 9.sp, color = CampPalette.Muted)
        Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp), tint = CampPalette.Muted)
            Text(
                displayDate(date),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 7.dp),
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
        label = { Text(text, fontSize = 10.sp) },
        leadingIcon = { Icon(icon, null, Modifier.size(14.dp)) },
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
                selectedBorderColor = Color(0xFFA5BC8D),
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
                    fontSize = 7.sp,
                    letterSpacing = 1.4.sp,
                    color = Color.White.copy(alpha = .8f),
                    modifier = Modifier.align(Alignment.BottomStart).padding(15.dp),
                )
            }
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.LocationOn,
                        null,
                        Modifier.size(13.dp),
                        tint = CampPalette.Muted,
                    )
                    Text(
                        site.region + (distance?.let { " · 확인된 위치 기준 ${number(it)}km" } ?: ""),
                        fontSize = 9.sp,
                        color = CampPalette.Muted,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (site.sourceMode == "demo") "데모" else "연동 데이터",
                        fontSize = 8.sp,
                        color = CampPalette.Muted,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onClick),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        site.name,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Outlined.ArrowOutward,
                        null,
                        Modifier.size(17.dp),
                        tint = CampPalette.Muted,
                    )
                }
                Text(site.description, fontSize = 10.sp, color = CampPalette.Muted, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    site.facilities.take(3).forEach { CampPill(facilityName(it)) }
                }
                HorizontalDivider(color = CampPalette.Line)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(won(site.price), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    if (site.price != null)
                        Text(" / 1박", fontSize = 9.sp, color = CampPalette.Muted)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) {
                        Text("자세히 보기", fontSize = 10.sp)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Outlined.ArrowForward, null, Modifier.size(14.dp))
                    }
                }
                Text(
                    "${site.availability.provider} · 조회 시각 ${site.availability.checkedAt.take(16).replace('T',' ')}",
                    fontSize = 7.sp,
                    color = CampPalette.Muted,
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
        "떠날 준비, 차근차근.",
        "선택한 장소부터 마지막 준비물까지, 하나의 여정으로 이어집니다.",
        "YOUR UPCOMING ESCAPE",
        action = { CampSecondary("장소 · 일정 변경", Icons.Outlined.Edit, onClick = { vm.tab(0) }) },
    )
    if (trip == null || site == null) {
        CampSurface(Modifier.fillMaxWidth()) {
            Text("우리의 다음 캠핑을 정해볼까요?", fontSize = 22.sp)
            CampPrimary("캠핑장 찾으러 가기", Icons.Outlined.Explore, onClick = { vm.tab(0) })
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
                    fontSize = 9.sp,
                    letterSpacing = 1.5.sp,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(22.dp),
                )
            }
            Column(
                Modifier.weight(1f).padding(26.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("나의 캠핑 일정", fontSize = 10.sp, color = CampPalette.Muted)
                Text(site.name, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                Text(site.address, fontSize = 10.sp, color = CampPalette.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    IconLabel(
                        Icons.Outlined.CalendarMonth,
                        "${displayDate(trip.checkIn)} — ${displayDate(trip.checkOut)}",
                    )
                    IconLabel(Icons.Outlined.PeopleOutline, "${trip.guests}명")
                }
                HorizontalDivider(color = CampPalette.Line)
                Text(
                    "체크인 ${site.checkInTime}    ·    체크아웃 ${site.checkOutTime}    ·    여행 저장됨 ✓",
                    fontSize = 10.sp,
                    color = CampPalette.Muted,
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
                Text("여정에 조금 더 가까이", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "위치와 도착 알림, 음성 안내를 함께 활용하세요.",
                    fontSize = 10.sp,
                    color = CampPalette.Muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            CampSecondary(
                "도착 알림",
                Icons.Outlined.NotificationsActive,
                enabled = !s.busy,
                modifier = Modifier.testTag("arrival-watch"),
                onClick = onArrival,
            )
            CampSecondary(
                "여행 안내 듣기",
                Icons.Outlined.VolumeUp,
                enabled = !s.busy,
                modifier = Modifier.testTag("speak-briefing"),
                onClick = onSpeak,
            )
        }
        if (s.arrivalMessage.isNotBlank())
            Text(s.arrivalMessage, fontSize = 10.sp, color = CampPalette.Muted)
        if (s.ttsStatus.isNotBlank())
            Text(
                "음성 안내: ${assistStatusLabel(s.ttsStatus)}",
                fontSize = 9.sp,
                color = CampPalette.Muted,
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
                Text("함께 지키는 캠핑 약속", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                site.rules.forEach {
                    Text(
                        "✓  $it",
                        fontSize = 10.sp,
                        color = CampPalette.Muted,
                        modifier = Modifier.padding(top = 7.dp),
                    )
                }
            }
            CampSecondary("우리 차박 공간 꾸미기", Icons.Outlined.ArrowForward, onClick = { vm.tab(2) })
        }
    }
    SectionTitle(
        "이번 캠핑에 챙기면 좋은 것들",
        "캠핑장 시설과 날씨를 생각한 작은 준비.",
        "READY FOR THE OUTDOORS",
        action = {
            TextButton(onClick = { vm.cart(true) }) {
                Text("장바구니 보기", fontSize = 11.sp)
                Icon(Icons.Outlined.ArrowForward, null, Modifier.size(17.dp))
            }
        },
    )
    ProductGrid(data.products, s.busy, vm::addProduct)
    if (data.orders.isNotEmpty())
        CampSurface(Modifier.fillMaxWidth()) {
            Text("준비물 주문 내역", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            data.orders.take(3).forEach { order ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CampPill("데모 주문 완료")
                    Text(
                        order.id,
                        fontSize = 9.sp,
                        modifier = Modifier.weight(1f).padding(horizontal = 13.dp),
                    )
                    Text("${order.eta} 도착 예정", fontSize = 10.sp, color = CampPalette.Muted)
                    Text(
                        won(order.total),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 20.dp),
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
                Icons.Outlined.WbSunny,
                null,
                tint = CampPalette.Muted,
                modifier = Modifier.size(21.dp),
            )
            Text("  머무는 동안의 날씨", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            CampPill(if (weather.demo) "데모 예보" else "기상 예보")
        }
        if (weather.status == "unavailable")
            Text(
                weather.message.ifBlank { "이 기간의 예보를 확인할 수 없어요." },
                fontSize = 12.sp,
                color = CampPalette.Muted,
                modifier = Modifier.heightIn(min = 125.dp),
            )
        else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WbSunny, null, Modifier.size(65.dp), tint = CampPalette.Warm)
                Column(Modifier.padding(start = 22.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "${number(weather.low)}°",
                            fontSize = 43.sp,
                            fontWeight = FontWeight.Light,
                        )
                        Text(
                            "밤 최저",
                            fontSize = 10.sp,
                            color = CampPalette.Muted,
                            modifier = Modifier.padding(start = 9.dp, bottom = 10.dp),
                        )
                    }
                    Text(weather.summary, fontSize = 10.sp, color = CampPalette.Muted)
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
                            fontSize = 9.sp,
                            color = CampPalette.Muted,
                        )
                        Icon(
                            if (index > 2) Icons.Outlined.Bedtime else Icons.Outlined.WbSunny,
                            null,
                            Modifier.size(23.dp),
                            tint = CampPalette.Warm,
                        )
                        Text("${number(f.temperature)}°", fontSize = 13.sp)
                    }
                }
            }
            Text(
                "강수 확률 ${number(weather.rain)}% · 따뜻한 침낭을 챙겨주세요",
                fontSize = 9.sp,
                color = CampPalette.Muted,
            )
        }
        Text(
            "${weather.source} · ${weather.updatedAt.take(16).replace('T',' ')}",
            fontSize = 8.sp,
            color = CampPalette.Muted,
        )
    }
}

@Composable
private fun RouteCard(s: CampUiState, vm: CampViewModel, modifier: Modifier) {
    val route = s.route
    CampSurface(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Navigation,
                null,
                tint = CampPalette.Muted,
                modifier = Modifier.size(21.dp),
            )
            Text("  내 차로 떠나는 여정", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            CampPill(if (BuildConfig.IS_DEMO) "데모 경로" else "Pleos 경로")
        }
        Box(
            Modifier.fillMaxWidth()
                .height(106.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(CampPalette.Sage)
        ) {
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
                drawCircle(
                    CampPalette.Paper,
                    19.dp.toPx(),
                    Offset(size.width * .13f, size.height * .8f),
                )
                drawCircle(
                    CampPalette.Forest,
                    19.dp.toPx(),
                    Offset(size.width * .88f, size.height * .2f),
                )
            }
            Icon(
                Icons.Outlined.DirectionsCar,
                null,
                tint = CampPalette.Muted,
                modifier =
                    Modifier.align(Alignment.BottomStart)
                        .padding(start = 39.dp, bottom = 5.dp)
                        .size(22.dp),
            )
            Icon(
                Icons.Outlined.Forest,
                null,
                tint = CampPalette.Paper,
                modifier =
                    Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 40.dp).size(22.dp),
            )
            Text(
                if (route?.chargingStop == true) "충전 경유 포함" else "쉼 없이, 여유롭게",
                fontSize = 9.sp,
                color = CampPalette.Muted,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("이동 거리", "${number(route?.distance)}", "km")
            Metric("예상 소요", route?.minutes?.let { "${it/60}시간 ${it%60}분" } ?: "—", "")
            Metric("도착 예상 잔량", number(route?.arrivalSoc), "%")
        }
        CampPrimary(
            if (s.navigationLaunched || s.data?.trip?.navigationStarted == true)
                if (BuildConfig.IS_DEMO) "데모 길안내 실행 중" else "길안내 요청 전달됨"
            else "길안내 시작",
            Icons.Outlined.Navigation,
            busy = s.busy,
            modifier = Modifier.fillMaxWidth().testTag("start-navigation"),
            onClick = vm::navigation,
        )
        Text(
            "현재 SOC ${number(s.data?.vehicle?.soc)}% · 예상 소비 ${number(route?.consumption)} kWh\n실제 경로는 달라질 수 있어요",
            fontSize = 8.sp,
            color = CampPalette.Muted,
        )
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String) {
    Column {
        Text(label, fontSize = 9.sp, color = CampPalette.Muted)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.8).sp)
            Text(
                unit,
                fontSize = 10.sp,
                color = CampPalette.Muted,
                modifier = Modifier.padding(start = 3.dp, bottom = 5.dp),
            )
        }
    }
}

@Composable
private fun IconLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(17.dp), tint = CampPalette.Muted)
        Text(text, fontSize = 12.sp, modifier = Modifier.padding(start = 7.dp))
    }
}

@Composable
private fun ProductGrid(products: List<Product>, busy: Boolean, onAdd: (String) -> Unit) {
    BoxWithConstraints {
        val columns = if (maxWidth > 980.dp) 3 else if (maxWidth > 520.dp) 2 else 1
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
                                        fontSize = 9.sp,
                                        color = CampPalette.Muted,
                                    )
                                    Text(
                                        product.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        product.reason,
                                        fontSize = 10.sp,
                                        color = CampPalette.Muted,
                                        minLines = 2,
                                    )
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            won(product.price),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Spacer(Modifier.weight(1f))
                                        FilledTonalIconButton(
                                            onClick = { onAdd(product.id) },
                                            enabled = !busy && product.stock > 0,
                                            modifier =
                                                Modifier.size(34.dp)
                                                    .testTag("product-add-${product.id}"),
                                            shape = RoundedCornerShape(6.dp),
                                        ) {
                                            Icon(
                                                Icons.Outlined.Add,
                                                "${product.name} 담기",
                                                Modifier.size(21.dp),
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
