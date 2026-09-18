package com.markliv.android

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun MarkAvatar(isSpeaking: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "avatar")
    val pulse by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isSpeaking) 300 else 1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Canvas(modifier = modifier.size(100.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 3 * pulse

        // Cercle extérieur lumineux (cyan futuriste style Mark LIV)
        drawCircle(
            color = Color(0xFF00E5FF).copy(alpha = 0.6f),
            radius = radius,
            center = center,
            style = Stroke(width = 4.dp.toPx())
        )

        // Noyau central
        drawCircle(
            color = Color(0xFF00B0FF),
            radius = radius * 0.5f,
            center = center
        )
    }
}
