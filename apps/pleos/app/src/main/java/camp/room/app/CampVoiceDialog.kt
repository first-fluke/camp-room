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
                        "  말로 준비하는 작은 쉼",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.voice(false) }) {
                        Icon(Icons.Outlined.Close, "음성 명령 닫기")
                    }
                }
                Text(
                    "음성을 인식하거나 텍스트로 직접 입력해 주세요. 명령은 먼저 미리보기로 보여드립니다.",
                    fontSize = 12.sp,
                    color = CampPalette.Muted,
                )
                CampPrimary(
                    if (state.busy) "음성을 확인하고 있어요" else "음성으로 말하기",
                    Icons.Outlined.Mic,
                    busy = state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("voice-listen"),
                    onClick = onListen,
                )
                OutlinedTextField(
                    value = state.transcript,
                    onValueChange = vm::transcript,
                    label = { Text("인식 결과 · 직접 입력", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth().testTag("voice-transcript"),
                    minLines = 2,
                    enabled = !state.busy,
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                    shape = RoundedCornerShape(8.dp),
                )
                Text(
                    state.transcriptSource.ifBlank {
                        "음성 사용이 어려우면 직접 입력할 수 있어요. 직접 입력은 녹음 결과가 아닙니다."
                    },
                    fontSize = 10.sp,
                    color = CampPalette.Muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("취침 준비", "독서와 휴식", "길안내").forEach { example ->
                        SuggestionChip(
                            onClick = { vm.transcript(example) },
                            label = { Text(example, fontSize = 11.sp) },
                        )
                    }
                }
                CampSecondary(
                    "명령 미리보기",
                    Icons.Outlined.Visibility,
                    enabled = state.transcript.isNotBlank() && !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("voice-preview"),
                    onClick = vm::previewVoice,
                )
                if (state.voicePreview.isNotBlank())
                    CampSurface(Modifier.fillMaxWidth()) {
                        Text(state.voicePreview, fontSize = 12.sp, color = CampPalette.Forest)
                        if (state.previewTab != null)
                            CampPrimary(
                                "화면에서 확인하기",
                                Icons.Outlined.ArrowForward,
                                modifier = Modifier.fillMaxWidth().testTag("voice-preview-open"),
                                onClick = vm::openVoicePreview,
                            )
                    }
                if (state.error.isNotBlank()) CampError(state.error, vm::clearError)
                Text(
                    "차량 설정과 길안내는 해당 화면의 버튼으로 직접 실행합니다. 음성으로 주문 · 결제를 실행하지 않습니다.",
                    fontSize = 9.sp,
                    color = CampPalette.Muted,
                )
            }
        }
    }
}
