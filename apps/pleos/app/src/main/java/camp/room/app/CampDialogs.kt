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
import androidx.compose.ui.platform.LocalConfiguration
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
                                .size(CampDesign.IconTarget),
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            tr(camp.room.app.R.string.camp_close_campsite_details),
                            Modifier.size(21.dp),
                        )
                    }
                    Box(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                        CampPill(
                            if (site.sourceMode == "demo")
                                tr(camp.room.app.R.string.camp_demo_campsite)
                            else tr(camp.room.app.R.string.camp_external_data)
                        )
                    }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(25.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        site.region,
                        fontSize = 12.sp,
                        color = CampPalette.Muted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        site.name,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        site.description,
                        fontSize = 14.sp,
                        color = CampPalette.Muted,
                        lineHeight = 21.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        site.address,
                        fontSize = 12.sp,
                        color = CampPalette.Muted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        site.facilities.take(5).forEach { CampPill(facilityName(it)) }
                    }
                    HorizontalDivider(color = CampPalette.Line)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        DetailFact(
                            tr(camp.room.app.R.string.camp_sleeping_in_the_car),
                            when (site.carCamping) {
                                true -> tr(camp.room.app.R.string.camp_allowed)
                                false -> tr(camp.room.app.R.string.camp_not_allowed)
                                null -> tr(camp.room.app.R.string.camp_confirm_with_the_operator)
                            },
                        )
                        DetailFact(
                            tr(camp.room.app.R.string.camp_camping_power_212),
                            if ("electricity" in site.facilities)
                                tr(camp.room.app.R.string.camp_provided)
                            else tr(camp.room.app.R.string.camp_check_needed),
                        )
                        DetailFact(
                            tr(camp.room.app.R.string.camp_ev_charging),
                            when (site.evCharging) {
                                true -> tr(camp.room.app.R.string.camp_available)
                                false -> tr(camp.room.app.R.string.camp_unavailable_216)
                                null -> tr(camp.room.app.R.string.camp_check_needed)
                            },
                        )
                        DetailFact(
                            tr(camp.room.app.R.string.camp_check_in_check_out),
                            "${site.checkInTime} · ${site.checkOutTime}",
                        )
                    }
                    HorizontalDivider(color = CampPalette.Line)
                    Text(
                        tr(camp.room.app.R.string.camp_a_few_promises_for_a_comfortable_stay),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    site.rules.forEach {
                        Row {
                            Icon(
                                Icons.Outlined.Spa,
                                null,
                                Modifier.size(15.dp),
                                tint = CampPalette.Muted,
                            )
                            Text(
                                "  ${it}",
                                fontSize = 12.sp,
                                color = CampPalette.Muted,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
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
                                    tr(
                                        camp.room.app.R.string.camp_campsite_parcel_receiving,
                                        if (site.acceptsParcels)
                                            tr(camp.room.app.R.string.camp_accepted)
                                        else tr(camp.room.app.R.string.camp_not_accepted),
                                    ),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${site.receivingLocation} · ${site.receivingInstructions}",
                                    fontSize = 12.sp,
                                    color = CampPalette.Muted,
                                    modifier = Modifier.padding(top = 4.dp),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CampPill(site.availability.label)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(
                                site.availability.provider,
                                fontSize = 12.sp,
                                color = CampPalette.Muted,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                tr(
                                    camp.room.app.R.string.camp_checked_222,
                                    site.availability.coverage,
                                    site.availability.checkedAt.take(16).replace('T', ' '),
                                ),
                                fontSize = 12.sp,
                                color = CampPalette.Muted,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
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
                        Text(
                            won(site.price),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            tr(
                                camp.room.app.R.string.camp_guests_223,
                                displayDate(s.checkIn),
                                displayDate(s.checkOut),
                                guestCount(s.guests),
                            ),
                            fontSize = 12.sp,
                            color = CampPalette.Muted,
                            modifier = Modifier.padding(top = 3.dp),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    CampPrimary(
                        tr(camp.room.app.R.string.camp_save_to_my_trip),
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
        Text(
            label,
            fontSize = 12.sp,
            color = CampPalette.Muted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 7.dp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
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
                            fontSize = 12.sp,
                            letterSpacing = 1.4.sp,
                            color = CampPalette.Muted,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            tr(camp.room.app.R.string.camp_pack_a_little_rest_a_lot),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 7.dp),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            tr(
                                camp.room.app.R.string
                                    .camp_bring_the_little_things_this_journey_needs
                            ),
                            fontSize = 12.sp,
                            color = CampPalette.Muted,
                            modifier = Modifier.padding(top = 5.dp),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { vm.cart(false) }) {
                        Icon(Icons.Outlined.Close, tr(camp.room.app.R.string.camp_close_cart))
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
        Text(
            tr(camp.room.app.R.string.camp_packed_essentials, data.cart.count),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
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
                    tr(camp.room.app.R.string.camp_a_travel_bag_waiting_to_be_filled),
                    fontSize = 16.sp,
                    color = CampPalette.Muted,
                    modifier = Modifier.padding(top = 13.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tr(camp.room.app.R.string.camp_add_a_recommended_essential_below),
                    fontSize = 12.sp,
                    color = CampPalette.Muted,
                    modifier = Modifier.padding(top = 5.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        data.cart.items.forEach { item ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ProductArtwork(item.id, Modifier.size(73.dp))
                Column(Modifier.weight(1f).padding(start = 13.dp)) {
                    Text(
                        item.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        won(item.unitPrice),
                        fontSize = 12.sp,
                        color = CampPalette.Muted,
                        modifier = Modifier.padding(top = 4.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        OutlinedIconButton(
                            onClick = { vm.quantity(item.id, item.quantity - 1) },
                            enabled = !s.busy && item.quantity > 1,
                            modifier = Modifier.size(CampDesign.IconTarget),
                        ) {
                            Icon(
                                Icons.Outlined.Remove,
                                tr(camp.room.app.R.string.camp_decrease_quantity, item.name),
                                Modifier.size(15.dp),
                            )
                        }
                        Text(
                            item.quantity.toString(),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 13.dp),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        OutlinedIconButton(
                            onClick = { vm.quantity(item.id, item.quantity + 1) },
                            enabled = !s.busy,
                            modifier = Modifier.size(CampDesign.IconTarget),
                        ) {
                            Icon(
                                Icons.Outlined.Add,
                                tr(camp.room.app.R.string.camp_increase_quantity, item.name),
                                Modifier.size(15.dp),
                            )
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    IconButton(
                        onClick = { vm.quantity(item.id, 0) },
                        enabled = !s.busy,
                        modifier = Modifier.size(CampDesign.IconTarget),
                    ) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            tr(camp.room.app.R.string.camp_remove, item.name),
                            Modifier.size(18.dp),
                            tint = CampPalette.Muted,
                        )
                    }
                    Spacer(Modifier.height(23.dp))
                    Text(
                        won(item.lineTotal),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider(color = CampPalette.Line)
        }
        Spacer(Modifier.height(5.dp))
        Text(
            tr(camp.room.app.R.string.camp_a_few_more_things_to_consider),
            fontSize = 16.sp,
            color = CampPalette.Muted,
            fontWeight = FontWeight.Medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        data.products.forEach { product ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ProductArtwork(product.id, Modifier.size(51.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        product.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        won(product.price),
                        fontSize = 12.sp,
                        color = CampPalette.Muted,
                        modifier = Modifier.padding(top = 4.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledTonalIconButton(
                    onClick = { vm.addProduct(product.id) },
                    enabled = !s.busy && product.stock > 0,
                    modifier =
                        Modifier.size(CampDesign.IconTarget)
                            .testTag("cart-product-add-${product.id}"),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Icon(
                        Icons.Outlined.Add,
                        tr(camp.room.app.R.string.camp_add_another, product.name),
                        Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckoutPanel(s: CampUiState, vm: CampViewModel, modifier: Modifier) {
    Column(modifier.background(CampPalette.Canvas).verticalScroll(rememberScrollState())) {
        CheckoutContent(s, vm, Modifier.fillMaxWidth())
    }
}

@Composable
private fun CheckoutContent(s: CampUiState, vm: CampViewModel, modifier: Modifier) {
    val cart = s.data?.cart ?: return
    val site = s.tripSite
    val trip = s.data.trip
    Column(
        modifier.background(CampPalette.Canvas).padding(25.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            tr(camp.room.app.R.string.camp_where_would_you_like_to_receive_it),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            DeliveryChoice(
                tr(camp.room.app.R.string.camp_home),
                "home",
                Icons.Outlined.Home,
                s,
                vm,
                Modifier.weight(1f),
            )
            DeliveryChoice(
                tr(camp.room.app.R.string.camp_campsite),
                "campsite",
                Icons.Outlined.Forest,
                s,
                vm,
                Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = s.recipient,
            onValueChange = { vm.delivery(recipient = it) },
            label = {
                Text(
                    tr(camp.room.app.R.string.camp_recipient),
                    fontSize = 14.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("recipient-name"),
            textStyle = LocalTextStyle.current.copy(fontSize = 16.sp),
            shape = RoundedCornerShape(7.dp),
        )
        if (s.destination == "home")
            OutlinedTextField(
                value = s.address,
                onValueChange = { vm.delivery(address = it) },
                label = {
                    Text(
                        tr(camp.room.app.R.string.camp_home_delivery_address),
                        fontSize = 14.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.fillMaxWidth().testTag("delivery-address"),
                minLines = 2,
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
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
                            site?.name ?: tr(camp.room.app.R.string.camp_choose_a_campsite_first),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            site?.receivingLocation.orEmpty(),
                            fontSize = 12.sp,
                            color = CampPalette.Muted,
                            modifier = Modifier.padding(top = 4.dp),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (site?.acceptsParcels == true)
                                tr(camp.room.app.R.string.camp_parcels_accepted)
                            else
                                tr(camp.room.app.R.string.camp_parcels_not_accepted_or_unconfirmed),
                            fontSize = 12.sp,
                            color = CampPalette.Muted,
                            modifier = Modifier.padding(top = 5.dp),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            OutlinedTextField(
                value = s.siteReference,
                onValueChange = { vm.delivery(siteReference = it) },
                label = {
                    Text(
                        tr(camp.room.app.R.string.camp_pitch_reservation_reference),
                        fontSize = 14.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("site-reference"),
                textStyle = LocalTextStyle.current.copy(fontSize = 16.sp),
                shape = RoundedCornerShape(7.dp),
            )
            Text(
                tr(
                    camp.room.app.R.string.camp_stay,
                    displayDate(trip?.checkIn.orEmpty()),
                    displayDate(trip?.checkOut.orEmpty()),
                ),
                fontSize = 12.sp,
                color = CampPalette.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            tr(camp.room.app.R.string.camp_the_prefilled_name_and_address_are_fictional),
            fontSize = 12.sp,
            color = CampPalette.Muted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        CampSecondary(
            tr(camp.room.app.R.string.camp_check_delivery_availability),
            Icons.Outlined.Schedule,
            enabled = !s.busy && cart.items.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag("delivery-quote"),
            onClick = vm::quote,
        )
        s.quote?.let { quote ->
            Surface(
                color = if (quote.eligible) CampPalette.Sage else CampPalette.WarningSurface,
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
                            if (quote.eligible) tr(camp.room.app.R.string.camp_expected, quote.eta)
                            else
                                tr(
                                    camp.room.app.R.string
                                        .camp_this_delivery_doesn_t_fit_the_receiving
                                ),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (quote.reason.isNotBlank())
                            Text(
                                quote.reason,
                                fontSize = 12.sp,
                                color = CampPalette.Muted,
                                modifier = Modifier.padding(top = 5.dp),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        quote.conditions.forEach {
                            Text(
                                it,
                                fontSize = 12.sp,
                                color = CampPalette.Muted,
                                modifier = Modifier.padding(top = 4.dp),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        if (s.error.isNotBlank()) CampError(s.error, vm::clearError)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PriceRow(tr(camp.room.app.R.string.camp_items_subtotal), won(cart.subtotal))
            PriceRow(
                tr(camp.room.app.R.string.camp_delivery),
                won(s.quote?.shipping ?: cart.shipping),
            )
            HorizontalDivider(
                color = CampPalette.Line,
                modifier = Modifier.padding(vertical = 5.dp),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr(camp.room.app.R.string.camp_order_total),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    won(s.quote?.total ?: cart.total),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        CampPrimary(
            tr(camp.room.app.R.string.camp_demo_payment, won(s.quote?.total ?: cart.total)),
            Icons.Outlined.ShoppingBag,
            enabled = s.quote?.eligible == true && cart.items.isNotEmpty(),
            busy = s.busy,
            modifier = Modifier.fillMaxWidth().testTag("checkout-submit"),
            onClick = vm::checkout,
        )
        Text(
            tr(camp.room.app.R.string.camp_no_real_payment_or_delivery_occurs_ncheck),
            fontSize = 12.sp,
            color = CampPalette.Muted,
            lineHeight = 18.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
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
        modifier = modifier.testTag("delivery-${value}"),
        shape = RoundedCornerShape(7.dp),
        color = if (selected) CampPalette.Sage else CampPalette.Paper,
        border =
            androidx.compose.foundation.BorderStroke(
                1.dp,
                if (selected) CampPalette.Outline else CampPalette.Line,
            ),
    ) {
        Row(
            Modifier.padding(vertical = 13.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = CampPalette.Muted)
            Text(
                "  ${label}",
                fontSize = 14.sp,
                color = if (selected) CampPalette.Forest else CampPalette.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PriceRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            fontSize = 12.sp,
            color = CampPalette.Muted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
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
        CampPill(tr(camp.room.app.R.string.camp_no_real_payment))
        Text(
            tr(camp.room.app.R.string.camp_your_essentials_are_waiting_for_the_journey),
            fontSize = 25.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            tr(camp.room.app.R.string.camp_your_demo_order_has_been_saved),
            fontSize = 14.sp,
            color = CampPalette.Muted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        CampSurface(Modifier.widthIn(max = 570.dp).fillMaxWidth()) {
            PriceRow(tr(camp.room.app.R.string.camp_order_number), order.id)
            PriceRow(tr(camp.room.app.R.string.camp_order_amount), won(order.total))
            PriceRow(tr(camp.room.app.R.string.camp_estimated_arrival), order.eta)
            PriceRow(
                tr(camp.room.app.R.string.camp_receiving_at),
                if (order.destination == "home") tr(camp.room.app.R.string.camp_home)
                else tr(camp.room.app.R.string.camp_campsite),
            )
            Text(
                order.address,
                fontSize = 12.sp,
                color = CampPalette.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            PriceRow(
                tr(camp.room.app.R.string.camp_recipient),
                "${order.recipient} ${order.siteReference}",
            )
        }
        Text(
            tr(camp.room.app.R.string.camp_this_is_a_demo_order_no_real),
            fontSize = 12.sp,
            color = CampPalette.Muted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        CampPrimary(
            tr(camp.room.app.R.string.camp_back_to_my_trip),
            Icons.Outlined.ArrowForward,
            modifier = Modifier.testTag("order-back-trip"),
            onClick = vm::closeOrder,
        )
    }
}
