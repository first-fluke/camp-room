@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package camp.room.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

object CampPalette {
    val Canvas = Color(0xFFF7F8F2)
    val Paper = Color(0xFFFFFEF9)
    val Forest = Color(0xFF254F40)
    val Deep = Color(0xFF193F33)
    val Ink = Color(0xFF293F32)
    val Muted = Color(0xFF839176)
    val Sage = Color(0xFFE7EDDC)
    val Line = Color(0xFFDFE5D4)
    val Warm = Color(0xFFC1A36C)
    val Danger = Color(0xFFAD614B)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme =
                    lightColorScheme(
                        primary = CampPalette.Forest,
                        onPrimary = CampPalette.Paper,
                        background = CampPalette.Canvas,
                        surface = CampPalette.Paper,
                        onSurface = CampPalette.Ink,
                        onBackground = CampPalette.Ink,
                        secondary = CampPalette.Muted,
                        outline = CampPalette.Line,
                        surfaceVariant = CampPalette.Sage,
                    )
            ) {
                CampRoomRoot()
            }
        }
    }
}

@Composable
fun CampRoomRoot(vm: CampViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onHostStart() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onHostStop() }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var pendingPermissionOperation by remember { mutableStateOf<String?>(null) }
    val performAssist: (String) -> Unit = { operation ->
        when (operation) {
            "location" -> vm.nearby()
            "arrival" -> vm.arrivalWatch()
            "stt" -> vm.listen()
            "tts" -> vm.speakBriefing()
        }
    }
    val assistPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            grants ->
            val operation = pendingPermissionOperation
            pendingPermissionOperation = null
            if (operation != null) {
                if (grants.values.any { it }) performAssist(operation)
                else
                    vm.permissionDenied(
                        when (operation) {
                            "location",
                            "arrival" -> "위치"
                            "stt" -> "음성 인식"
                            else -> "음성 안내"
                        }
                    )
            }
        }
    val requestAssist: (String) -> Unit = { operation ->
        val required =
            when (operation) {
                "location",
                "arrival" ->
                    if (BuildConfig.IS_DEMO)
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    else arrayOf("pleos.car.permission.FUSED_LOCATION")
                "stt" ->
                    if (BuildConfig.IS_DEMO) arrayOf(Manifest.permission.RECORD_AUDIO)
                    else arrayOf("pleos.car.permission.STT_SERVICE")
                "tts" ->
                    if (BuildConfig.IS_DEMO) emptyArray()
                    else arrayOf("pleos.car.permission.TTS_SERVICE")
                else -> emptyArray()
            }
        if (
            required.isEmpty() ||
                required.any {
                    ContextCompat.checkSelfPermission(context, it) ==
                        PackageManager.PERMISSION_GRANTED
                }
        )
            performAssist(operation)
        else {
            pendingPermissionOperation = operation
            assistPermission.launch(required)
        }
    }
    val nearMe: () -> Unit = { requestAssist("location") }
    val listen: () -> Unit = { requestAssist("stt") }
    val arrival: () -> Unit = { requestAssist("arrival") }
    val speak: () -> Unit = { requestAssist("tts") }
    LaunchedEffect(state.notice) {
        if (state.notice.isNotBlank()) {
            snackbar.showSnackbar(state.notice)
            vm.clearNotice()
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(CampPalette.Canvas).semantics {
            testTagsAsResourceId = true
        }
    ) {
        val wide = maxWidth > 850.dp
        Scaffold(
            containerColor = CampPalette.Canvas,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!wide)
                    NavigationBar(containerColor = CampPalette.Paper, tonalElevation = 0.dp) {
                        CampNav.entries.forEach { nav ->
                            NavigationBarItem(
                                selected = state.tab == nav.index,
                                onClick = { vm.tab(nav.index) },
                                icon = { Icon(nav.icon, nav.label) },
                                label = { Text(nav.label) },
                                modifier = Modifier.testTag(nav.tag),
                            )
                        }
                    }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide) CampSidebar(state, vm)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    CampTopbar(state, vm, wide)
                    if (state.loading)
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                CircularProgressIndicator(color = CampPalette.Forest)
                                Text("다음 쉼을 준비하고 있어요", fontSize = 22.sp)
                                Text("캠핑장과 여행 정보를 불러옵니다.", color = CampPalette.Muted)
                            }
                        }
                    else if (state.data == null)
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Outlined.CloudOff,
                                    null,
                                    Modifier.size(42.dp),
                                    tint = CampPalette.Muted,
                                )
                                Spacer(Modifier.height(16.dp))
                                Text("여행 정보를 불러오지 못했어요", fontSize = 22.sp)
                                Text(
                                    state.error,
                                    color = CampPalette.Muted,
                                    modifier = Modifier.padding(18.dp),
                                )
                                CampPrimary("다시 연결하기", Icons.Outlined.Refresh, onClick = vm::reload)
                            }
                        }
                    else
                        Column(
                            Modifier.fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = if (wide) 34.dp else 20.dp, vertical = 27.dp),
                            verticalArrangement = Arrangement.spacedBy(23.dp),
                        ) {
                            if (state.error.isNotBlank()) CampError(state.error, vm::clearError)
                            when (state.tab) {
                                0 -> DiscoverScreen(state, vm, nearMe)
                                1 -> TripScreen(state, vm, arrival, speak)
                                else -> CabinScreen(state, vm)
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.Forest,
                                    null,
                                    Modifier.size(17.dp),
                                    tint = CampPalette.Muted,
                                )
                                Text(
                                    "  camp room",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                    color = CampPalette.Muted,
                                )
                                Text(
                                    "  자연과 나 사이, 작은 방.",
                                    fontSize = 10.sp,
                                    color = CampPalette.Muted,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    if (BuildConfig.IS_DEMO) "데모 데이터 · 실제 예약/결제 없음"
                                    else "Pleos 차량 연동 · 외부 데이터 출처 확인",
                                    fontSize = 9.sp,
                                    color = CampPalette.Muted,
                                )
                            }
                        }
                }
            }
        }
        if (state.selectedSite != null) CampsiteDetailDialog(state, vm)
        if (state.cartOpen && state.data != null) CartDialog(state, vm)
        if (state.voiceOpen) VoiceCommandDialog(state, vm, listen)
    }
}

