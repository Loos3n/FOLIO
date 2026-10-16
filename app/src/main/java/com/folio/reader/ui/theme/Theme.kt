package com.folio.reader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Fallback palette for devices without Material You (Android 11 and lower).
private val LightColors = lightColorScheme(
    primary = Color(0xFF3D5A80),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E3FF),
    onPrimaryContainer = Color(0xFF001B3C),
    secondary = Color(0xFF8A5A44),
    secondaryContainer = Color(0xFFFFDBCC),
    tertiary = Color(0xFFEE6C4D),
    background = Color(0xFFFBF7F2),
    surface = Color(0xFFFBF7F2),
    surfaceContainer = Color(0xFFF1ECE6),
    surfaceContainerHigh = Color(0xFFEBE6E0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C8FF),
    onPrimary = Color(0xFF05305F),
    primaryContainer = Color(0xFF244777),
    onPrimaryContainer = Color(0xFFD5E3FF),
    secondary = Color(0xFFFFB597),
    secondaryContainer = Color(0xFF6E3F2B),
    tertiary = Color(0xFFFFB4A1),
    background = Color(0xFF141218),
    surface = Color(0xFF141218),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF2B2930),
)

private val SepiaColors = lightColorScheme(
    primary = Color(0xFF8A5A1C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEBD9B4),
    onPrimaryContainer = Color(0xFF2E1F0A),
    secondaryContainer = Color(0xFFE6D6B8),
    background = Color(0xFFF6EEDC),
    surface = Color(0xFFF6EEDC),
    onSurface = Color(0xFF433422),
    surfaceContainer = Color(0xFFEFE4CC),
    surfaceContainerLow = Color(0xFFF2E8D3),
    surfaceContainerHigh = Color(0xFFE9DDC2),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    )
}

enum class ChromeStyle { System, Light, Dark, Sepia }

@Composable
fun FolioTheme(
    style: ChromeStyle = ChromeStyle.System,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scheme: ColorScheme = when (style) {
        ChromeStyle.Sepia -> SepiaColors
        ChromeStyle.Dark -> if (dynamic) dynamicDarkColorScheme(context) else DarkColors
        ChromeStyle.Light -> if (dynamic) dynamicLightColorScheme(context) else LightColors
        ChromeStyle.System -> when {
            dynamic && systemDark -> dynamicDarkColorScheme(context)
            dynamic -> dynamicLightColorScheme(context)
            systemDark -> DarkColors
            else -> LightColors
        }
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
