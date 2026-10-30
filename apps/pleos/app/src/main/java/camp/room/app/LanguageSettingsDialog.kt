@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package camp.room.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import camp.room.app.icons.CampIcons

@Composable
fun LanguageSettingsDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val selected = CampLocale.selection(context)
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.widthIn(max = 480.dp).fillMaxWidth(.9f).semantics {
                testTagsAsResourceId = true
            },
            shape = RoundedCornerShape(14.dp),
            color = CampPalette.Paper,
        ) {
            Column(Modifier.heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .9f).dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(CampIcons.Globe, null, tint = CampPalette.Muted)
                    Text(
                        tr(R.string.language_settings),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                    )
                    IconButton(onClick = onClose) {
                        Icon(CampIcons.Close, tr(R.string.language_close))
                    }
                }
                HorizontalDivider(color = CampPalette.Line)
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(15.dp),
                ) {
                    Text(
                        tr(R.string.language_settings_description),
                        fontSize = 22.sp,
                        color = CampPalette.Muted,
                    )
                    listOf(
                        "system" to R.string.language_system,
                        "en" to R.string.language_english,
                        "ko" to R.string.language_korean,
                    ).forEach { (value, label) ->
                        Surface(
                            onClick = {
                                CampLocale.select(context, value)
                                onClose()
                                CampLocale.activity(context)?.recreate()
                            },
                            modifier = Modifier.fillMaxWidth().testTag("language-$value"),
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected == value) CampPalette.Sage else CampPalette.Paper,
                            border =
                                androidx.compose.foundation.BorderStroke(1.dp, CampPalette.Line),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    tr(label),
                                    fontSize = 22.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                RadioButton(selected = selected == value, onClick = null)
                            }
                        }
                    }
                }
            }
        }
    }
}
