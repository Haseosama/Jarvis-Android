package com.jarvis.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import android.graphics.Color as AndroidColor

/**
 * Live theming — Android port of the HUD's hue-wheel recolouring. A single hue
 * (0-360, stored in [com.jarvis.android.memory.ConfigStore.themeHue]) drives the
 * whole scheme, same idea as the desktop's "recolour the entire HUD" feature.
 */
private fun colorFromHue(hue: Float, saturation: Float, value: Float): Color {
    val argb = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value))
    return Color(argb)
}

@Composable
fun JarvisTheme(hue: Float, content: @Composable () -> Unit) {
    val accent = colorFromHue(hue, 0.75f, 1.0f)
    val accentDim = colorFromHue(hue, 0.6f, 0.55f)
    val scheme = darkColorScheme(
        primary = accent,
        secondary = accentDim,
        tertiary = accent,
        background = Color(0xFF0B0F14),
        surface = Color(0xFF11161D),
        onPrimary = Color.Black,
        onBackground = Color(0xFFE6F1F5),
        onSurface = Color(0xFFE6F1F5),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
