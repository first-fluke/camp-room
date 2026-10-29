package camp.room.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Keeps provider photographs distinct from the fallback illustration. */
@Composable
fun CampCampsiteImage(
    site: Campsite,
    modifier: Modifier = Modifier,
) {
    val url = site.imageUrl.trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }
    var status by remember(url) { mutableStateOf(if (url == null) "missing" else "loading") }
    Box(modifier.testTag("campsite-image-${site.id}-$status")) {
        if (status != "loaded") CampLandscape(site.id, Modifier.fillMaxSize())
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = site.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onLoading = { status = "loading" },
                onSuccess = { status = "loaded" },
                onError = { status = "failed" },
            )
        }
        if (status != "loaded") {
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                color = CampPalette.Paper.copy(alpha = .95f),
                contentColor = CampPalette.Ink,
                shape = MaterialTheme.shapes.small,
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(
                        tr(
                            when (status) {
                                "loading" -> R.string.camp_photo_loading
                                "failed" -> R.string.camp_photo_failed
                                else -> R.string.camp_photo_missing
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(tr(R.string.camp_photo_illustration), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