enum class CampNav(
    val index: Int,
    val label: String,
    val subtitle: String,
    val icon: ImageVector,
    val tag: String,
) {
    DISCOVER(0, "찾기", "나에게 맞는 캠핑장", Icons.Outlined.Explore, "nav-discover"),
    TRIP(1, "내 캠핑", "여정부터 준비물까지", Icons.Outlined.Forest, "nav-trip"),
    CABIN(2, "차박", "우리만의 편안한 공간", Icons.Outlined.DirectionsCar, "nav-cabin"),
}

@Composable
private fun CampSidebar(state: CampUiState, vm: CampViewModel) {
    Column(
        Modifier.width(202.dp).fillMaxHeight().background(CampPalette.Deep).padding(21.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Forest, null, Modifier.size(29.dp), tint = Color(0xFFC7D4B7))
            Column(Modifier.padding(start = 9.dp)) {
                Text(
                    "camp room",
                    fontSize = 24.sp,
                    color = CampPalette.Paper,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-1).sp,
                )
                Text("자연과 나 사이, 작은 방", fontSize = 8.sp, color = Color(0xFFA6B8A2))
            }
        }
        Spacer(Modifier.height(27.dp))
        Text(
            "YOUR CAMPING COMPANION",
            fontSize = 7.sp,
            color = Color(0xFF9CB09A),
            letterSpacing = 1.sp,
        )
        CampNav.entries.forEach { nav ->
            val active = state.tab == nav.index
            Surface(
                onClick = { vm.tab(nav.index) },
                color = if (active) CampPalette.Sage else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().testTag(nav.tag),
            ) {
                Row(
                    Modifier.padding(horizontal = 13.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        nav.icon,
                        nav.label,
                        Modifier.size(23.dp),
                        tint = if (active) CampPalette.Forest else Color(0xFFA6BBA4),
                    )
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(
                            nav.label,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (active) CampPalette.Deep else Color(0xFFC4D0BF),
                        )
                        Text(
                            nav.subtitle,
                            fontSize = 8.sp,
                            color = if (active) CampPalette.Muted else Color(0xFF9CB298),
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Icon(Icons.Outlined.Landscape, null, Modifier.size(43.dp), tint = Color(0xFF9EB494))
        Text(
            "조금 더 가까이,\n자연스럽게 쉬어가기.",
            fontSize = 12.sp,
            lineHeight = 21.sp,
            color = Color(0xFFB3C4A8),
        )
        Spacer(Modifier.height(17.dp))
        HorizontalDivider(color = Color(0xFF426049))
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(Color(0xFFB0C68C), CircleShape))
            Column(Modifier.padding(start = 7.dp).weight(1f)) {
                Text(
                    if (BuildConfig.IS_DEMO) "데모 차량 연결됨" else "Pleos 차량 상태",
                    fontSize = 9.sp,
                    color = Color(0xFFD2DDC8),
                )
                Text(
                    if (BuildConfig.IS_DEMO) "PLEOS · 시뮬레이터" else "PLEOS · 로컬 SDK",
                    fontSize = 7.sp,
                    color = Color(0xFF92AC8E),
                )
            }
            Text(
                "${number(state.data?.vehicle?.soc)}%",
                fontSize = 13.sp,
                color = Color(0xFFD2DDC8),
            )
        }
        Text(
            if (BuildConfig.IS_DEMO) "NATIVE ANDROID · DEMO" else "NATIVE ANDROID · PLEOS",
            fontSize = 7.sp,
            color = Color(0xFF8FA88A),
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun CampTopbar(state: CampUiState, vm: CampViewModel, wide: Boolean) {
    Row(
        Modifier.fillMaxWidth()
            .height(65.dp)
            .background(CampPalette.Paper)
            .padding(horizontal = if (wide) 34.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (wide) "나의 작은 아웃도어  /  ${CampNav.entries[state.tab].label}" else "camp room",
            fontSize = if (wide) 11.sp else 22.sp,
            color = if (wide) CampPalette.Muted else CampPalette.Forest,
            fontWeight = if (wide) FontWeight.Normal else FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        CampPill(if (BuildConfig.IS_DEMO) "체험 모드" else "Pleos 모드")
        Spacer(Modifier.width(22.dp))
        IconButton(onClick = { vm.voice(true) }, modifier = Modifier.testTag("voice-open")) {
            Icon(Icons.Outlined.Mic, "음성 명령", Modifier.size(20.dp))
        }
        TextButton(onClick = { vm.cart(true) }, modifier = Modifier.testTag("cart-open")) {
            Icon(Icons.Outlined.ShoppingBag, "준비물", Modifier.size(20.dp))
            Spacer(Modifier.width(7.dp))
            Text("준비물", fontSize = 12.sp)
            if ((state.data?.cart?.count ?: 0) > 0)
                Text("  ${state.data?.cart?.count}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(30.dp).background(CampPalette.Sage, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("C", fontSize = 13.sp, color = CampPalette.Forest)
        }
    }
    HorizontalDivider(color = CampPalette.Line)
}

@Composable
fun CampPrimary(
    text: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 19.dp, vertical = 12.dp),
        modifier = modifier.heightIn(min = 44.dp),
        colors = ButtonDefaults.buttonColors(containerColor = CampPalette.Forest),
    ) {
        if (busy)
            CircularProgressIndicator(
                Modifier.size(17.dp),
                strokeWidth = 2.dp,
                color = CampPalette.Paper,
            )
        else if (icon != null) Icon(icon, null, Modifier.size(18.dp))
        if (busy || icon != null) Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun CampSecondary(
    text: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CampPalette.Line),
        modifier = modifier.heightIn(min = 40.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, fontSize = 11.sp)
    }
}

@Composable
fun CampPill(text: String, color: Color = CampPalette.Muted) {
    Surface(color = CampPalette.Sage, shape = RoundedCornerShape(5.dp)) {
        Text(
            text,
            fontSize = 9.sp,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun CampError(text: String, onClose: () -> Unit) {
    Surface(
        color = Color(0xFFFAEDE4),
        shape = RoundedCornerShape(9.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.ErrorOutline,
                null,
                tint = CampPalette.Danger,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text,
                color = CampPalette.Danger,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f).padding(horizontal = 9.dp),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(25.dp)) {
                Icon(Icons.Outlined.Close, "오류 닫기", Modifier.size(17.dp), tint = CampPalette.Danger)
            }
        }
    }
}

@Composable
fun SectionTitle(
    title: String,
    subtitle: String = "",
    eyebrow: String = "",
    action: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (eyebrow.isNotBlank())
                Text(
                    eyebrow,
                    fontSize = 8.sp,
                    letterSpacing = 1.5.sp,
                    color = CampPalette.Muted,
                    modifier = Modifier.padding(bottom = 7.dp),
                )
            Text(
                title,
                fontSize = if (eyebrow.isNotBlank()) 28.sp else 19.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-.7).sp,
            )
            if (subtitle.isNotBlank())
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = CampPalette.Muted,
                    modifier = Modifier.padding(top = 7.dp),
                )
        }
        action?.invoke()
    }
}

