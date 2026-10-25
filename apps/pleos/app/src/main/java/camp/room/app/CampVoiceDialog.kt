@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package camp.room.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
fun VoiceCommandDialog(state: CampUiState, vm: CampViewModel, onListen: () -> Unit) {
    Dialog(
        onDismissRequest = { vm.voice(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier =
                Modifier.semantics { testTagsAsResourceId = true }
                    .widthIn(max = 650.dp)
                    .fillMaxWidth(.93f),
            shape = RoundedCornerShape(14.dp),
            color = CampPalette.Paper,
        ) {
            Column(
                Modifier.heightIn(max = 550.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(27.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Mic, null, tint = CampPalette.Muted)
                    Text(
                        tr(camp.room.app.R.string.camp_a_little_rest_with_a_few_words),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = { vm.voice(false) }) {
                        Icon(
                            Icons.Outlined.Close,
                            tr(camp.room.app.R.string.camp_close_voice_command),
                        )
                    }
                }
                Text(
                    tr(camp.room.app.R.string.camp_speak_or_type_a_phrase_we_ll),
                    fontSize = 14.sp,
                    color = CampPalette.Muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                CampPrimary(
                    if (state.busy) tr(camp.room.app.R.string.camp_checking_your_voice)
                    else tr(camp.room.app.R.string.camp_speak_a_command),
                    Icons.Outlined.Mic,
                    busy = state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("voice-listen"),
                    onClick = onListen,
                )
                OutlinedTextField(
                    value = state.transcript,
                    onValueChange = vm::transcript,
                    label = {
                        Text(
                            tr(camp.room.app.R.string.camp_recognized_text_type_instead),
                            fontSize = 14.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag("voice-transcript"),
                    minLines = 2,
                    enabled = !state.busy,
                    textStyle = LocalTextStyle.current.copy(fontSize = 16.sp),
                    shape = RoundedCornerShape(8.dp),
                )
                Text(
                    state.transcriptSource.ifBlank {
                        tr(camp.room.app.R.string.camp_you_can_type_if_voice_is_unavailable)
                    },
                    fontSize = 12.sp,
                    color = CampPalette.Muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                            tr(camp.room.app.R.string.camp_prepare_for_sleep),
                            tr(camp.room.app.R.string.camp_read_and_rest),
                            tr(camp.room.app.R.string.camp_navigation),
                        )
                        .forEach { example ->
                            SuggestionChip(
                                onClick = { vm.transcript(example) },
                                label = {
                                    Text(
                                        example,
                                        fontSize = 12.sp,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                }
                CampSecondary(
                    tr(camp.room.app.R.string.camp_preview_command),
                    Icons.Outlined.Visibility,
                    enabled = state.transcript.isNotBlank() && !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("voice-preview"),
                    onClick = vm::previewVoice,
                )
                if (state.voicePreview.isNotBlank())
                    CampSurface(Modifier.fillMaxWidth()) {
                        Text(
                            state.voicePreview,
                            fontSize = 14.sp,
                            color = CampPalette.Forest,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.previewTab != null)
                            CampPrimary(
                                tr(camp.room.app.R.string.camp_review_on_the_screen),
                                Icons.Outlined.ArrowForward,
                                modifier = Modifier.fillMaxWidth().testTag("voice-preview-open"),
                                onClick = vm::openVoicePreview,
                            )
                    }
                if (state.error.isNotBlank()) CampError(state.error, vm::clearError)
                Text(
                    tr(camp.room.app.R.string.camp_run_vehicle_settings_and_navigation_with_their),
                    fontSize = 12.sp,
                    color = CampPalette.Muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
