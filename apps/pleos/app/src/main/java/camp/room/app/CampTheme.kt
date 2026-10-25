package camp.room.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Camp Room's Compose interpretation of the official Pleos design guidance, not an SDK UI kit. */
object CampDesign {
    val PrimaryHeight = 64.dp
    val SecondaryHeight = 56.dp
    val IconTarget = 48.dp
    val Font = FontFamily(Font(R.font.asta_sans))
    // Locale-aware phrase boundaries complement sufficient width and responsive layout.
    // Android's line breaker may still use emergency breaks for overlong tokens.
    val TextLineBreak = LineBreak(
        strategy = LineBreak.Strategy.HighQuality,
        strictness = LineBreak.Strictness.Strict,
        wordBreak = LineBreak.WordBreak.Phrase,
    )
}

data class CampColors(
    val canvas: Color,
    val paper: Color,
    val forest: Color,
    val onForest: Color,
    val ink: Color,
    val muted: Color,
    val sage: Color,
    val line: Color,
    val outline: Color,
    val warm: Color,
    val danger: Color,
    val errorSurface: Color,
    val warningSurface: Color,
    val selected: Color,
)

private val DayColors =
    CampColors(
        canvas = Color(0xFFF7F8F2),
        paper = Color(0xFFFFFEF9),
        forest = Color(0xFF254F40),
        onForest = Color(0xFFFFFEF9),
        ink = Color(0xFF293F32),
        muted = Color(0xFF53634D),
        sage = Color(0xFFE7EDDC),
        line = Color(0xFFDFE5D4),
        outline = Color(0xFF687D59),
        warm = Color(0xFF785A25),
        danger = Color(0xFF953C2B),
        errorSurface = Color(0xFFFAEDE4),
        warningSurface = Color(0xFFFAEEE0),
        selected = Color(0xFFDCE8CD),
    )
private val NightColors =
    CampColors(
        canvas = Color(0xFF101B17),
        paper = Color(0xFF182820),
        forest = Color(0xFFBCD7AE),
        onForest = Color(0xFF182B1C),
        ink = Color(0xFFE5EDDF),
        muted = Color(0xFFB1C1A6),
        sage = Color(0xFF243D2F),
        line = Color(0xFF3A5140),
        outline = Color(0xFF8FA480),
        warm = Color(0xFFE4C58D),
        danger = Color(0xFFFFB6A2),
        errorSurface = Color(0xFF46251F),
        warningSurface = Color(0xFF3D301D),
        selected = Color(0xFF35503B),
    )
private val LocalCampColors = staticCompositionLocalOf { DayColors }

/** Semantic names keep each existing screen on the same adaptive color contract. */
object CampPalette {
    val Canvas: Color
        @Composable get() = LocalCampColors.current.canvas

    val Paper: Color
        @Composable get() = LocalCampColors.current.paper

    val Forest: Color
        @Composable get() = LocalCampColors.current.forest

    val OnForest: Color
        @Composable get() = LocalCampColors.current.onForest

    val Ink: Color
        @Composable get() = LocalCampColors.current.ink

    val Muted: Color
        @Composable get() = LocalCampColors.current.muted

    val Sage: Color
        @Composable get() = LocalCampColors.current.sage

    val Line: Color
        @Composable get() = LocalCampColors.current.line

    val Outline: Color
        @Composable get() = LocalCampColors.current.outline

    val Warm: Color
        @Composable get() = LocalCampColors.current.warm

    val Danger: Color
        @Composable get() = LocalCampColors.current.danger

    val ErrorSurface: Color
        @Composable get() = LocalCampColors.current.errorSurface

    val WarningSurface: Color
        @Composable get() = LocalCampColors.current.warningSurface

    val Selected: Color
        @Composable get() = LocalCampColors.current.selected

    // Sidebar remains a dark brand landmark in both display modes.
    val Deep = Color(0xFF193F33)
    val OnSidebar = Color(0xFFEEF5E6)
}

