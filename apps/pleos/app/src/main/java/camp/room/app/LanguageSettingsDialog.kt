@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package camp.room.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

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
            Column(Modifier.padding(27.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Language, null, tint = CampPalette.Muted)
                    Text(
                        tr(R.string.language_settings),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, tr(R.string.language_close))
                    }
                }
                Text(
                    tr(R.string.language_settings_description),
                    fontSize = 14.sp,
                    color = CampPalette.Muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                listOf(
                        "system" to R.string.language_system,
                        "en" to R.string.language_english,
                        "ko" to R.string.language_korean,
                    )
                    .forEach { (value, label) ->
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
                                    fontSize = 16.sp,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                RadioButton(selected = selected == value, onClick = null)
                            }
                        }
                    }
            }
        }
    }
}
