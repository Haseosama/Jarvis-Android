package com.jarvis.android.avatar

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.jarvis.android.core.JarvisState

/**
 * The holographic head. It redraws itself about 30 times a second while it is on screen, and stops when it leaves.
 * [outputLevel] is the 0..1 loudness of the assistant's voice, [onTap] is the same tap as the reactor's.
 */
@Composable
internal fun AvatarView(controller: AvatarController, state: JarvisState, outputLevel: Float, modifier: Modifier = Modifier) {
    val avatar = controller.avatar
    val renderer = remember(controller) { AvatarRenderer(controller.mesh) }
    val currentState by rememberUpdatedState(state)
    val currentLevel by rememberUpdatedState(outputLevel)
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(controller) {
        var last = SystemClock.elapsedRealtimeNanos()
        var lastDraw = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { nanos ->
                // A sleeping face only breathes and blinks: half the frame rate is plenty and saves battery.
                val sleeping = currentState == JarvisState.ASLEEP || currentState == JarvisState.ERROR
                if (nanos - lastDraw < (if (sleeping) SLEEP_FRAME_NS else FRAME_NS)) return@withFrameNanos
                lastDraw = nanos
                val now = SystemClock.elapsedRealtimeNanos()
                val dt = (now - last) / 1e9f
                last = now
                val sample = controller.timeline.sample(now)
                avatar.step(
                    dt, currentLevel, sample.speaking, controller.debugMood ?: moodFor(currentState),
                    if (sample.speaking) sample.frames else null,
                )
                frame = nanos
            }
        }
    }

    val scheme = MaterialTheme.colorScheme
    val primary = scheme.primary.toArgb()
    val accent = scheme.tertiary.toArgb()
    val bg = scheme.background.toArgb()
    val stroke = with(LocalDensity.current) { 1.1.dp.toPx() }

    Canvas(modifier.fillMaxWidth().aspectRatio(1f)) {
        @Suppress("UNUSED_VARIABLE") val tick = frame // reading it makes the canvas redraw with every animation step
        // The realistic bust needs room below the chin for the neck and shoulders.
        val realistic = controller.look.style != AvatarStyle.HOLOGRAPHIC
        val r = size.minDimension * (if (realistic) 0.305f else 0.36f) // head half-height
        val cyFactor = if (realistic) 0.385f else 0.44f
        renderer.scanY = avatar.scan
        renderer.showHair = controller.hair
        renderer.look = controller.look
        renderer.draw(this, avatar, size.width / 2f, size.height * cyFactor, r, primary, accent, bg, stroke)
    }
}

private const val FRAME_NS = 30_000_000L
private const val SLEEP_FRAME_NS = 66_000_000L
