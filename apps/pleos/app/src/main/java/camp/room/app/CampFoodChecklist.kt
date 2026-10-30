package camp.room.app

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private const val FOOD_PREP_PREFERENCES = "camp_room_food_prep_v1"

private data class FoodPrepPack(
    val id: String,
    @StringRes val title: Int,
    @StringRes val serves: Int,
    @StringRes val contents: Int,
    @StringRes val imageDescription: Int,
    @DrawableRes val image: Int,
)

// Suggested meal portions are independent of merchandise, prices, stock, and orders.
private val FoodPrepPacks =
    listOf(
        FoodPrepPack(
            "pork_belly",
            R.string.camp_food_prep_pork_title,
            R.string.camp_food_prep_serves_2_3,
            R.string.camp_food_prep_pork_contents,
            R.string.camp_food_prep_pork_image,
            R.drawable.food_prep_pork_belly_example,
        ),
        FoodPrepPack(
            "beef_grill",
            R.string.camp_food_prep_beef_title,
            R.string.camp_food_prep_serves_2_3,
            R.string.camp_food_prep_beef_contents,
            R.string.camp_food_prep_beef_image,
            R.drawable.food_prep_beef_grill_example,
        ),
        FoodPrepPack(
            "marinated_ribs",
            R.string.camp_food_prep_ribs_title,
            R.string.camp_food_prep_serves_3_4,
            R.string.camp_food_prep_ribs_contents,
            R.string.camp_food_prep_ribs_image,
            R.drawable.food_prep_marinated_ribs_example,
        ),
        FoodPrepPack(
            "bbq_assortment",
            R.string.camp_food_prep_mixed_title,
            R.string.camp_food_prep_serves_4,
            R.string.camp_food_prep_mixed_contents,
            R.string.camp_food_prep_mixed_image,
            R.drawable.food_prep_bbq_assortment_example,
        ),
    )

// Length prefixes prevent a delimiter in an opaque trip key from sharing a preference.
private fun foodPrepPreferenceKey(
    tripKey: String,
    packId: String,
): String = "${tripKey.length}:$tripKey|$packId"

internal fun foodPrepPreferences(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(FOOD_PREP_PREFERENCES, Context.MODE_PRIVATE)

internal fun selectedFoodPreparation(
    preferences: SharedPreferences,
    tripKey: String,
): Set<String> = FoodPrepPacks.filter { preferences.getBoolean(foodPrepPreferenceKey(tripKey, it.id), false) }.map { it.id }.toSet()

/** Reuse the existing intention checklist; importing never creates an order or packed confirmation. */
internal fun importFoodPreparation(
    preferences: SharedPreferences,
    tripKey: String,
    ids: Set<String>,
) {
    preferences
        .edit()
        .apply {
            FoodPrepPacks.forEach { putBoolean(foodPrepPreferenceKey(tripKey, it.id), it.id in ids) }
        }.apply()
}

/** A local packing checklist. Checking a set never creates a cart item or an order. */
@Composable
fun CampFoodChecklist(
    tripKey: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val preferences =
        remember(context) {
            foodPrepPreferences(context)
        }
    val checked =
        remember(preferences, tripKey) {
            mutableStateMapOf<String, Boolean>().apply {
                FoodPrepPacks.forEach { pack ->
                    this[pack.id] = preferences.getBoolean(foodPrepPreferenceKey(tripKey, pack.id), false)
                }
            }
        }
    DisposableEffect(preferences, tripKey) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { updated, key ->
                FoodPrepPacks.forEach { pack ->
                    val preferenceKey = foodPrepPreferenceKey(tripKey, pack.id)
                    if (key == null || key == preferenceKey) {
                        checked[pack.id] = updated.getBoolean(preferenceKey, false)
                    }
                }
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        // Reconcile changes made between initial composition and listener registration.
        FoodPrepPacks.forEach { pack ->
            checked[pack.id] = preferences.getBoolean(foodPrepPreferenceKey(tripKey, pack.id), false)
        }
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    Column(
        modifier.fillMaxWidth().testTag("food-prep-checklist"),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                tr(R.string.camp_food_prep_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = CampPalette.Ink,
            )
            Text(
                tr(R.string.camp_food_prep_disclosure),
                fontSize = 22.sp,
                color = CampPalette.Muted,
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth > 760.dp * LocalDensity.current.fontScale) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                FoodPrepPacks.chunked(columns).forEach { packs ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        packs.forEach { pack ->
                            FoodPrepCard(
                                pack = pack,
                                checked = checked[pack.id] == true,
                                onCheckedChange = { value ->
                                    checked[pack.id] = value
                                    preferences
                                        .edit()
                                        .putBoolean(foodPrepPreferenceKey(tripKey, pack.id), value)
                                        .apply()
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(columns - packs.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FoodPrepCard(
    pack: FoodPrepPack,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val packTitle = tr(pack.title)
    Surface(
        modifier = modifier.testTag("food-prep-card-${pack.id}"),
        color = CampPalette.Paper,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (checked) CampPalette.Forest else CampPalette.Line),
    ) {
        Column {
            FoodPrepImage(pack, Modifier.fillMaxWidth().height(190.dp))
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    packTitle,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CampPalette.Ink,
                )
                Text(tr(pack.serves), fontSize = 22.sp, color = CampPalette.Forest)
                Text(tr(pack.contents), fontSize = 22.sp, color = CampPalette.Muted)
                Surface(
                    color = if (checked) CampPalette.Selected else CampPalette.Sage,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .semantics { contentDescription = packTitle }
                                .toggleable(
                                    value = checked,
                                    role = Role.Checkbox,
                                    onValueChange = onCheckedChange,
                                ).testTag("food-prep-toggle-${pack.id}")
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            colors =
                                CheckboxDefaults.colors(
                                    checkedColor = CampPalette.Forest,
                                    checkmarkColor = CampPalette.OnForest,
                                    uncheckedColor = CampPalette.Muted,
                                ),
                        )
                        Text(
                            tr(if (checked) R.string.camp_food_prep_selected else R.string.camp_food_prep_select),
                            modifier = Modifier.weight(1f),
                            fontSize = 22.sp,
                            color = CampPalette.Forest,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FoodPrepImage(
    pack: FoodPrepPack,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    var imageLoaded by remember(pack.image, configuration) { mutableStateOf(false) }
    var imageFailed by remember(pack.image, configuration) { mutableStateOf(false) }
    Surface(color = CampPalette.Sage, modifier = modifier) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (!imageFailed) {
                AsyncImage(
                    model = pack.image,
                    contentDescription = tr(pack.imageDescription),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().testTag("food-prep-image-${pack.id}"),
                    onSuccess = { imageLoaded = true },
                    onError = { imageFailed = true },
                )
            }
            if (!imageLoaded || imageFailed) {
                Text(
                    tr(if (imageFailed) R.string.camp_food_prep_image_unavailable else R.string.camp_food_prep_image_loading),
                    modifier = Modifier.padding(18.dp).testTag("food-prep-image-status-${pack.id}"),
                    fontSize = 22.sp,
                    textAlign = TextAlign.Center,
                    color = CampPalette.Muted,
                )
            }
        }
    }
}
