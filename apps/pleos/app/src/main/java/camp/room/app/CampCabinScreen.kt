@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package camp.room.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class CampScene(
    val id: String,
    val name: String,
    val sub: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

val campScenes =
    listOf(
        CampScene("load", "짐 정리", "도착 후 여유롭게", Icons.Outlined.Inventory2),
        CampScene("together", "함께 쉬기", "모두의 편안함", Icons.Outlined.WbSunny),
        CampScene("split", "독서와 휴식", "앞좌석 독서 · 뒷좌석 휴식", Icons.Outlined.MenuBook),
        CampScene("sleep", "취침 준비", "차분하게 하루 마무리", Icons.Outlined.Bedtime),
        CampScene("departure", "출발 준비", "다음 여정을 향해", Icons.Outlined.DirectionsCar),
    )

@Composable
fun CabinScreen(s: CampUiState, vm: CampViewModel) {
    val vehicle = s.data?.vehicle ?: return
    SectionTitle(
        "차 안에, 우리만의 작은 방.",
        "독서와 휴식, 서로 다른 시간이 편안하게 흐르도록.",
        "MAKE ROOM FOR REST",
        action = { CampPill(if (vehicle.mode == "demo") "시뮬레이터 연결" else "Pleos 차량 상태") },
    )
    CampSurface(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VehicleStatus(Icons.Outlined.BatteryChargingFull, "현재 배터리", "${number(vehicle.soc)}%")
            VehicleStatus(
                Icons.Outlined.Thermostat,
                "실내 측정 온도",
                "${number(vehicle.measuredTemperature)}°C",
            )
            VehicleStatus(
                Icons.Outlined.DoorFront,
                "도어 상태",
                when (vehicle.doorsClosed) {
                    true -> "모두 닫힘"
                    false -> "열린 문 있음"
                    null -> "확인 필요"
                },
            )
            VehicleStatus(
                Icons.Outlined.Air,
                "창문 상태",
                when (vehicle.windowsClosed) {
                    true -> "모두 닫힘"
                    false -> "열린 창문 있음"
                    null -> "확인 필요"
                },
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${vehicle.updatedAt.take(16).replace('T',' ')} 확인",
                    fontSize = 8.sp,
                    color = CampPalette.Muted,
                )
                Text(
                    "${if(vehicle.mode=="demo")"모의 " else ""}${when(vehicle.motionState){"parked"->"정차"
"moving"->"이동"
else->"미확인"}} 상태",
                    fontSize = 8.sp,
                    color = CampPalette.Muted,
                )
            }
        }
    }
    BoxWithConstraints {
        val wide = maxWidth > 720.dp
        if (wide)
            Row(horizontalArrangement = Arrangement.spacedBy(23.dp)) {
                CabinDiagram(vehicle, Modifier.weight(1.18f))
                ScenePanel(s, vm, Modifier.weight(1f))
            }
        else
            Column(verticalArrangement = Arrangement.spacedBy(23.dp)) {
                CabinDiagram(vehicle, Modifier.fillMaxWidth())
                ScenePanel(s, vm, Modifier.fillMaxWidth())
            }
    }
    CampSurface(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("나에게 맞게 조금 더", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("사용자가 직접 적용한 설정만 실행해요", fontSize = 9.sp, color = CampPalette.Muted)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            Column(Modifier.weight(1f)) {
                Text("목표 실내 온도", fontSize = 11.sp, color = CampPalette.Muted)
                Row(
                    Modifier.fillMaxWidth().padding(top = 17.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedIconButton(
                        onClick = {
                            vm.control("targetTemperature", (vehicle.targetTemperature ?: 22.0) - 1)
                        },
                        enabled = !s.busy && (vehicle.targetTemperature ?: 22.0) > 16,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Outlined.Remove, "목표 온도 낮추기", Modifier.size(16.dp))
                    }
                    Text(
                        "${number(vehicle.targetTemperature)}°C",
                        fontSize = 31.sp,
                        fontWeight = FontWeight.Light,
                    )
                    OutlinedIconButton(
                        onClick = {
                            vm.control("targetTemperature", (vehicle.targetTemperature ?: 22.0) + 1)
                        },
                        enabled = !s.busy && (vehicle.targetTemperature ?: 22.0) < 30,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Outlined.Add, "목표 온도 높이기", Modifier.size(16.dp))
                    }
                }
                Text(
                    "측정 온도와 설정 목표는 달라요",
                    fontSize = 8.sp,
                    color = CampPalette.Muted,
                    modifier = Modifier.padding(top = 9.dp),
                )
            }
            ZoneControls("앞좌석", "front", vehicle.front, s.busy, vm, Modifier.weight(1f))
            ZoneControls("뒷좌석", "rear", vehicle.rear, s.busy, vm, Modifier.weight(1f))
        }
    }
    if (s.commands.isNotEmpty())
        CampSurface(Modifier.fillMaxWidth().testTag("scene-results")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (s.commandStatus == "completed") Icons.Outlined.CheckCircle
                    else Icons.Outlined.ErrorOutline,
                    null,
                    Modifier.size(21.dp),
                    tint = CampPalette.Muted,
                )
                Text("  공간 설정 처리 결과", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                CampPill(if (s.commandStatus == "completed") "항목 확인 완료" else "일부 확인 필요")
            }
            s.commands.forEach { result ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(5.dp)
                            .background(
                                if (result.status == "applied") Color(0xFF8EAA6B)
                                else CampPalette.Danger,
                                CircleShape,
                            )
                    )
                    Text(
                        controlLabel(result.control),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(140.dp).padding(start = 8.dp),
                    )
                    Text(
                        result.message,
                        fontSize = 9.sp,
                        color = CampPalette.Muted,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        when (result.confirmation) {
                            "observed" -> "관측값 확인"
                            "acknowledged" -> "명령 수락"
                            else -> "상태 확인 필요"
                        },
                        fontSize = 9.sp,
                        color = CampPalette.Muted,
                    )
                }
                HorizontalDivider(color = CampPalette.Line)
            }
            Text(
                if (vehicle.mode == "demo") "명령 수락과 관측 상태는 다릅니다. 위 결과는 데모 시뮬레이터입니다."
                else "명령 수락과 관측 상태는 다릅니다. 미관측 항목은 확인 필요로 유지됩니다.",
                fontSize = 9.sp,
                color = CampPalette.Muted,
            )
        }
    if (s.sceneId == "departure")
        CampSurface(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, null, tint = CampPalette.Muted)
                Column(Modifier.weight(1f).padding(horizontal = 15.dp)) {
                    Text("다음 여정도 가볍게.", fontSize = 16.sp)
                    Text(
                        "문 · 창문 상태와 배터리 ${number(vehicle.soc)}%를 확인하고 다음 목적지로 이어가세요.",
                        fontSize = 11.sp,
                        color = CampPalette.Muted,
                        modifier = Modifier.padding(top = 7.dp),
                    )
                }
                CampSecondary("이동 계획 보기", Icons.Outlined.ArrowForward, onClick = { vm.tab(1) })
            }
        }
}

