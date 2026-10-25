@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package camp.room.app

import android.Manifest
import android.content.Context
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(CampLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideDemoSystemBars()
        setContent { CampTheme { CampRoomRoot() } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideDemoSystemBars()
    }

    private fun hideDemoSystemBars() {
        if (!BuildConfig.IS_DEMO) return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
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
    var languageSettingsOpen by remember { mutableStateOf(false) }
    val languageTag = CampLocale.language(context)
    LaunchedEffect(languageTag) { vm.setLanguage(languageTag) }
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
                            "arrival" -> CampText.get(camp.room.app.R.string.camp_location)
                            "stt" -> CampText.get(camp.room.app.R.string.camp_speech_recognition)
                            else -> CampText.get(camp.room.app.R.string.camp_voice_guidance)
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
                                label = {
                                    Text(nav.label, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                },
                                modifier = Modifier.testTag(nav.tag),
                            )
                        }
                    }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide) CampSidebar(state, vm)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    CampTopbar(state, vm, wide) { languageSettingsOpen = true }
                    if (state.busy)
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = CampPalette.Forest,
                            trackColor = CampPalette.Sage,
                        )
                    if (state.loading)
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                CircularProgressIndicator(color = CampPalette.Forest)
                                Text(
                                    tr(camp.room.app.R.string.camp_getting_your_next_escape_ready),
                                    fontSize = 22.sp,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    tr(camp.room.app.R.string.camp_loading_campsites_and_your_trip),
                                    color = CampPalette.Muted,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
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
                                Text(
                                    tr(camp.room.app.R.string.camp_we_couldn_t_load_your_trip),
                                    fontSize = 22.sp,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    state.error,
                                    color = CampPalette.Muted,
                                    modifier = Modifier.padding(18.dp),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                CampPrimary(
                                    tr(camp.room.app.R.string.camp_connect_again),
                                    Icons.Outlined.Refresh,
                                    onClick = vm::reload,
                                )
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
                                    fontSize = 14.sp,
                                    color = CampPalette.Muted,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    tr(
                                        camp.room.app.R.string
                                            .camp_a_little_room_between_you_and_nature
                                    ),
                                    fontSize = 12.sp,
                                    color = CampPalette.Muted,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    if (BuildConfig.IS_DEMO)
                                        tr(
                                            camp.room.app.R.string
                                                .camp_demo_data_no_real_reservations_or_payments
                                        )
                                    else
                                        tr(
                                            camp.room.app.R.string
                                                .camp_pleos_vehicle_integration_check_external_data_sources
                                        ),
                                    fontSize = 12.sp,
                                    color = CampPalette.Muted,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                }
            }
        }
        if (state.selectedSite != null) CampsiteDetailDialog(state, vm)
        if (state.cartOpen && state.data != null) CartDialog(state, vm)
        if (state.voiceOpen) VoiceCommandDialog(state, vm, listen)
        if (languageSettingsOpen) LanguageSettingsDialog { languageSettingsOpen = false }
    }
}

enum class CampNav(
    val index: Int,
    private val labelResource: Int,
    private val subtitleResource: Int,
    val icon: ImageVector,
    val tag: String,
) {
    DISCOVER(
        0,
        camp.room.app.R.string.camp_discover,
        camp.room.app.R.string.camp_find_your_kind_of_campsite,
        Icons.Outlined.Explore,
        "nav-discover",
    ),
    TRIP(
        1,
        camp.room.app.R.string.camp_my_trip,
        camp.room.app.R.string.camp_from_the_journey_to_your_essentials,
        Icons.Outlined.Forest,
        "nav-trip",
    ),
    CABIN(
        2,
        camp.room.app.R.string.camp_cabin,
        camp.room.app.R.string.camp_a_comfortable_space_of_our_own,
        Icons.Outlined.DirectionsCar,
        "nav-cabin",
    );

    val label: String
        get() = CampText.get(labelResource)

    val subtitle: String
        get() = CampText.get(subtitleResource)
}