@Composable
fun CampSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        color = CampPalette.Paper,
        shape = RoundedCornerShape(11.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CampPalette.Line),
    ) {
        Column(
            Modifier.padding(21.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
            content = content,
        )
    }
}

fun number(value: Double?): String =
    when {
        value == null -> "—"
        value % 1.0 == 0.0 -> value.toInt().toString()
        else -> String.format(java.util.Locale.US, "%.1f", value)
    }

fun won(value: Int?): String =
    if (value == null) "요금 확인 필요" else String.format(java.util.Locale.KOREA, "%,d원", value)

fun displayDate(value: String): String =
    runCatching {
            val date = java.time.LocalDate.parse(value)
            "${date.monthValue}.${date.dayOfMonth} (${listOf("월","화","수","목","금","토","일")[date.dayOfWeek.value-1]})"
        }
        .getOrDefault(value)

fun facilityName(code: String): String =
    mapOf(
        "shower" to "샤워실",
        "electricity" to "캠핑 전기",
        "pets" to "반려견 동반",
        "toilet" to "화장실",
        "water" to "급수대",
    )[code] ?: code

fun assistStatusLabel(value: String): String =
    when (value) {
        "available",
        "ready" -> "준비됨"
        "registered" -> "알림 등록됨"
        "triggered",
        "arrived" -> "도착 확인"
        "started",
        "speaking" -> "안내 중"
        "recognized" -> "인식 완료"
        "listening" -> "듣는 중"
        "unavailable" -> "사용 불가"
        "idle" -> "대기 중"
        "done",
        "completed" -> "완료"
        "stopped" -> "중지됨"
        else -> "상태 확인 중"
    }
