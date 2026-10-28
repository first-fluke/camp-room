@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import camp.room.app.icons.CampIcons

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var uxMonitor: CampUxMonitor
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(CampLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideDemoSystemBars()
        setContent { CampTheme { CampRoomRoot(uxMonitor = uxMonitor) } }
    }

    override fun onStart() {
        super.onStart()
        uxMonitor.start()
    }

    override fun onStop() {
        uxMonitor.stop()
        super.onStop()
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
fun CampRoomRoot(uxMonitor: CampUxMonitor, vm: CampViewModel = viewModel()) {
    var navigationExpanded by rememberSaveable { mutableStateOf(false) }
    val ux by uxMonitor.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(ux.allowsInteraction) {
        vm.onUxAvailabilityChanged(ux.allowsInteraction && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        vm.onUxAvailabilityChanged(uxMonitor.state.value.allowsInteraction)
        vm.onHostStart()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        vm.onUxAvailabilityChanged(false)
        vm.onHostStop()
    }
    if (!ux.allowsInteraction) {
        CampUxRestrictedScreen(ux) { uxMonitor.stop(); uxMonitor.start() }
        return
    }
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
                                icon = {
                                    Icon(
                                        if (state.tab == nav.index) nav.selectedIcon else nav.icon,
                                        nav.label,
                                        Modifier.size(28.dp),
                                    )
                                },
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
                                Modifier.widthIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                CircularProgressIndicator(color = CampPalette.Forest)
                                Text(
                                    tr(camp.room.app.R.string.camp_getting_your_next_escape_ready),
                                    fontSize = 22.sp,
                                )
                                Text(
                                    tr(camp.room.app.R.string.camp_loading_campsites_and_your_trip),
                                    color = CampPalette.Muted,
                                )
                            }
                        }
                    else if (state.data == null)
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(Modifier.widthIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    CampIcons.CloudSlash,
                                    null,
                                    Modifier.size(42.dp),
                                    tint = CampPalette.Muted,
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    tr(camp.room.app.R.string.camp_we_couldn_t_load_your_trip),
                                    fontSize = 22.sp,
                                )
                                Text(
                                    state.error,
                                    color = CampPalette.Muted,
                                    modifier = Modifier.padding(18.dp),
                                )
                                CampPrimary(
                                    tr(camp.room.app.R.string.camp_connect_again),
                                    CampIcons.Refresh,
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
                        }
                }
                if (wide) {
                    CampSidebar(state, vm, navigationExpanded) {
                        navigationExpanded = !navigationExpanded
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
    val selectedIcon: ImageVector,
    val tag: String,
) {
    DISCOVER(
        0,
        camp.room.app.R.string.camp_discover,
        camp.room.app.R.string.camp_find_your_kind_of_campsite,
        CampIcons.Compass,
        CampIcons.Selected.Compass,
        "nav-discover",
    ),
    TRIP(
        1,
        camp.room.app.R.string.camp_my_trip,
        camp.room.app.R.string.camp_from_the_journey_to_your_essentials,
        CampIcons.Tent,
        CampIcons.Selected.Tent,
        "nav-trip",
    ),
    CABIN(
        2,
        camp.room.app.R.string.camp_cabin,
        camp.room.app.R.string.camp_a_comfortable_space_of_our_own,
        CampIcons.Vehicle,
        CampIcons.Selected.Vehicle,
        "nav-cabin",
    );

    val label: String
        get() = CampText.get(labelResource)

    val subtitle: String
        get() = CampText.get(subtitleResource)
}

@Composable
private fun CampSidebar(
    state: CampUiState,
    vm: CampViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val toggleLabel = tr(if (expanded) R.string.camp_collapse_menu else R.string.camp_expand_menu)
    val menuState = tr(if (expanded) R.string.camp_menu_expanded else R.string.camp_menu_collapsed)
    Column(
        Modifier.width(if (expanded) 264.dp else 80.dp).fillMaxHeight()
            .background(CampPalette.Deep).testTag("right-navigation")
            .verticalScroll(rememberScrollState()).padding(if (expanded) 16.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (expanded) {
                Icon(CampIcons.Artwork.TreeEvergreen, null, Modifier.size(24.dp),
                    tint = Color(0xFFC7D4B7))
                Text(tr(R.string.camp_brand_name), modifier = Modifier.weight(1f),
                    fontSize = 24.sp, color = CampPalette.OnSidebar,
                    fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onToggle,
                modifier = Modifier.size(48.dp).testTag("navigation-toggle")
                    .semantics {
                        contentDescription = toggleLabel
                        stateDescription = menuState
                    },
                colors = IconButtonDefaults.iconButtonColors(contentColor = CampPalette.OnSidebar)) {
                Text(if (expanded) "\u203a" else "\u2039", fontSize = 30.sp,
                    modifier = Modifier.clearAndSetSemantics {})
            }
        }
        Spacer(Modifier.height(12.dp))
        CampNav.entries.forEach { nav ->
            val active = state.tab == nav.index
            Surface(
                onClick = { vm.tab(nav.index) },
                color = if (active) CampPalette.Sage else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(nav.tag)
                    .semantics {
                        contentDescription = nav.label
                        selected = active
                        role = Role.Tab
                    },
            ) {
                Row(Modifier.padding(horizontal = if (expanded) 13.dp else 0.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (expanded) Arrangement.spacedBy(12.dp) else Arrangement.Center) {
                    Icon(if (active) nav.selectedIcon else nav.icon, null, Modifier.size(28.dp),
                        tint = if (active) CampPalette.Forest else Color(0xFFA6BBA4))
                    if (expanded) {
                        Text(nav.label, modifier = Modifier.weight(1f).clearAndSetSemantics {},
                            fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = if (active) CampPalette.Ink else CampPalette.OnSidebar)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = Color(0xFF426049))
        val vehicle = state.data?.vehicle
        val vehicleStatus = if (vehicle?.isConnected == true) tr(R.string.vehicle_connected)
            else tr(R.string.vehicle_disconnected)
        if (expanded) {
            Text(vehicleStatus, fontSize = 22.sp, color = Color(0xFFD2DDC8),
                modifier = Modifier.fillMaxWidth().testTag("sidebar-vehicle-status"))
            if (vehicle?.isConnected == true) {
                Text("${number(vehicle.soc)}%", fontSize = 24.sp, color = Color(0xFFD2DDC8),
                    modifier = Modifier.fillMaxWidth())
            } else {
                Text(tr(R.string.vehicle_connection_needed), fontSize = 22.sp,
                    color = Color(0xFFD2DDC8), modifier = Modifier.fillMaxWidth())
            }
        } else {
            Icon(CampIcons.Vehicle, vehicleStatus,
                Modifier.size(28.dp).testTag("sidebar-vehicle-status"), tint = Color(0xFFD2DDC8))
        }
    }
}

@Composable
private fun CampTopbar(
    state: CampUiState,
    vm: CampViewModel,
    wide: Boolean,
    onLanguage: () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(CampPalette.Paper)
            .padding(horizontal = if (wide) 34.dp else 20.dp, vertical = 8.dp),
    ) {
        if (maxWidth >= (640 * fontScale).dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CampTopbarInfo(state, wide, Modifier.weight(1f))
                CampTopbarActions(state, vm, onLanguage)
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CampTopbarInfo(state, wide, Modifier.fillMaxWidth())
                CampTopbarActions(state, vm, onLanguage, Modifier.fillMaxWidth())
            }
        }
    }
    HorizontalDivider(color = CampPalette.Line)
}

@Composable
private fun CampTopbarInfo(state: CampUiState, wide: Boolean, modifier: Modifier) {
    Text(
        if (wide)
            tr(R.string.camp_my_little_outdoors, CampNav.entries[state.tab].label)
        else tr(R.string.camp_brand_name),
        modifier = modifier,
        fontSize = 22.sp,
        color = if (wide) CampPalette.Muted else CampPalette.Forest,
        fontWeight = if (wide) FontWeight.Normal else FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun CampTopbarActions(
    state: CampUiState,
    vm: CampViewModel,
    onLanguage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cartCount = state.data?.cart?.count ?: 0
    val cartDescription = if (cartCount > 0) tr(R.string.camp_cart_item_count, cartCount)
        else tr(R.string.camp_view_cart)
    val actionColors = IconButtonDefaults.iconButtonColors(contentColor = CampPalette.Forest)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.voice(true) }, colors = actionColors,
            modifier = Modifier.size(48.dp).testTag("voice-open")) {
            Icon(CampIcons.Microphone, tr(R.string.camp_voice_command), Modifier.size(24.dp))
        }
        IconButton(onClick = { vm.cart(true) }, colors = actionColors,
            modifier = Modifier.size(48.dp).testTag("cart-open")
                .semantics { contentDescription = cartDescription }) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(CampIcons.ShoppingBag, null, Modifier.size(24.dp))
                if (cartCount > 0) {
                    CampCartBadge(cartCount,
                        Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 5.dp))
                }
            }
        }
        IconButton(onClick = onLanguage, colors = actionColors,
            modifier = Modifier.size(48.dp).testTag("language-settings")) {
            Icon(CampIcons.Globe, tr(R.string.language_settings), Modifier.size(24.dp))
        }
    }
}