@Composable
fun CampTheme(content: @Composable () -> Unit) {
    val night = isSystemInDarkTheme()
    val colors = if (night) NightColors else DayColors
    val scheme = if (night) darkColorScheme() else lightColorScheme()
    val textLocales = LocaleList(LocalConfiguration.current.locales.toLanguageTags())
    val type =
        Typography(
            headlineLarge =
                TextStyle(localeList = textLocales, lineBreak = CampDesign.TextLineBreak, hyphens = Hyphens.None, fontFamily = CampDesign.Font, fontSize = 32.sp, lineHeight = 40.sp),
            headlineMedium =
                TextStyle(localeList = textLocales, lineBreak = CampDesign.TextLineBreak, hyphens = Hyphens.None, fontFamily = CampDesign.Font, fontSize = 28.sp, lineHeight = 36.sp),
            headlineSmall =
                TextStyle(localeList = textLocales, lineBreak = CampDesign.TextLineBreak, hyphens = Hyphens.None, fontFamily = CampDesign.Font, fontSize = 24.sp, lineHeight = 32.sp),
            titleLarge =
                TextStyle(localeList = textLocales, lineBreak = CampDesign.TextLineBreak, hyphens = Hyphens.None,
                    fontFamily = CampDesign.Font,
                    fontSize = 24.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            titleMedium =
                TextStyle(localeList = textLocales, lineBreak = CampDesign.TextLineBreak, hyphens = Hyphens.None,
                    fontFamily = CampDesign.Font,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Medium,
                ),
            titleSmall =
                TextStyle(localeList = textLocales, lineBreak = CampDesign.TextLineBreak, hyphens = Hyphens.None,
                    fontFamily = CampDesign.Font,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Medium,
                ),
            bodyLarge =
                TextStyle(localeList = textLocales, lineBreak = CampDesign.TextLineBreak, hyphens = Hyphens.None, fontFamily = CampDesign.Font, fontSize = 22.sp, lineHeight = 28.sp),
            bodyMedium =
                TextStyle(localeList = textLocales, lineBreak = CampDesign.TextLineBreak, hyphens = Hyphens.None, fontFamily = CampDesign.Font, fontSize = 22.sp, lineHeight = 28.sp),
            bodySmall =
                TextStyle(localeList = textLocales, lineBreak = CampDesign.TextLineBreak, hyphens = Hyphens.None, fontFamily = CampDesign.Font, fontSize = 22.sp, lineHeight = 28.sp),
            labelLarge =
                TextStyle(localeList = textLocales, lineBreak = CampDesign.TextLineBreak, hyphens = Hyphens.None,
                    fontFamily = CampDesign.Font,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Medium,
                ),
            labelMedium =
                TextStyle(localeList = textLocales, lineBreak = CampDesign.TextLineBreak, hyphens = Hyphens.None,
                    fontFamily = CampDesign.Font,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Medium,
                ),
            labelSmall =
                TextStyle(localeList = textLocales, lineBreak = CampDesign.TextLineBreak, hyphens = Hyphens.None, fontFamily = CampDesign.Font, fontSize = 22.sp, lineHeight = 28.sp),
        )
    CompositionLocalProvider(LocalCampColors provides colors) {
        MaterialTheme(
            colorScheme =
                scheme.copy(
                    primary = colors.forest,
                    onPrimary = colors.onForest,
                    primaryContainer = colors.sage,
                    onPrimaryContainer = colors.ink,
                    secondary = colors.muted,
                    onSecondary = colors.paper,
                    secondaryContainer = colors.sage,
                    onSecondaryContainer = colors.ink,
                    background = colors.canvas,
                    onBackground = colors.ink,
                    surface = colors.paper,
                    onSurface = colors.ink,
                    surfaceVariant = colors.sage,
                    onSurfaceVariant = colors.muted,
                    surfaceContainer = colors.paper,
                    surfaceContainerLow = colors.canvas,
                    surfaceContainerHigh = colors.sage,
                    surfaceContainerHighest = colors.selected,
                    outline = colors.outline,
                    outlineVariant = colors.line,
                    error = colors.danger,
                    errorContainer = colors.errorSurface,
                    onErrorContainer = colors.danger,
                ),
            typography = type,
            content = content,
        )
    }
}