@Composable
private fun CampSidebar(state: CampUiState, vm: CampViewModel) {
    Column(
        Modifier.width(232.dp).fillMaxHeight().background(CampPalette.Deep).padding(21.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Forest, null, Modifier.size(29.dp), tint = Color(0xFFC7D4B7))
            Column(Modifier.padding(start = 9.dp)) {
                Text(
                    "camp room",
                    fontSize = 24.sp,
                    color = CampPalette.OnSidebar,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-1).sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tr(camp.room.app.R.string.camp_a_little_room_between_you_and_nature_017),
                    fontSize = 12.sp,
                    color = Color(0xFFA6B8A2),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(27.dp))
        Text(
            "YOUR CAMPING COMPANION",
            fontSize = 12.sp,
            color = Color(0xFF9CB09A),
            letterSpacing = 1.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
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
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (active) CampPalette.Ink else CampPalette.OnSidebar,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            nav.subtitle,
                            fontSize = 12.sp,
                            color = if (active) CampPalette.Muted else Color(0xFF9CB298),
                            modifier = Modifier.padding(top = 3.dp),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Icon(Icons.Outlined.Landscape, null, Modifier.size(43.dp), tint = Color(0xFF9EB494))
        Text(
            tr(camp.room.app.R.string.camp_a_little_closer_na_little_more_naturally),
            fontSize = 14.sp,
            lineHeight = 21.sp,
            color = Color(0xFFB3C4A8),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(17.dp))
        HorizontalDivider(color = Color(0xFF426049))
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(Color(0xFFB0C68C), CircleShape))
            Column(Modifier.padding(start = 7.dp).weight(1f)) {
                Text(
                    if (BuildConfig.IS_DEMO) tr(camp.room.app.R.string.camp_demo_vehicle_connected)
                    else tr(camp.room.app.R.string.camp_pleos_vehicle_status),
                    fontSize = 12.sp,
                    color = Color(0xFFD2DDC8),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (BuildConfig.IS_DEMO) tr(camp.room.app.R.string.camp_pleos_simulator)
                    else tr(camp.room.app.R.string.camp_pleos_local_sdk),
                    fontSize = 12.sp,
                    color = Color(0xFF92AC8E),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "${number(state.data?.vehicle?.soc)}%",
                fontSize = 16.sp,
                color = Color(0xFFD2DDC8),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            if (BuildConfig.IS_DEMO) "NATIVE ANDROID · DEMO" else "NATIVE ANDROID · PLEOS",
            fontSize = 12.sp,
            color = Color(0xFF8FA88A),
            letterSpacing = 1.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CampTopbar(
    state: CampUiState,
    vm: CampViewModel,
    wide: Boolean,
    onLanguage: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .height(65.dp)
            .background(CampPalette.Paper)
            .padding(horizontal = if (wide) 34.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (wide)
                tr(camp.room.app.R.string.camp_my_little_outdoors, CampNav.entries[state.tab].label)
            else "camp room",
            fontSize = if (wide) 14.sp else 22.sp,
            color = if (wide) CampPalette.Muted else CampPalette.Forest,
            fontWeight = if (wide) FontWeight.Normal else FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        CampPill(
            if (BuildConfig.IS_DEMO) tr(camp.room.app.R.string.camp_demo_mode)
            else tr(camp.room.app.R.string.camp_pleos_mode)
        )
        Spacer(Modifier.width(22.dp))
        IconButton(onClick = { vm.voice(true) }, modifier = Modifier.testTag("voice-open")) {
            Icon(
                Icons.Outlined.Mic,
                tr(camp.room.app.R.string.camp_voice_command),
                Modifier.size(20.dp),
            )
        }
        TextButton(onClick = { vm.cart(true) }, modifier = Modifier.testTag("cart-open")) {
            Icon(
                Icons.Outlined.ShoppingBag,
                tr(camp.room.app.R.string.camp_essentials),
                Modifier.size(20.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                tr(camp.room.app.R.string.camp_essentials),
                fontSize = 14.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if ((state.data?.cart?.count ?: 0) > 0)
                Text(
                    "  ${state.data?.cart?.count}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
        }
        Spacer(Modifier.width(12.dp))
        IconButton(onClick = onLanguage, modifier = Modifier.testTag("language-settings")) {
            Icon(
                Icons.Outlined.Language,
                tr(R.string.language_settings),
                Modifier.size(22.dp),
                tint = CampPalette.Forest,
            )
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
    val haptic = LocalHapticFeedback.current
    Button(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        enabled = enabled && !busy,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 19.dp, vertical = 12.dp),
        modifier = modifier.heightIn(min = CampDesign.PrimaryHeight),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = CampPalette.Forest,
                contentColor = CampPalette.OnForest,
            ),
    ) {
        if (busy)
            CircularProgressIndicator(
                Modifier.size(17.dp),
                strokeWidth = 2.dp,
                color = CampPalette.OnForest,
            )
        else if (icon != null) Icon(icon, null, Modifier.size(24.dp))
        if (busy || icon != null) Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2)
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
    val haptic = LocalHapticFeedback.current
    OutlinedButton(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CampPalette.Outline),
        modifier = modifier.heightIn(min = CampDesign.SecondaryHeight),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(22.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, fontSize = 18.sp, maxLines = 2)
    }
}

@Composable
fun CampPill(text: String, color: Color = CampPalette.Muted) {
    Surface(color = CampPalette.Sage, shape = RoundedCornerShape(5.dp)) {
        Text(
            text,
            fontSize = 12.sp,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun CampError(text: String, onClose: () -> Unit) {
    Surface(
        color = CampPalette.ErrorSurface,
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
                fontSize = 14.sp,
                modifier = Modifier.weight(1f).padding(horizontal = 9.dp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onClose, modifier = Modifier.size(CampDesign.IconTarget)) {
                Icon(
                    Icons.Outlined.Close,
                    tr(camp.room.app.R.string.camp_dismiss_error),
                    Modifier.size(17.dp),
                    tint = CampPalette.Danger,
                )
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
                    fontSize = 12.sp,
                    letterSpacing = 1.5.sp,
                    color = CampPalette.Muted,
                    modifier = Modifier.padding(bottom = 7.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            Text(
                title,
                fontSize = if (eyebrow.isNotBlank()) 28.sp else 19.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-.7).sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank())
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = CampPalette.Muted,
                    modifier = Modifier.padding(top = 7.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
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

fun won(value: Int?): String = CampText.currency(value)

fun displayDate(value: String): String = CampText.date(value)

fun facilityName(code: String): String =
    mapOf(
        "shower" to camp.room.app.CampText.get(camp.room.app.R.string.camp_showers),
        "electricity" to camp.room.app.CampText.get(camp.room.app.R.string.camp_camping_power),
        "pets" to camp.room.app.CampText.get(camp.room.app.R.string.camp_pets_welcome),
        "toilet" to camp.room.app.CampText.get(camp.room.app.R.string.camp_restrooms),
        "water" to camp.room.app.CampText.get(camp.room.app.R.string.camp_water_point),
    )[code] ?: code

fun assistStatusLabel(value: String): String =
    when (value) {
        "available",
        "ready" -> camp.room.app.CampText.get(camp.room.app.R.string.camp_ready)
        "registered" -> camp.room.app.CampText.get(camp.room.app.R.string.camp_alert_registered)
        "triggered",
        "arrived" -> camp.room.app.CampText.get(camp.room.app.R.string.camp_arrival_observed)
        "started",
        "speaking" -> camp.room.app.CampText.get(camp.room.app.R.string.camp_speaking)
        "recognized" -> camp.room.app.CampText.get(camp.room.app.R.string.camp_recognized)
        "listening" -> camp.room.app.CampText.get(camp.room.app.R.string.camp_listening)
        "unavailable" -> camp.room.app.CampText.get(camp.room.app.R.string.camp_unavailable)
        "idle" -> camp.room.app.CampText.get(camp.room.app.R.string.camp_waiting)
        "done",
        "completed" -> camp.room.app.CampText.get(camp.room.app.R.string.camp_complete)
        "stopped" -> camp.room.app.CampText.get(camp.room.app.R.string.camp_stopped)
        else -> camp.room.app.CampText.get(camp.room.app.R.string.camp_checking_status)
    }
