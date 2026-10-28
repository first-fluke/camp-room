@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package camp.room.app

import camp.room.app.icons.CampIcons

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
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
                Modifier.imePadding().semantics { testTagsAsResourceId = true }
                    .widthIn(max = 650.dp)
                    .fillMaxWidth(.93f),
            shape = RoundedCornerShape(14.dp),
            color = CampPalette.Paper,
        ) {
            Column(Modifier.heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .9f).dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(CampIcons.Microphone, null, tint = CampPalette.Muted)
                    Text(
                        tr(camp.room.app.R.string.camp_a_little_rest_with_a_few_words),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.voice(false) }) {
                        Icon(
                            CampIcons.Close,
                            tr(camp.room.app.R.string.camp_close_voice_command),
                        )
                    }
                }
                HorizontalDivider(color = CampPalette.Line)
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {

                Text(
                    tr(camp.room.app.R.string.camp_speak_or_type_a_phrase_we_ll),
                    fontSize = 22.sp,
                    color = CampPalette.Muted,
                )
                CampPrimary(
                    if (state.busy) tr(camp.room.app.R.string.camp_checking_your_voice)
                    else tr(camp.room.app.R.string.camp_speak_a_command),
                    CampIcons.Microphone,
                    enabled = state.sttRetryable && state.interactionAllowed,
                    busy = state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("voice-listen"),
                    onClick = onListen,
                )
                if (state.sttMessage.isNotBlank())
                    Text(
                        state.sttMessage,
                        fontSize = 22.sp,
                        color = CampPalette.Muted,
                    )
                OutlinedTextField(
                    value = state.transcript,
                    onValueChange = vm::transcript,
                    label = {
                        Text(
                            tr(camp.room.app.R.string.camp_recognized_text_type_instead),
                            fontSize = 22.sp,
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag("voice-transcript"),
                    minLines = 2,
                    maxLines = 3,
                    enabled = !state.busy && state.interactionAllowed,
                    textStyle = LocalTextStyle.current.copy(fontSize = 22.sp),
                    shape = RoundedCornerShape(8.dp),
                )
                Text(
                    state.transcriptSource.ifBlank {
                        tr(camp.room.app.R.string.camp_you_can_type_if_voice_is_unavailable)
                    },
                    fontSize = 22.sp,
                    color = CampPalette.Muted,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                            tr(camp.room.app.R.string.camp_prepare_for_sleep),
                            tr(camp.room.app.R.string.camp_read_and_rest),
                            tr(camp.room.app.R.string.camp_navigation),
                        )
                        .forEach { example ->
                            SuggestionChip(
                                onClick = { vm.transcript(example) },
                                enabled = !state.busy && state.interactionAllowed,
                                label = {
                                    Text(
                                        example,
                                        fontSize = 22.sp,
                                    )
                                },
                            )
                        }
                }
                CampSecondary(
                    tr(camp.room.app.R.string.camp_preview_command),
                    CampIcons.Eye,
                    enabled = state.transcript.isNotBlank() && !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("voice-preview"),
                    onClick = vm::previewVoice,
                )
                if (state.voicePreview.isNotBlank())
                    CampSurface(Modifier.fillMaxWidth()) {
                        Text(
                            state.voicePreview,
                            fontSize = 22.sp,
                            color = CampPalette.Forest,
                        )
                        if (state.previewTab != null)
                            CampPrimary(
                                tr(camp.room.app.R.string.camp_review_on_the_screen),
                                CampIcons.ChevronRight,
                                modifier = Modifier.fillMaxWidth().testTag("voice-preview-open"),
                                onClick = vm::openVoicePreview,
                            )
                    }
                if (state.error.isNotBlank()) CampError(state.error, vm::clearError)
                Text(
                    tr(R.string.vehicle_voice_help),
                    fontSize = 22.sp,
                    color = CampPalette.Muted,
                )
                }
            }
        }
    }
}
