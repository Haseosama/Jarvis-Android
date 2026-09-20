package com.jarvis.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Color as AndroidColor

/**
 * Live theming — Android port of the HUD's hue-wheel recolouring. A single hue
 * (0-360, stored in [com.jarvis.android.memory.ConfigStore.themeHue]) drives the
 * whole scheme: accent, tinted dark surfaces and the background gradient.
 */
internal fun colorFromHue(hue: Float, saturation: Float, value: Float): Color {
    val argb = AndroidColor.HSVToColor(floatArrayOf(hue.mod(360f), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f)))
    return Color(argb)
}

/** The soft vertical gradient behind every screen: a tinted dark blue at the top fading to near black. */
fun jarvisBackdrop(hue: Float): Brush = Brush.verticalGradient(
    listOf(colorFromHue(hue, 0.55f, 0.16f), colorFromHue(hue, 0.45f, 0.08f), colorFromHue(hue, 0.35f, 0.04f)),
)

private val JarvisShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

private val JarvisTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.15.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
)

@Composable
fun JarvisTheme(hue: Float, content: @Composable () -> Unit) {
    val accent = colorFromHue(hue, 0.70f, 1.0f)
    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF041014),
        primaryContainer = colorFromHue(hue, 0.65f, 0.32f),
        onPrimaryContainer = colorFromHue(hue, 0.25f, 1.0f),
        secondary = colorFromHue(hue, 0.50f, 0.78f),
        onSecondary = Color(0xFF041014),
        secondaryContainer = colorFromHue(hue, 0.45f, 0.24f),
        onSecondaryContainer = colorFromHue(hue, 0.15f, 0.95f),
        tertiary = colorFromHue(hue + 45f, 0.60f, 1.0f),
        background = colorFromHue(hue, 0.35f, 0.06f),
        onBackground = Color(0xFFE6F1F5),
        surface = colorFromHue(hue, 0.35f, 0.10f),
        onSurface = Color(0xFFE6F1F5),
        surfaceVariant = colorFromHue(hue, 0.32f, 0.17f),
        onSurfaceVariant = colorFromHue(hue, 0.12f, 0.80f),
        surfaceContainer = colorFromHue(hue, 0.34f, 0.13f),
        surfaceContainerHigh = colorFromHue(hue, 0.33f, 0.16f),
        outline = colorFromHue(hue, 0.25f, 0.45f),
        outlineVariant = colorFromHue(hue, 0.25f, 0.28f),
        error = Color(0xFFFF7A7A),
    )
    MaterialTheme(colorScheme = scheme, shapes = JarvisShapes, typography = JarvisTypography, content = content)
}
