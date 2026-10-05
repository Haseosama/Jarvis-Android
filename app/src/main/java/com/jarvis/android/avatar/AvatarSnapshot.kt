package com.jarvis.android.avatar

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.random.Random

/**
 * The chosen face drawn once into a bitmap, for the home-screen widget (which cannot show a live view). A close-up, as the small face
 * over a video: the face fills the square. It is drawn from a head of its own (eyes open, at rest), so the face on screen, if the app is
 * open, is not disturbed. Slow (the head is read, about a second): call it off the main thread.
 */
internal object AvatarSnapshot {
    /** The settings that change how the face looks, to know when a drawn face is out of date. */
    fun key(controller: AvatarController): String =
        listOf(controller.shownModel, controller.hair, controller.hairColour, controller.skin, controller.lips, controller.cap, controller.shapeKey)
            .joinToString("|")

    fun draw(controller: AvatarController, sizePx: Int, density: Float, primary: Int, accent: Int, bg: Int): Bitmap {
        val model = controller.shownModel
        val (mesh, _) = controller.head()
        val avatar = HoloAvatar(mesh, Random(7))
        // half a second of rest: the face settles, well before its first blink (at three seconds)
        repeat(25) { avatar.step(0.02f, 0f, false, Mood.IDLE, null) }
        val characterFolder = avatarFace(model).character
        val character = if (characterFolder == null) null else controller.character()?.let { CharacterRenderer(it) }
        val renderer = AvatarRenderer(mesh)
        val image = ImageBitmap(sizePx, sizePx, ImageBitmapConfig.Argb8888)
        val canvas = androidx.compose.ui.graphics.Canvas(image)
        val side = sizePx.toFloat()
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, canvas, Size(side, side)) {
            // brighter, as the close-up on screen: the dark looks otherwise melt into the widget's dark background
            drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset.Zero, size), CLOSE_UP_PAINT)
            try {
                drawHead(
                    renderer, avatar, controller, model, controller.hairColour, characterFolder, character, CartoonRenderer(),
                    cy = side * CLOSE_CENTRE, r = side * CLOSE_RADIUS, primary = primary, accent = accent, bg = bg, stroke = 1.1f * density,
                )
            } finally {
                drawContext.canvas.restore()
            }
        }
        return image.asAndroidBitmap()
    }

    // the close-up's measures in AvatarView (headRadius and headCentre with close = true)
    private const val CLOSE_RADIUS = 0.58f
    private const val CLOSE_CENTRE = 0.47f
}
