package camp.room.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import camp.room.app.icons.CampIcons

@Composable
fun CampUxRestrictedScreen(
    state: CampUxState,
    retry: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(CampPalette.Canvas)
            .padding(32.dp)
            .testTag("ux-restricted-screen"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 620.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Icon(CampIcons.Vehicle, null, Modifier.size(64.dp), tint = CampPalette.Forest)
            Text(
                tr(if (state.observed) R.string.camp_ux_restricted_title else R.string.camp_ux_unavailable_title),
                color = CampPalette.Ink,
                fontSize = 28.sp,
                lineHeight = 36.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                tr(if (state.observed) R.string.camp_ux_restricted_body else R.string.camp_ux_unavailable_body),
                color = CampPalette.Muted,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                textAlign = TextAlign.Center,
            )
            if (!state.observed) {
                CampPrimary(tr(R.string.camp_ux_retry), CampIcons.Refresh, onClick = retry)
            }
        }
    }
}