@Composable
private fun VehicleStatus(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(24.dp), tint = CampPalette.Muted)
        Column(Modifier.padding(start = 10.dp)) {
            Text(label, fontSize = 9.sp, color = CampPalette.Muted)
            Text(
                value,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

@Composable
private fun ZoneControls(
    label: String,
    zone: String,
    value: CabinZone,
    busy: Boolean,
    vm: CampViewModel,
    modifier: Modifier,
) {
    var blind by remember(value.blind) { mutableFloatStateOf((value.blind ?: 0).toFloat()) }
    Column(modifier) {
        Text("$label 공간", fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (value.reading == null) "독서등 · 확인 필요" else "독서등",
                fontSize = 10.sp,
                color = CampPalette.Muted,
            )
            Spacer(Modifier.weight(1f))
            Switch(
                checked = value.reading == true,
                onCheckedChange = { vm.control("readingLight", it, zone) },
                enabled = !busy && value.reading != null,
                modifier = Modifier.height(28.dp),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Text("차광", fontSize = 10.sp, color = CampPalette.Muted)
            Spacer(Modifier.weight(1f))
            Text(
                if (value.blind == null) "확인 필요" else "${blind.toInt()}%",
                fontSize = 10.sp,
                color = CampPalette.Muted,
            )
        }
        Slider(
            value = blind,
            onValueChange = { blind = it },
            onValueChangeFinished = { vm.control("blindLevel", blind.toInt(), zone) },
            valueRange = 0f..100f,
            steps = 9,
            enabled = !busy && value.blind != null,
            modifier = Modifier.fillMaxWidth().height(30.dp),
        )
    }
}

@Composable
private fun ScenePanel(s: CampUiState, vm: CampViewModel, modifier: Modifier) {
    CampSurface(modifier) {
        Text(
            "MOMENTS IN YOUR ROOM",
            fontSize = 8.sp,
            letterSpacing = 1.4.sp,
            color = CampPalette.Muted,
        )
        Text("지금, 어떤 쉼이 필요한가요?", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        campScenes.forEach { scene ->
            val selected = scene.id == s.sceneId
            Surface(
                onClick = { vm.scene(scene.id) },
                color = if (selected) Color(0xFFF0F5E6) else CampPalette.Paper,
                shape = RoundedCornerShape(8.dp),
                border =
                    androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (selected) Color(0xFF8EA870) else CampPalette.Line,
                    ),
                modifier = Modifier.fillMaxWidth().testTag("scene-${scene.id}"),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(35.dp)
                            .background(
                                if (selected) Color(0xFFDCE8CD) else Color(0xFFF1F4EA),
                                RoundedCornerShape(7.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(scene.icon, null, Modifier.size(23.dp), tint = CampPalette.Muted)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(scene.name, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(
                            scene.sub,
                            fontSize = 9.sp,
                            color = CampPalette.Muted,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    RadioButton(
                        selected = selected,
                        onClick = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Text(
            when (s.sceneId) {
                "split" -> "앞좌석은 독서에 집중하도록 밝게, 뒷좌석은 휴식에 알맞게 차분하게 설정해요."
                "sleep" -> "빛은 낮추고 차광을 더해, 편안한 밤을 준비해요."
                "departure" -> "차광과 조명을 정리하고 출발 전 상태를 확인해요."
                "load" -> "짐을 정리하기 편하도록 앞뒤 공간을 밝혀요."
                else -> "함께 머무는 시간에 어울리는 은은한 조명과 온도로 맞춰요."
            },
            fontSize = 10.sp,
            color = CampPalette.Muted,
            lineHeight = 17.sp,
        )
        CampPrimary(
            "이 공간 설정 적용하기",
            Icons.Outlined.Check,
            busy = s.busy,
            modifier = Modifier.fillMaxWidth().testTag("apply-scene"),
            onClick = vm::applyScene,
        )
        Text("지원 항목 실행 후 시뮬레이터 관측값을 다시 확인합니다.", fontSize = 8.sp, color = CampPalette.Muted)
    }
}

@Composable
private fun CabinDiagram(vehicle: Vehicle, modifier: Modifier) {
    Surface(
        modifier = modifier.height(566.dp),
        color = CampPalette.Sage,
        shape = RoundedCornerShape(11.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CampPalette.Line),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.padding(25.dp)) {
                Text(
                    "MY LITTLE RETREAT",
                    fontSize = 8.sp,
                    letterSpacing = 1.5.sp,
                    color = CampPalette.Muted,
                )
                Text(
                    if (vehicle.sceneId == "split") "각자의 시간이, 함께 흐르는 공간"
                    else if (vehicle.sceneId == "sleep") "하루를 접고, 편안한 밤으로" else "오늘의 쉼을 담는 작은 방",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Canvas(
                Modifier.fillMaxWidth().height(440.dp).align(Alignment.Center).padding(top = 35.dp)
            ) {
                val scale = size.height / 440f
                val carW = 185f * scale
                val carH = 389f * scale
                val left = size.width / 2 - carW / 2
                val top = (size.height - carH) / 2 + 5f
                fun rect(color: Color, x: Float, y: Float, w: Float, h: Float, r: Float = 8f) {
                    drawRoundRect(
                        color,
                        Offset(left + x * scale, top + y * scale),
                        Size(w * scale, h * scale),
                        CornerRadius(r * scale),
                    )
                }
                rect(Color(0xFF8F9F80), -5f, 0f, 195f, 389f, 60f)
                rect(Color(0xFFB6C5A6), 2f, 7f, 181f, 375f, 57f)
                rect(Color(0xFF52705D), 15f, 26f, 155f, 62f, 24f)
                rect(Color(0xFF9DAC90), 18f, 94f, 149f, 23f, 6f)
                drawCircle(
                    Color(0xFF6D825E),
                    10f * scale,
                    Offset(left + 49f * scale, top + 106f * scale),
                    style = Stroke(3f * scale),
                )
                rect(Color(0xFF617B5F), 84f, 99f, 37f, 12f, 3f)
                rect(
                    if (vehicle.front.reading == true) Color(0xFFE1D5A0) else Color(0xFFBAC8A8),
                    14f,
                    127f,
                    157f,
                    124f,
                    8f,
                )
                rect(Color(0xFF94A688), 26f, 140f, 46f, 65f, 13f)
                rect(Color(0xFF94A688), 112f, 140f, 46f, 65f, 13f)
                rect(Color(0xFFB8C8A6), 34f, 135f, 30f, 12f, 5f)
                rect(Color(0xFFB8C8A6), 120f, 135f, 30f, 12f, 5f)
                rect(Color(0xFF9DAD8D), 84f, 147f, 17f, 51f, 5f)
                rect(
                    if (vehicle.rear.reading == true) Color(0xFFD7D29E) else Color(0xFF9FAF93),
                    14f,
                    260f,
                    157f,
                    88f,
                    8f,
                )
                rect(Color(0xFF8F9F80), 26f, 271f, 133f, 34f, 9f)
                rect(Color(0xFFB2C2A3), 30f, 266f, 58f, 10f, 4f)
                rect(Color(0xFFB2C2A3), 97f, 266f, 58f, 10f, 4f)
                rect(Color(0xFF607E64), 27f, 356f, 131f, 18f, 10f)
                rect(Color(0xFF8F9F80), -19f, 104f, 15f, 30f, 5f)
                rect(Color(0xFF8F9F80), 189f, 104f, 15f, 30f, 5f)
                drawLine(
                    Color(0xFF9EB18B),
                    Offset(left - 67f * scale, top + 174f * scale),
                    Offset(left + 15f * scale, top + 174f * scale),
                    1.2f,
                )
                drawLine(
                    Color(0xFF9EB18B),
                    Offset(left + 170f * scale, top + 300f * scale),
                    Offset(left + 243f * scale, top + 300f * scale),
                    1.2f,
                )
            }
            Column(
                Modifier.align(Alignment.Center).offset(y = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(60.dp))
                Text(
                    "앞좌석 ${when(vehicle.front.reading){true->"독서"
false->"휴식"
null->"확인 필요"}}",
                    fontSize = 10.sp,
                    color = CampPalette.Forest,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "조명 ${vehicle.front.ambient?.let{"$it%"}?:"확인 필요"} · 차광 ${vehicle.front.blind?.let{"$it%"}?:"확인 필요"}",
                    fontSize = 7.sp,
                    color = CampPalette.Muted,
                )
                Spacer(Modifier.height(72.dp))
                Text(
                    "뒷좌석 ${when(vehicle.rear.reading){true->"함께 쉬기"
false->"휴식"
null->"확인 필요"}}",
                    fontSize = 10.sp,
                    color = CampPalette.Forest,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "조명 ${vehicle.rear.ambient?.let{"$it%"}?:"확인 필요"} · 차광 ${vehicle.rear.blind?.let{"$it%"}?:"확인 필요"}",
                    fontSize = 7.sp,
                    color = CampPalette.Muted,
                )
            }
            Column(
                Modifier.align(Alignment.CenterStart).padding(start = 19.dp).offset(y = (-45).dp)
            ) {
                Icon(Icons.Outlined.Lightbulb, null, Modifier.size(19.dp), tint = CampPalette.Muted)
                Text("집중하는 빛", fontSize = 8.sp, color = CampPalette.Muted)
                Text(
                    when (vehicle.front.reading) {
                        true -> "독서등 켜짐"
                        false -> "독서등 꺼짐"
                        null -> "상태 확인 필요"
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Column(Modifier.align(Alignment.CenterEnd).padding(end = 19.dp).offset(y = 105.dp)) {
                Icon(Icons.Outlined.Air, null, Modifier.size(20.dp), tint = CampPalette.Muted)
                Text("편안한 온도", fontSize = 8.sp, color = CampPalette.Muted)
                Text(
                    "목표 ${number(vehicle.targetTemperature)}°C",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    null,
                    Modifier.size(14.dp),
                    tint = CampPalette.Muted,
                )
                Text(
                    if (vehicle.mode == "demo") "  데모 시뮬레이터 관측 상태" else "  Pleos 차량 관측 상태",
                    fontSize = 8.sp,
                    color = CampPalette.Muted,
                )
            }
        }
    }
}

fun controlLabel(value: String): String {
    val zone =
        if (value.startsWith("front")) "앞좌석 " else if (value.startsWith("rear")) "뒷좌석 " else ""
    return zone +
        when (value.substringAfterLast('.')) {
            "readingLight" -> "독서등"
            "ambientBrightness" -> "앰비언트 밝기"
            "blindLevel" -> "차광"
            "targetTemperature" -> "목표 온도"
            else -> value
        }
}

@Composable
fun CampLandscape(id: String, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val shore = id == "solbaram" || id == "island"
        val sky = if (shore) Color(0xFFD7E6DF) else Color(0xFFDDE2CF)
        val mountain = if (shore) Color(0xFF87AAA0) else Color(0xFF93A68C)
        drawRect(sky)
        drawCircle(
            Color(0xFFF0E4BC),
            size.height * .13f,
            Offset(size.width * .76f, size.height * .23f),
        )
        val back =
            Path().apply {
                moveTo(0f, size.height * .65f)
                lineTo(size.width * .18f, size.height * .2f)
                lineTo(size.width * .38f, size.height * .63f)
                lineTo(size.width * .58f, size.height * .12f)
                lineTo(size.width * .83f, size.height * .61f)
                lineTo(size.width, size.height * .32f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
        drawPath(back, mountain)
        val front =
            Path().apply {
                moveTo(0f, size.height * .7f)
                lineTo(size.width * .18f, size.height * .49f)
                lineTo(size.width * .43f, size.height * .77f)
                lineTo(size.width * .73f, size.height * .43f)
                lineTo(size.width, size.height * .67f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
        drawPath(front, Color(0xFF5D806A))
        drawRect(
            if (shore) Color(0xFF9EC2B4) else Color(0xFFAAC1AA),
            Offset(0f, size.height * .72f),
            Size(size.width, size.height * .28f),
        )
        val grass =
            Path().apply {
                moveTo(0f, size.height * .91f)
                quadraticTo(
                    size.width * .3f,
                    size.height * .75f,
                    size.width * .6f,
                    size.height * .87f,
                )
                quadraticTo(size.width * .8f, size.height * .9f, size.width, size.height * .76f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
        drawPath(grass, Color(0xFFA7AF87))
        fun tree(x: Float, y: Float, h: Float) {
            drawLine(Color(0xFF34543D), Offset(x, y), Offset(x, y - h * .7f), size.width * .008f)
            val p =
                Path().apply {
                    moveTo(x, y - h)
                    lineTo(x - h * .2f, y - h * .38f)
                    lineTo(x - h * .11f, y - h * .38f)
                    lineTo(x - h * .27f, y - h * .12f)
                    lineTo(x + h * .27f, y - h * .12f)
                    lineTo(x + h * .11f, y - h * .38f)
                    lineTo(x + h * .2f, y - h * .38f)
                    close()
                }
            drawPath(p, Color(0xFF345942))
        }
        tree(size.width * .09f, size.height * .97f, size.height * .86f)
        tree(size.width * .19f, size.height * .95f, size.height * .6f)
        tree(size.width * .91f, size.height * .94f, size.height * .86f)
        tree(size.width * .99f, size.height * .95f, size.height * .61f)
        val tent =
            Path().apply {
                moveTo(size.width * .31f, size.height * .92f)
                lineTo(size.width * .48f, size.height * .56f)
                lineTo(size.width * .69f, size.height * .93f)
                close()
            }
        drawPath(tent, Color(0xFFDCAD7D))
        val side =
            Path().apply {
                moveTo(size.width * .48f, size.height * .56f)
                lineTo(size.width * .49f, size.height * .93f)
                lineTo(size.width * .69f, size.height * .93f)
                close()
            }
        drawPath(side, Color(0xFFB78A60))
        val entrance =
            Path().apply {
                moveTo(size.width * .38f, size.height * .92f)
                lineTo(size.width * .478f, size.height * .63f)
                lineTo(size.width * .49f, size.height * .93f)
                close()
            }
        drawPath(entrance, Color(0xFF34543C))
        drawLine(
            Color(0xFFE7CDA4),
            Offset(size.width * .31f, size.height * .92f),
            Offset(size.width * .69f, size.height * .93f),
            size.height * .008f,
        )
    }
}

@Composable
fun ProductArtwork(id: String, modifier: Modifier = Modifier) {
    val icon =
        when {
            id.contains("lantern") || id.contains("light") -> Icons.Outlined.Light
            id.contains("sleep") || id.contains("blanket") -> Icons.Outlined.Bedtime
            id.contains("chair") -> Icons.Outlined.Weekend
            else -> Icons.Outlined.Backpack
        }
    Box(
        modifier.background(if (id.contains("sleep")) Color(0xFFE4E9D8) else Color(0xFFEDECDD)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawOval(
                Color(0xFF747954).copy(alpha = .12f),
                Offset(size.width * .31f, size.height * .73f),
                Size(size.width * .38f, size.height * .1f),
            )
        }
        Icon(
            icon,
            null,
            Modifier.size(65.dp),
            tint = if (id.contains("sleep")) Color(0xFF8D9F74) else Color(0xFFAE9670),
        )
    }
}