@Composable
private fun CampCartBadge(count: Int, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    Box(
        modifier.widthIn(min = 18.dp).height(18.dp)
            .background(MaterialTheme.colorScheme.error, RoundedCornerShape(50))
            .padding(horizontal = 4.dp).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        // The full count is announced by the cart button; keep its decorative pill compact.
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
            Text(if (count > 99) "99+" else count.toString(),
                fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onError, maxLines = 1, softWrap = false,
                style = LocalTextStyle.current.copy(platformStyle = PlatformTextStyle(includeFontPadding = false)))
        }
    }
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
        Text(text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
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
            Icon(icon, null, Modifier.size(24.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, fontSize = 22.sp)
    }
}

@Composable
fun CampPill(text: String, color: Color = CampPalette.Muted) {
    Surface(color = CampPalette.Sage, shape = RoundedCornerShape(5.dp)) {
        Text(
            text,
            fontSize = 22.sp,
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
                CampIcons.WarningCircle,
                null,
                tint = CampPalette.Danger,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text,
                color = CampPalette.Danger,
                fontSize = 22.sp,
                modifier = Modifier.weight(1f).padding(horizontal = 9.dp),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(CampDesign.IconTarget)) {
                Icon(
                    CampIcons.Close,
                    tr(camp.room.app.R.string.camp_dismiss_error),
                    Modifier.size(24.dp),
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
    action: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.7).sp)
        if (subtitle.isNotBlank()) Text(subtitle, fontSize = 22.sp, color = CampPalette.Muted)
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
    )[code] ?: CampText.get(R.string.camp_other_facility)

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
