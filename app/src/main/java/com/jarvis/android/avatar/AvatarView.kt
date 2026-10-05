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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.jarvis.android.core.JarvisState

/**
 * The holographic head. It redraws itself about 30 times a second while it is on screen, and stops when it leaves.
 * [outputLevel] is the 0..1 loudness of the assistant's voice, [onTap] is the same tap as the reactor's.
 * [close]: a close-up for a small face (over a video): the face fills the square, the hair and the neck cut off, lit brighter on a
 * glow of the theme's colour, since the dark looks melt into the background at that size.
 */
@Composable
internal fun AvatarView(controller: AvatarController, state: JarvisState, outputLevel: Float, modifier: Modifier = Modifier, close: Boolean = false) {
    val model = controller.shownModel
    // The head (its mesh read, about a second on a phone, and its renderer built) is made off the main thread: the screen shows at
    // once and the face appears when it is ready, instead of the whole first frame waiting for it.
    val hair = controller.hair
    val hairColour = controller.hairColour
    val shape = controller.shapeKey
    val head by androidx.compose.runtime.produceState<Pair<AvatarRenderer, HoloAvatar>?>(null, controller, model, hair, hairColour, shape) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { controller.head().let { (m, a) -> AvatarRenderer(m) to a } }
    }
    val cartoon = remember { CartoonRenderer() }
    val reactions = controller.reactions
    LaunchedEffect(reactions, head) { if (reactions > 0) head?.second?.react() }
    // a character's files (its mesh and its atlas image) are read off the main thread: the face appears when they are
    val characterFolder = avatarFace(model).character
    val character by androidx.compose.runtime.produceState<CharacterRenderer?>(null, controller, model, characterFolder) {
        value = if (characterFolder == null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            controller.character()?.let { CharacterRenderer(it) }
        }
    }
    val currentState by rememberUpdatedState(state)
    val currentLevel by rememberUpdatedState(outputLevel)
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(controller, model, hair, hairColour, head) {
        // the animation of the very head being drawn
        val avatar = head?.second ?: return@LaunchedEffect
        var last = SystemClock.elapsedRealtimeNanos()
        var lastDraw = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { nanos ->
                // A sleeping face only breathes and blinks: half the frame rate is plenty and saves battery.
                val sleeping = currentState == JarvisState.ASLEEP || currentState == JarvisState.ERROR
                // the light mode draws at half the rate (a phone that struggles, or battery to spare)
                if (nanos - lastDraw < (if (sleeping || controller.light) SLEEP_FRAME_NS else FRAME_NS)) return@withFrameNanos
                lastDraw = nanos
                val now = SystemClock.elapsedRealtimeNanos()
                val dt = (now - last) / 1e9f
                last = now
                avatar.watching = controller.watching
                avatar.yawOverride = controller.debugYaw; avatar.pitchOverride = controller.debugPitch
                avatar.rollOverride = controller.debugRoll
                avatar.mouthOverride = controller.debugMouth
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

    Canvas(modifier.fillMaxWidth().aspectRatio(1f).then(if (close) Modifier.clipToBounds() else Modifier)) {
        @Suppress("UNUSED_VARIABLE") val tick = frame // reading it makes the canvas redraw with every animation step
        val (renderer, avatar) = head ?: return@Canvas
        // head half-height: the head fills about 72 % of the square, the neck fades below it; a close-up: the face fills it
        val r = size.minDimension * (if (close) 0.58f else 0.36f)
        val cy = size.height * (if (close) 0.47f else 0.44f)
        if (close) {
            drawCircle(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(androidx.compose.ui.graphics.Color(primary).copy(alpha = 0.45f), androidx.compose.ui.graphics.Color.Transparent),
                    center = center, radius = size.minDimension * 0.62f,
                ),
            )
            drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset.Zero, size), CLOSE_UP_PAINT)
        }
        try {
            drawHead(renderer, avatar, controller, model, hairColour, characterFolder, character, cartoon, cy, r, primary, accent, bg, stroke)
        } finally {
            if (close) drawContext.canvas.restore()
        }
    }
}

/** Brighter and a little lifted, for the small face: the dark looks otherwise melt into the background. */
private val CLOSE_UP_PAINT = androidx.compose.ui.graphics.Paint().apply {
    colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(
        androidx.compose.ui.graphics.ColorMatrix(
            floatArrayOf(
                1.5f, 0f, 0f, 0f, 22f,
                0f, 1.5f, 0f, 0f, 22f,
                0f, 0f, 1.5f, 0f, 22f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHead(
    renderer: AvatarRenderer, avatar: HoloAvatar, controller: AvatarController, model: Int, hairColour: String,
    characterFolder: String?, character: CharacterRenderer?, cartoon: CartoonRenderer, cy: Float, r: Float,
    primary: Int, accent: Int, bg: Int, stroke: Float,
) {
    run {
        if (characterFolder != null) {
            character?.draw(this, avatar, size.width / 2f, cy, r, primary, bg)
            return
        }
        if (avatarFace(model).cartoon) {
            cartoon.draw(this, avatar, size.width / 2f, cy, r, if (controller.skin >= HOLO_SKIN) 1 else controller.skin, controller.lips)
            return
        }
        renderer.scanY = avatar.scan
        renderer.holo = controller.skin >= HOLO_SKIN
        renderer.holoHair = controller.skin == HOLO_HAIR_SKIN
        // the hologram wears the matt tone, or the blue skin of the blue hologram
        renderer.blueMix = controller.skin == BLUE_HOLO_SKIN || controller.skin == DARK_BLUE_HOLO_SKIN
        renderer.skin = when {
            renderer.blueMix -> 5
            renderer.holo -> 2
            else -> controller.skin
        }
        renderer.lips = controller.lips
        renderer.cap = controller.cap
        // the hologram's own dark ink, not a pale theme cyan: a mood change (a lift, a lowered lid) has to read at a glance
        renderer.browColour = if (renderer.holo) DEEP_BLUE else hairShade(hairColour)?.browColour ?: avatarFace(model).browColour
        renderer.browScale = avatarFace(model).browScale
        renderer.lashScale = avatarFace(model).lashScale
        renderer.androidLook = avatarFace(model).androidLook
        renderer.halo = avatarFace(model).halo
        renderer.lipTint = avatarFace(model).lipTint
        renderer.fibreOverlay = avatarFace(model).fibres && !controller.light
        renderer.haseoEyes = avatarFace(model).haseo
        renderer.draw(this, avatar, size.width / 2f, cy, r, primary, accent, bg, stroke)
    }
}

/** The value of the skin setting for the hologram over the skin (0 is the web alone, 1..4 the tones). */
internal const val HOLO_SKIN = 5

/** The hologram look with the hair of optical fibres. */
internal const val HOLO_HAIR_SKIN = 6

/** The hologram with the blue skin: light blue with deep blue accents, and more gold circuits. */
internal const val BLUE_HOLO_SKIN = 7

/** Kept for settings saved by a test build: it looks the same as [BLUE_HOLO_SKIN]. */
internal const val DARK_BLUE_HOLO_SKIN = 8

private const val FRAME_NS = 30_000_000L
private const val SLEEP_FRAME_NS = 66_000_000L
