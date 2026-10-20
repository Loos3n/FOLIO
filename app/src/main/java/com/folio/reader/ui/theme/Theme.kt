@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.folio.reader.ui.theme

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import android.content.res.AssetManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// A warm "paper and ink" palette with a terracotta accent, so the app has one
// consistent, designed look on every phone rather than whatever the wallpaper suggests.
private val LightColors = lightColorScheme(
    primary = Color(0xFF9A4A2E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF4F6354),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E8D8),
    onSecondaryContainer = Color(0xFF0D1F13),
    tertiary = Color(0xFF2F5D8A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD3E4FF),
    onTertiaryContainer = Color(0xFF001C38),
    background = Color(0xFFFAF7F2),
    onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFAF7F2),
    onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFEDE5DA),
    onSurfaceVariant = Color(0xFF5E574E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F1EA),
    surfaceContainer = Color(0xFFEFEAE2),
    surfaceContainerHigh = Color(0xFFE9E3DA),
    surfaceContainerHighest = Color(0xFFE3DCD2),
    outline = Color(0xFF8A8278),
    outlineVariant = Color(0xFFD8D0C4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB59C),
    onPrimary = Color(0xFF5C1A05),
    primaryContainer = Color(0xFF7A3019),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFB6CCB9),
    onSecondary = Color(0xFF213527),
    secondaryContainer = Color(0xFF374B3C),
    onSecondaryContainer = Color(0xFFD2E8D5),
    tertiary = Color(0xFFA1C9FF),
    onTertiary = Color(0xFF00315B),
    tertiaryContainer = Color(0xFF164773),
    onTertiaryContainer = Color(0xFFD3E4FF),
    background = Color(0xFF141210),
    onBackground = Color(0xFFECE6DE),
    surface = Color(0xFF141210),
    onSurface = Color(0xFFECE6DE),
    surfaceVariant = Color(0xFF3A3530),
    onSurfaceVariant = Color(0xFFCFC6BA),
    surfaceContainerLowest = Color(0xFF0F0D0B),
    surfaceContainerLow = Color(0xFF1C1A17),
    surfaceContainer = Color(0xFF211E1B),
    surfaceContainerHigh = Color(0xFF2B2825),
    surfaceContainerHighest = Color(0xFF36332F),
    outline = Color(0xFF988F84),
    outlineVariant = Color(0xFF4B463F),
)

private val SepiaColors = LightColors.copy(
    primary = Color(0xFF8A5A1C),
    primaryContainer = Color(0xFFEBD6AE),
    onPrimaryContainer = Color(0xFF2E1F0A),
    background = Color(0xFFF3E9D2),
    surface = Color(0xFFF3E9D2),
    onSurface = Color(0xFF3F3122),
    onSurfaceVariant = Color(0xFF6A5843),
    surfaceContainerLow = Color(0xFFEFE3C8),
    surfaceContainer = Color(0xFFEADDC0),
    surfaceContainerHigh = Color(0xFFE4D6B7),
    surfaceContainerHighest = Color(0xFFDDCFAE),
    outlineVariant = Color(0xFFD3C29F),
    secondaryContainer = Color(0xFFE2D2AF),
)

private val NightColors = DarkColors.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLow = Color(0xFF0C0C0C),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1C1C1C),
    surfaceContainerHighest = Color(0xFF262626),
)

/** Display face for headings (Fraunces) and a clean UI face (Inter), both bundled. */
object FolioFonts {
    @Volatile private var cached: Pair<FontFamily, FontFamily>? = null

    fun get(assets: AssetManager): Pair<FontFamily, FontFamily> = cached ?: run {
        fun family(path: String, vararg weights: FontWeight) =
            FontFamily(weights.map { Font(path, assets, it) })
        val display = family("fonts/fraunces.ttf", FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
        val text = family("fonts/inter.ttf", FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
        (display to text).also { cached = it }
    }

    fun literata(assets: AssetManager) = FontFamily(Font("fonts/literata.ttf", assets, FontWeight.Normal))
    fun inter(assets: AssetManager) = get(assets).second
    fun atkinson(assets: AssetManager) = FontFamily(Font("fonts/atkinson.ttf", assets, FontWeight.Normal))
}

private fun folioTypography(display: FontFamily, text: FontFamily): Typography {
    val b = Typography()
    return Typography(
        displayLarge = b.displayLarge.copy(fontFamily = display, fontWeight = FontWeight.SemiBold),
        displayMedium = b.displayMedium.copy(fontFamily = display, fontWeight = FontWeight.SemiBold),
        displaySmall = b.displaySmall.copy(fontFamily = display, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineLarge = b.headlineLarge.copy(fontFamily = display, fontWeight = FontWeight.SemiBold),
        headlineMedium = b.headlineMedium.copy(fontFamily = display, fontWeight = FontWeight.SemiBold),
        headlineSmall = b.headlineSmall.copy(fontFamily = display, fontWeight = FontWeight.SemiBold),
        titleLarge = b.titleLarge.copy(fontFamily = display, fontWeight = FontWeight.SemiBold),
        titleMedium = b.titleMedium.copy(fontFamily = text, fontWeight = FontWeight.SemiBold),
        titleSmall = b.titleSmall.copy(fontFamily = text, fontWeight = FontWeight.SemiBold),
        bodyLarge = b.bodyLarge.copy(fontFamily = text),
        bodyMedium = b.bodyMedium.copy(fontFamily = text),
        bodySmall = b.bodySmall.copy(fontFamily = text),
        labelLarge = b.labelLarge.copy(fontFamily = text, fontWeight = FontWeight.SemiBold),
        labelMedium = b.labelMedium.copy(fontFamily = text, fontWeight = FontWeight.Medium),
        labelSmall = b.labelSmall.copy(fontFamily = text, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    )
}

enum class ChromeStyle { System, Light, Dark, Sepia, Night }

@Composable
fun FolioTheme(
    style: ChromeStyle = ChromeStyle.System,
    content: @Composable () -> Unit,
) {
    val assets = LocalContext.current.assets
    val systemDark = isSystemInDarkTheme()
    val scheme: ColorScheme = when (style) {
        ChromeStyle.Sepia -> SepiaColors
        ChromeStyle.Dark -> DarkColors
        ChromeStyle.Night -> NightColors
        ChromeStyle.Light -> LightColors
        ChromeStyle.System -> if (systemDark) DarkColors else LightColors
    }
    val typography = remember(assets) {
        val (display, text) = FolioFonts.get(assets)
        folioTypography(display, text)
    }
    MaterialTheme(colorScheme = scheme, typography = typography) {
        // Text and icons drawn straight on a screen's background (not inside a Surface) would
        // otherwise default to black, which disappears in dark mode.
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}
