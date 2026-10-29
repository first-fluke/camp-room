package camp.room.app.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

internal data class CampIconPath(
    val data: String,
    val alpha: Float = 1f,
    val evenOdd: Boolean = false,
)

/** Small vendored vector set; cached lazy properties parse each verified path once. */
internal fun campVector(
    name: String,
    width: Float,
    height: Float,
    paths: List<CampIconPath>,
): ImageVector =
    ImageVector
        .Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = width, viewportHeight = height)
        .apply {
            paths.forEach { path ->
                addPath(
                    pathData = PathParser().parsePathString(path.data).toNodes(),
                    fill = SolidColor(Color.Black),
                    fillAlpha = path.alpha,
                    pathFillType = if (path.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                )
            }
        }.build()
