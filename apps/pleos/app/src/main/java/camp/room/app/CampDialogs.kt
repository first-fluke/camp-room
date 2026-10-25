@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package camp.room.app

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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun CampsiteDetailDialog(s: CampUiState, vm: CampViewModel) {
    val site = s.selectedSite ?: return
    val screen = LocalConfiguration.current
    val height = (screen.screenHeightDp * .9f).dp
    Dialog(
        onDismissRequest = { vm.detail(null) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier =
                Modifier.semantics { testTagsAsResourceId = true }
                    .widthIn(max = 790.dp)
                    .fillMaxWidth(.92f)
                    .height(height),
            shape = RoundedCornerShape(15.dp),
            color = CampPalette.Paper,
        ) {
            Column {
                Box(
                    Modifier.fillMaxWidth()
                        .height(if (screen.screenHeightDp < 700) 142.dp else 205.dp)
                ) {
                    CampLandscape(site.id, Modifier.fillMaxSize())
                    IconButton(
                        onClick = { vm.detail(null) },
                        modifier =
                            Modifier.align(Alignment.TopEnd)
                                .padding(12.dp)
                                .background(CampPalette.Paper.copy(alpha = .9f), CircleShape)
                                .size(33.dp),
                    ) {
                        Icon(Icons.Outlined.Close, "캠핑장 상세 닫기", Modifier.size(21.dp))
                    }
                    Box(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                        CampPill(if (site.sourceMode == "demo") "데모 캠핑장" else "외부 데이터")
                    }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(25.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(site.region, fontSize = 10.sp, color = CampPalette.Muted)
                    Text(site.name, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        site.description,
                        fontSize = 12.sp,
                        color = CampPalette.Muted,
                        lineHeight = 21.sp,
                    )
                    Text(site.address, fontSize = 10.sp, color = CampPalette.Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        site.facilities.take(5).forEach { CampPill(facilityName(it)) }
                    }
                    HorizontalDivider(color = CampPalette.Line)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        DetailFact(
                            "차량 내 숙박",
                            when (site.carCamping) {
                                true -> "허용"
                                false -> "미허용"
                                null -> "운영자 확인 필요"
                            },
                        )
                        DetailFact(
                            "캠핑용 전기",
                            if ("electricity" in site.facilities) "제공" else "확인 필요",
                        )
                        DetailFact(
                            "전기차 충전",
                            when (site.evCharging) {
                                true -> "가능"
                                false -> "불가"
                                null -> "확인 필요"
                            },
                        )
                        DetailFact("입실 · 퇴실", "${site.checkInTime} · ${site.checkOutTime}")
                    }
                    HorizontalDivider(color = CampPalette.Line)
                    Text("편안한 캠핑을 위한 약속", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    site.rules.forEach {
                        Row {
                            Icon(
                                Icons.Outlined.Spa,
                                null,
                                Modifier.size(15.dp),
                                tint = CampPalette.Muted,
                            )
                            Text("  $it", fontSize = 11.sp, color = CampPalette.Muted)
                        }
                    }
                    Surface(color = CampPalette.Sage, shape = RoundedCornerShape(7.dp)) {
                        Row(Modifier.padding(15.dp)) {
                            Icon(
                                Icons.Outlined.LocalShipping,
                                null,
                                Modifier.size(23.dp),
                                tint = CampPalette.Muted,
                            )
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(
                                    "캠핑장 택배 ${if(site.acceptsParcels)"수령 가능" else "수령 불가"}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    "${site.receivingLocation} · ${site.receivingInstructions}",
                                    fontSize = 10.sp,
                                    color = CampPalette.Muted,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CampPill(site.availability.label)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(
                                site.availability.provider,
                                fontSize = 9.sp,
                                color = CampPalette.Muted,
                            )
                            Text(
                                "${site.availability.coverage} · ${site.availability.checkedAt.take(16).replace('T',' ')} 조회",
                                fontSize = 8.sp,
                                color = CampPalette.Muted,
                            )
                        }
                    }
                    if (s.error.isNotBlank()) CampError(s.error, vm::clearError)
                }
                HorizontalDivider(color = CampPalette.Line)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 25.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(won(site.price), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${displayDate(s.checkIn)} — ${displayDate(s.checkOut)} · ${s.guests}명",
                            fontSize = 10.sp,
                            color = CampPalette.Muted,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    CampPrimary(
                        "내 캠핑에 담기",
                        Icons.Outlined.Add,
                        busy = s.busy,
                        modifier = Modifier.testTag("save-trip"),
                        onClick = vm::saveTrip,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailFact(label: String, value: String) {
    Column {
        Text(label, fontSize = 9.sp, color = CampPalette.Muted)
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 7.dp),
        )
    }
}

@Composable
fun CartDialog(s: CampUiState, vm: CampViewModel) {
    val data = s.data ?: return
    val screen = LocalConfiguration.current
    val height = (screen.screenHeightDp * .92f).dp
    Dialog(
        onDismissRequest = { vm.cart(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier =
                Modifier.semantics { testTagsAsResourceId = true }
                    .widthIn(max = 1050.dp)
                    .fillMaxWidth(.96f)
                    .height(height),
            color = CampPalette.Paper,
            shape = RoundedCornerShape(15.dp),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 27.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "PACK A LITTLE HAPPINESS",
                            fontSize = 8.sp,
                            letterSpacing = 1.4.sp,
                            color = CampPalette.Muted,
                        )
                        Text(
                            "가벼운 준비, 편안한 캠핑.",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 7.dp),
                        )
                        Text(
                            "이번 여정에 필요한 것들을 챙겨보세요.",
                            fontSize = 10.sp,
                            color = CampPalette.Muted,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                    }
                    IconButton(onClick = { vm.cart(false) }) {
                        Icon(Icons.Outlined.Close, "장바구니 닫기")
                    }
                }
                HorizontalDivider(color = CampPalette.Line)
                if (s.order != null) OrderConfirmation(s.order, vm, Modifier.weight(1f))
                else
                    BoxWithConstraints(Modifier.weight(1f)) {
                        if (maxWidth > 680.dp)
                            Row(Modifier.fillMaxSize()) {
                                CartItems(s, vm, Modifier.weight(1.1f).fillMaxHeight())
                                CheckoutPanel(s, vm, Modifier.weight(1f).fillMaxHeight())
                            }
                        else
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                CartItemsContent(s, vm, Modifier.fillMaxWidth())
                                CheckoutContent(s, vm, Modifier.fillMaxWidth())
                            }
                    }
            }
        }
    }
}

@Composable
private fun CartItems(s: CampUiState, vm: CampViewModel, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        CartItemsContent(s, vm, Modifier.fillMaxWidth())
    }
}

@Composable
private fun CartItemsContent(s: CampUiState, vm: CampViewModel, modifier: Modifier) {
    val data = s.data ?: return
    Column(modifier.padding(25.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("담은 준비물  ${data.cart.count}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (data.cart.items.isEmpty())
            Column(
                Modifier.fillMaxWidth().padding(vertical = 23.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.ShoppingBag,
                    null,
                    Modifier.size(42.dp),
                    tint = CampPalette.Muted,
                )
                Text(
                    "아직 비어 있는 여행 가방",
                    fontSize = 13.sp,
                    color = CampPalette.Muted,
                    modifier = Modifier.padding(top = 13.dp),
                )
                Text(
                    "아래 추천 준비물을 담아보세요.",
                    fontSize = 11.sp,
                    color = CampPalette.Muted,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        data.cart.items.forEach { item ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ProductArtwork(item.id, Modifier.size(73.dp))
                Column(Modifier.weight(1f).padding(start = 13.dp)) {
                    Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(
                        won(item.unitPrice),
                        fontSize = 10.sp,
                        color = CampPalette.Muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        OutlinedIconButton(
                            onClick = { vm.quantity(item.id, item.quantity - 1) },
                            enabled = !s.busy && item.quantity > 1,
                            modifier = Modifier.size(26.dp),
                        ) {
                            Icon(Icons.Outlined.Remove, "${item.name} 수량 줄이기", Modifier.size(15.dp))
                        }
                        Text(
                            item.quantity.toString(),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 13.dp),
                        )
                        OutlinedIconButton(
                            onClick = { vm.quantity(item.id, item.quantity + 1) },
                            enabled = !s.busy,
                            modifier = Modifier.size(26.dp),
                        ) {
                            Icon(Icons.Outlined.Add, "${item.name} 수량 늘리기", Modifier.size(15.dp))
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    IconButton(
                        onClick = { vm.quantity(item.id, 0) },
                        enabled = !s.busy,
                        modifier = Modifier.size(26.dp),
                    ) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            "${item.name} 삭제",
                            Modifier.size(18.dp),
                            tint = CampPalette.Muted,
                        )
                    }
                    Spacer(Modifier.height(23.dp))
                    Text(won(item.lineTotal), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            HorizontalDivider(color = CampPalette.Line)
        }
        Spacer(Modifier.height(5.dp))
        Text(
            "이런 준비물은 어때요?",
            fontSize = 13.sp,
            color = CampPalette.Muted,
            fontWeight = FontWeight.Medium,
        )
        data.products.forEach { product ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ProductArtwork(product.id, Modifier.size(51.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(product.name, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text(
                        won(product.price),
                        fontSize = 10.sp,
                        color = CampPalette.Muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                FilledTonalIconButton(
                    onClick = { vm.addProduct(product.id) },
                    enabled = !s.busy && product.stock > 0,
                    modifier = Modifier.size(31.dp).testTag("cart-product-add-${product.id}"),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Icon(Icons.Outlined.Add, "${product.name} 추가 담기", Modifier.size(19.dp))
                }
            }
        }
    }
}

@Composable
private fun CheckoutPanel(s: CampUiState, vm: CampViewModel, modifier: Modifier) {
    Column(modifier.background(Color(0xFFF2F5EA)).verticalScroll(rememberScrollState())) {
        CheckoutContent(s, vm, Modifier.fillMaxWidth())
    }
}

@Composable
private fun CheckoutContent(s: CampUiState, vm: CampViewModel, modifier: Modifier) {
    val cart = s.data?.cart ?: return
    val site = s.tripSite
    val trip = s.data.trip
    Column(
        modifier.background(Color(0xFFF2F5EA)).padding(25.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("어디에서 받을까요?", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            DeliveryChoice("자택", "home", Icons.Outlined.Home, s, vm, Modifier.weight(1f))
            DeliveryChoice("캠핑장", "campsite", Icons.Outlined.Forest, s, vm, Modifier.weight(1f))
        }
        OutlinedTextField(
            value = s.recipient,
            onValueChange = { vm.delivery(recipient = it) },
            label = { Text("받는 분", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("recipient-name"),
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
            shape = RoundedCornerShape(7.dp),
        )
        if (s.destination == "home")
            OutlinedTextField(
                value = s.address,
                onValueChange = { vm.delivery(address = it) },
                label = { Text("자택 배송 주소", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth().testTag("delivery-address"),
                minLines = 2,
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                shape = RoundedCornerShape(7.dp),
            )
        else {
            Surface(color = CampPalette.Sage, shape = RoundedCornerShape(7.dp)) {
                Row(Modifier.padding(13.dp)) {
                    Icon(
                        Icons.Outlined.LocationOn,
                        null,
                        Modifier.size(19.dp),
                        tint = CampPalette.Muted,
                    )
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(
                            site?.name ?: "캠핑장을 먼저 선택해 주세요",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            site?.receivingLocation.orEmpty(),
                            fontSize = 10.sp,
                            color = CampPalette.Muted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            if (site?.acceptsParcels == true) "택배 수령 가능" else "택배 수령 불가 또는 확인 필요",
                            fontSize = 9.sp,
                            color = CampPalette.Muted,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                    }
                }
            }
            OutlinedTextField(
                value = s.siteReference,
                onValueChange = { vm.delivery(siteReference = it) },
                label = { Text("사이트 · 예약 참조", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("site-reference"),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                shape = RoundedCornerShape(7.dp),
            )
            Text(
                "체류 ${displayDate(trip?.checkIn.orEmpty())} — ${displayDate(trip?.checkOut.orEmpty())}",
                fontSize = 9.sp,
                color = CampPalette.Muted,
            )
        }
        Text("입력된 이름과 주소는 시연을 위한 가상 정보입니다.", fontSize = 8.sp, color = CampPalette.Muted)
        CampSecondary(
            "배송 가능 여부 확인",
            Icons.Outlined.Schedule,
            enabled = !s.busy && cart.items.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag("delivery-quote"),
            onClick = vm::quote,
        )
        s.quote?.let { quote ->
            Surface(
                color = if (quote.eligible) Color(0xFFE6EFDA) else Color(0xFFFAEEE0),
                shape = RoundedCornerShape(7.dp),
            ) {
                Row(Modifier.padding(14.dp)) {
                    Icon(
                        if (quote.eligible) Icons.Outlined.CheckCircle
                        else Icons.Outlined.ErrorOutline,
                        null,
                        Modifier.size(21.dp),
                        tint = if (quote.eligible) CampPalette.Muted else CampPalette.Danger,
                    )
                    Column(Modifier.padding(start = 9.dp)) {
                        Text(
                            if (quote.eligible) "${quote.eta} 도착 예정" else "이 배송 조건으로는 받을 수 없어요",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        if (quote.reason.isNotBlank())
                            Text(
                                quote.reason,
                                fontSize = 10.sp,
                                color = CampPalette.Muted,
                                modifier = Modifier.padding(top = 5.dp),
                            )
                        quote.conditions.forEach {
                            Text(
                                it,
                                fontSize = 9.sp,
                                color = CampPalette.Muted,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
        if (s.error.isNotBlank()) CampError(s.error, vm::clearError)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PriceRow("상품 금액", won(cart.subtotal))
            PriceRow("배송비", won(s.quote?.shipping ?: cart.shipping))
            HorizontalDivider(
                color = CampPalette.Line,
                modifier = Modifier.padding(vertical = 5.dp),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("총 주문 금액", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(
                    won(s.quote?.total ?: cart.total),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        CampPrimary(
            "${won(s.quote?.total?:cart.total)} 데모 결제",
            Icons.Outlined.ShoppingBag,
            enabled = s.quote?.eligible == true && cart.items.isNotEmpty(),
            busy = s.busy,
            modifier = Modifier.fillMaxWidth().testTag("checkout-submit"),
            onClick = vm::checkout,
        )
        Text(
            "실제 결제 · 배송은 발생하지 않습니다.\n주문 전 수령 조건과 도착 예정일을 확인해 주세요.",
            fontSize = 8.sp,
            color = CampPalette.Muted,
            lineHeight = 14.sp,
        )
    }
}

@Composable
private fun DeliveryChoice(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    s: CampUiState,
    vm: CampViewModel,
    modifier: Modifier,
) {
    val selected = s.destination == value
    Surface(
        onClick = { vm.delivery(destination = value) },
        modifier = modifier.testTag("delivery-$value"),
        shape = RoundedCornerShape(7.dp),
        color = if (selected) CampPalette.Sage else CampPalette.Paper,
        border =
            androidx.compose.foundation.BorderStroke(
                1.dp,
                if (selected) Color(0xFF8BA573) else CampPalette.Line,
            ),
    ) {
        Row(
            Modifier.padding(vertical = 13.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = CampPalette.Muted)
            Text(
                "  $label",
                fontSize = 12.sp,
                color = if (selected) CampPalette.Forest else CampPalette.Muted,
            )
        }
    }
}

@Composable
private fun PriceRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 11.sp, color = CampPalette.Muted)
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun OrderConfirmation(order: Order, vm: CampViewModel, modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(35.dp)
            .testTag("order-confirmation"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            Modifier.size(73.dp).background(CampPalette.Sage, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(46.dp), tint = CampPalette.Muted)
        }
        CampPill("실제 결제 없음")
        Text("준비물이 여정을 기다리고 있어요.", fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
        Text("데모 주문이 저장되었습니다.", fontSize = 12.sp, color = CampPalette.Muted)
        CampSurface(Modifier.widthIn(max = 570.dp).fillMaxWidth()) {
            PriceRow("주문 번호", order.id)
            PriceRow("주문 금액", won(order.total))
            PriceRow("도착 예정", order.eta)
            PriceRow("받는 곳", if (order.destination == "home") "자택" else "캠핑장")
            Text(order.address, fontSize = 11.sp, color = CampPalette.Muted)
            PriceRow("받는 분", "${order.recipient} ${order.siteReference}")
        }
        Text("시연용 주문입니다. 실제 결제 승인이나 배송 접수는 발생하지 않았어요.", fontSize = 10.sp, color = CampPalette.Muted)
        CampPrimary(
            "내 캠핑으로 돌아가기",
            Icons.Outlined.ArrowForward,
            modifier = Modifier.testTag("order-back-trip"),
            onClick = vm::closeOrder,
        )
    }
}
