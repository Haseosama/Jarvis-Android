package com.jarvis.android.avatar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Colours of a skin tone: the base, a light for the highlights, a shade for the shadows, and the blush. */
internal class SkinPalette(val base: Color, val light: Color, val shade: Color, val blush: Color)

/** The four skin tones of the settings (1 light, 2 medium, 3 tanned, 4 dark); 0 (the glowing web) is drawn as light. */
internal fun skinPalette(tone: Int): SkinPalette = when (tone) {
    2 -> SkinPalette(Color(0xFFE0A47A), Color(0xFFF2C39C), Color(0xFFB87650), Color(0xFFE58A72))
    3 -> SkinPalette(Color(0xFFC38A5F), Color(0xFFDAA57C), Color(0xFF9A6442), Color(0xFFC97256))
    4 -> SkinPalette(Color(0xFF8E5C3F), Color(0xFFA9774F), Color(0xFF6A3F2A), Color(0xFF9E5A45))
    else -> SkinPalette(Color(0xFFF0C4A0), Color(0xFFFFDDBF), Color(0xFFD59A78), Color(0xFFF0A08A))
}

/** The colour of the lips: natural (the skin, a little redder) or one of the four lip colours of the settings. */
internal fun lipColour(tone: Int, skin: SkinPalette): Color = when (tone) {
    1 -> Color(0xFFD9788A)
    2 -> Color(0xFFC0392B)
    3 -> Color(0xFF8E3B63)
    4 -> Color(0xFFE8705A)
    else -> Color(
        red = (skin.shade.red * 0.80f + 0.10f).coerceIn(0f, 1f), green = skin.shade.green * 0.62f, blue = skin.shade.blue * 0.62f,
    )
}

/** What the drawing needs of the animation, all in the ranges of [HoloAvatar]. Kept apart so it can be tested without a canvas. */
internal data class CartoonPose(
    val mouth: Float, val wide: Float, val brow: Float, val eyeOpen: Float,
    val gazeX: Float, val gazeY: Float, val yaw: Float, val pitch: Float, val time: Float,
)

/** The open fraction of the eyes (1 wide, 0 shut) from the blink and the lids of the state (low when asleep). */
internal fun eyeOpenness(blink: Float, lids: Float): Float = ((1f - blink) * lids).coerceIn(0.03f, 1f)

internal fun cartoonPose(a: HoloAvatar) = CartoonPose(
    mouth = a.mouth.coerceIn(0f, 1f), wide = a.wide.coerceIn(-1f, 1f), brow = a.brow.coerceIn(-0.4f, 1.2f),
    eyeOpen = eyeOpenness(a.blink, a.lids), gazeX = a.gaze[0].coerceIn(-1f, 1f), gazeY = a.gaze[1].coerceIn(-1f, 1f),
    yaw = a.yaw, pitch = a.pitch, time = a.time,
)

/**
 * A cartoon head in the style of a soft 3D character: round face, big glasses, a beard, curly dark hair, a hoodie. Drawn with paths and
 * gradients on the canvas, and driven by the same animation as the scanned heads (lip-sync, blinks, gaze, brows, sway). The origin of its own
 * coordinates is the centre of the face, one unit is about the face's half-width, y goes down.
 */
internal class CartoonRenderer {
    private val p = Path()
    private val q = Path()

    private val hairDark = Color(0xFF1F1614)
    private val hairMid = Color(0xFF3B2B26)
    private val hairLight = Color(0xFF6A5045)
    private val beardDark = Color(0xFF2E211D)
    private val beardMid = Color(0xFF4A3830)
    private val hoodie = Color(0xFF3E4550)
    private val hoodieShade = Color(0xFF2A2F38)
    private val tee = Color(0xFFB9BEC4)

    fun draw(scope: DrawScope, a: HoloAvatar, cx: Float, cy: Float, r: Float, skinTone: Int, lipTone: Int) {
        val pose = cartoonPose(a)
        val skin = skinPalette(skinTone)
        // the character is a little larger than the scanned head: its unit is the face's half-width, about 0.62 of the head's half-height
        val k = r * 0.86f
        val roll = pose.yaw * 2.2f
        with(scope) {
            withTransform({
                translate(cx, cy - r * 0.05f)
                rotate(roll, Offset.Zero)
                scale(k, k, Offset.Zero)
            }) {
                val breath = 1f + 0.012f * sin(pose.time * 1.55f)
                val fx = pose.yaw * 0.26f                      // the features slide over the head as it turns
                val fy = pose.pitch * 0.55f
                val jaw = pose.mouth * 0.11f
                drawBody(skin, breath, fx * 0.4f)
                drawEars(skin, fx * -0.25f)
                drawHairBack(fx * 0.30f)
                drawFace(skin, pose, jaw, fx, fy)
                drawBeard(pose, jaw, fx, fy)
                drawMouth(skin, lipTone, pose, jaw, fx, fy)
                drawNose(skin, fx, fy)
                drawEyes(pose, fx, fy)
                drawBrows(pose, fx, fy)
                drawGlasses(fx, fy)
                drawHairFront(fx * 0.30f)
            }
        }
    }

    // ── body ──────────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun DrawScope.drawBody(skin: SkinPalette, breath: Float, dx: Float) {
        // the neck
        p.reset()
        p.moveTo(-0.40f + dx, 0.55f); p.lineTo(-0.44f + dx, 1.30f); p.lineTo(0.44f + dx, 1.30f); p.lineTo(0.40f + dx, 0.55f); p.close()
        drawPath(p, Brush.verticalGradient(listOf(skin.shade, skin.base), 0.55f, 1.3f))
        // the hoodie: shoulders, the hood behind the neck, the neckline of the tee
        withTransform({ scale(1f, breath, Offset(0f, 1.9f)) }) {
            p.reset()
            p.moveTo(-1.95f, 2.6f); p.lineTo(-1.75f, 1.75f)
            p.cubicTo(-1.55f, 1.28f, -1.0f, 1.16f, -0.62f, 1.06f)
            p.lineTo(0.62f, 1.06f)
            p.cubicTo(1.0f, 1.16f, 1.55f, 1.28f, 1.75f, 1.75f)
            p.lineTo(1.95f, 2.6f); p.close()
            drawPath(p, Brush.verticalGradient(listOf(hoodie, hoodieShade), 1.05f, 2.4f))
            // the hood folded round the neck
            p.reset()
            p.moveTo(-0.72f, 1.05f)
            p.cubicTo(-0.78f, 1.30f, -0.60f, 1.52f, -0.30f, 1.60f)
            p.lineTo(0.30f, 1.60f)
            p.cubicTo(0.60f, 1.52f, 0.78f, 1.30f, 0.72f, 1.05f)
            p.cubicTo(0.55f, 1.16f, 0.30f, 1.20f, 0f, 1.20f)
            p.cubicTo(-0.30f, 1.20f, -0.55f, 1.16f, -0.72f, 1.05f); p.close()
            drawPath(p, hoodieShade)
            // the tee under it
            p.reset()
            p.moveTo(-0.44f, 1.24f)
            p.cubicTo(-0.40f, 1.55f, -0.22f, 1.72f, 0f, 1.72f)
            p.cubicTo(0.22f, 1.72f, 0.40f, 1.55f, 0.44f, 1.24f)
            p.cubicTo(0.26f, 1.36f, -0.26f, 1.36f, -0.44f, 1.24f); p.close()
            drawPath(p, tee)
            // the strings of the hood
            drawLine(tee.copy(alpha = 0.85f), Offset(-0.20f, 1.42f), Offset(-0.24f, 1.95f), strokeWidth = 0.035f, cap = StrokeCap.Round)
            drawLine(tee.copy(alpha = 0.85f), Offset(0.20f, 1.42f), Offset(0.24f, 1.95f), strokeWidth = 0.035f, cap = StrokeCap.Round)
            drawCircle(tee, 0.028f, Offset(-0.24f, 1.96f)); drawCircle(tee, 0.028f, Offset(0.24f, 1.96f))
        }
        // the shadow of the head on the neck
        p.reset()
        p.moveTo(-0.40f + dx, 0.62f)
        p.cubicTo(-0.20f, 1.08f, 0.20f, 1.08f, 0.40f + dx, 0.62f); p.lineTo(0.42f + dx, 0.55f); p.lineTo(-0.42f + dx, 0.55f); p.close()
        drawPath(p, Color(0x55000000))
    }

    private fun DrawScope.drawEars(skin: SkinPalette, dx: Float) {
        for (s in floatArrayOf(-1f, 1f)) {
            val c = Offset(s * 0.83f + dx, 0.10f)
            drawOval(Brush.radialGradient(listOf(skin.light, skin.base), c, 0.20f), Offset(c.x - 0.11f, c.y - 0.20f), Size(0.22f, 0.40f))
            drawOval(skin.shade.copy(alpha = 0.55f), Offset(c.x - 0.045f + s * 0.01f, c.y - 0.10f), Size(0.09f, 0.20f))
        }
    }

    // ── face ──────────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun facePath(path: Path, jaw: Float, grow: Float = 0f) {
        path.reset()
        val w = 0.80f + grow
        val chin = 0.96f + jaw + grow
        path.moveTo(0f, -0.84f - grow)
        path.cubicTo(0.52f, -0.84f - grow, w, -0.58f, w, -0.10f)
        path.cubicTo(w + 0.01f, 0.34f, 0.62f + grow, chin - 0.08f, 0.26f, chin - 0.01f)
        path.cubicTo(0.12f, chin + 0.02f, -0.12f, chin + 0.02f, -0.26f, chin - 0.01f)
        path.cubicTo(-0.62f - grow, chin - 0.08f, -w - 0.01f, 0.34f, -w, -0.10f)
        path.cubicTo(-w, -0.58f, -0.52f, -0.84f - grow, 0f, -0.84f - grow)
        path.close()
    }

    private fun DrawScope.drawFace(skin: SkinPalette, pose: CartoonPose, jaw: Float, fx: Float, fy: Float) {
        facePath(p, jaw)
        drawPath(p, Brush.radialGradient(listOf(skin.light, skin.base, skin.shade), Offset(-0.12f + fx * 0.5f, -0.20f), 1.25f))
        clipPath(p) {
            // the cheeks and the forehead catch the light; the temples and the jaw fall into shade
            drawOval(skin.blush.copy(alpha = 0.34f), Offset(-0.70f + fx, 0.14f + fy), Size(0.36f, 0.24f))
            drawOval(skin.blush.copy(alpha = 0.34f), Offset(0.34f + fx, 0.14f + fy), Size(0.36f, 0.24f))
            drawOval(Color.White.copy(alpha = 0.20f), Offset(-0.34f + fx * 0.6f, -0.72f), Size(0.66f, 0.26f))
            drawOval(Color(0x14000000), Offset(-0.98f, -0.6f), Size(0.36f, 1.4f))
            drawOval(Color(0x14000000), Offset(0.62f, -0.6f), Size(0.36f, 1.4f))
        }
    }

    // ── beard, mouth, nose ────────────────────────────────────────────────────────────────────────────────────────────────

    private fun DrawScope.drawBeard(pose: CartoonPose, jaw: Float, fx: Float, fy: Float) {
        val chin = 1.02f + jaw
        p.reset()
        // outer contour: from the right sideburn along the jaw to the chin and back up on the left
        p.moveTo(0.80f, -0.06f)
        p.cubicTo(0.84f, 0.36f, 0.66f, chin - 0.10f, 0.28f, chin + 0.02f)
        p.cubicTo(0.12f, chin + 0.07f, -0.12f, chin + 0.07f, -0.28f, chin + 0.02f)
        p.cubicTo(-0.66f, chin - 0.10f, -0.84f, 0.36f, -0.80f, -0.06f)
        // inner contour: the cheek line, then over the moustache and back
        p.cubicTo(-0.75f, 0.12f, -0.64f, 0.30f + fy * 0.2f, -0.48f + fx * 0.3f, 0.42f + fy * 0.3f)
        p.cubicTo(-0.36f + fx * 0.4f, 0.50f + fy, -0.20f + fx, 0.47f + fy, 0f + fx, 0.49f + fy)
        p.cubicTo(0.20f + fx, 0.47f + fy, 0.36f + fx * 0.4f, 0.50f + fy, 0.48f + fx * 0.3f, 0.42f + fy * 0.3f)
        p.cubicTo(0.64f, 0.30f + fy * 0.2f, 0.75f, 0.12f, 0.80f, -0.06f)
        p.close()
        drawPath(p, Brush.verticalGradient(listOf(beardMid, beardDark), 0.2f, chin + 0.1f))
        // short hairs along the contour and on the chin, so it reads as hair and not as a flat shape
        clipPath(p) {
            var seed = 7
            for (i in 0 until 90) {
                seed = (seed * 1103515245 + 12345) and 0x7fffffff
                val u = (seed % 1000) / 1000f
                seed = (seed * 1103515245 + 12345) and 0x7fffffff
                val v = (seed % 1000) / 1000f
                val x = (u * 2f - 1f) * 0.74f
                val y = 0.46f + v * (chin - 0.42f)
                val len = 0.05f + 0.03f * v
                drawLine(hairLight.copy(alpha = 0.32f), Offset(x + fx * 0.3f, y), Offset(x * 1.03f + fx * 0.3f, y + len), strokeWidth = 0.012f, cap = StrokeCap.Round)
            }
        }
    }

    private fun DrawScope.drawMouth(skin: SkinPalette, lipTone: Int, pose: CartoonPose, jaw: Float, fx: Float, fy: Float) {
        val lip = lipColour(lipTone, skin)
        val cxm = fx * 0.9f
        val cym = 0.64f + fy * 0.8f
        val half = 0.31f * (1f + 0.30f * pose.wide.coerceAtLeast(0f) - 0.22f * (-pose.wide).coerceAtLeast(0f) - 0.10f * pose.mouth)
        val open = 0.06f + pose.mouth * 0.30f                   // the smile always shows a little of the teeth
        val cornerLift = 0.095f - 0.04f * pose.mouth
        val topY = cym - 0.02f
        val botY = cym + open + jaw * 0.6f
        // the opening: a smile above, a rounder lip below
        p.reset()
        p.moveTo(cxm - half, topY - cornerLift)
        p.cubicTo(cxm - half * 0.5f, topY + 0.045f, cxm + half * 0.5f, topY + 0.045f, cxm + half, topY - cornerLift)
        p.cubicTo(cxm + half * 0.9f, botY + 0.05f, cxm + half * 0.35f, botY + 0.085f, cxm, botY + 0.085f)
        p.cubicTo(cxm - half * 0.35f, botY + 0.085f, cxm - half * 0.9f, botY + 0.05f, cxm - half, topY - cornerLift)
        p.close()
        // the mouth: the lips, then the inside, the teeth and the tongue
        drawPath(p, lip)
        q.reset()
        val inset = 0.028f
        q.moveTo(cxm - half + inset, topY - cornerLift + 0.012f)
        q.cubicTo(cxm - half * 0.5f, topY + 0.045f + inset, cxm + half * 0.5f, topY + 0.045f + inset, cxm + half - inset, topY - cornerLift + 0.012f)
        q.cubicTo(cxm + half * 0.86f, botY + 0.03f, cxm + half * 0.33f, botY + 0.055f, cxm, botY + 0.055f)
        q.cubicTo(cxm - half * 0.33f, botY + 0.055f, cxm - half * 0.86f, botY + 0.03f, cxm - half + inset, topY - cornerLift + 0.012f)
        q.close()
        clipPath(q) {
            drawRect(Color(0xFF3B1414), Offset(cxm - half, topY - 0.2f), Size(half * 2f, 0.8f))
            val tongue = 0.13f + 0.4f * pose.mouth
            drawOval(Color(0xFFC85B62), Offset(cxm - half * 0.55f, botY - 0.02f), Size(half * 1.1f, tongue))
            // the upper teeth: a white band under the upper lip
            drawRect(Color(0xFFF7F3EC), Offset(cxm - half, topY - 0.10f), Size(half * 2f, 0.10f + 0.045f + 0.10f * pose.mouth))
            drawRect(Color(0x18000000), Offset(cxm - half, topY + 0.02f + 0.10f * pose.mouth), Size(half * 2f, 0.02f))
            // the teeth are separated by fine lines
            for (i in -3..3) drawLine(Color(0x22000000), Offset(cxm + i * half * 0.28f, topY - 0.01f), Offset(cxm + i * half * 0.28f, topY + 0.05f + 0.10f * pose.mouth), strokeWidth = 0.006f)
        }
        // the moustache over the upper lip
        for (s in floatArrayOf(-1f, 1f)) {
            p.reset()
            p.moveTo(cxm, topY - 0.075f)
            p.cubicTo(cxm + s * 0.16f, topY - 0.10f, cxm + s * 0.34f, topY - 0.09f, cxm + s * (half + 0.08f), topY - cornerLift - 0.015f)
            p.cubicTo(cxm + s * (half + 0.02f), topY - cornerLift + 0.035f, cxm + s * half * 0.6f, topY - 0.012f, cxm, topY - 0.02f)
            p.close()
            drawPath(p, Brush.verticalGradient(listOf(beardMid, beardDark), topY - 0.14f, topY + 0.02f))
        }
    }

    private fun DrawScope.drawNose(skin: SkinPalette, fx: Float, fy: Float) {
        val x = fx * 1.0f
        val y = 0.17f + fy
        // the shadow along the sides of the bridge, under the glasses
        for (s in floatArrayOf(-1f, 1f)) {
            p.reset()
            p.moveTo(x + s * 0.045f, 0.10f + fy); p.cubicTo(x + s * 0.11f, 0.18f + fy, x + s * 0.16f, y + 0.02f, x + s * 0.20f, y + 0.10f)
            p.lineTo(x + s * 0.15f, y + 0.12f); p.cubicTo(x + s * 0.11f, y + 0.06f, x + s * 0.07f, 0.16f + fy, x + s * 0.02f, 0.10f + fy); p.close()
            drawPath(p, skin.shade.copy(alpha = 0.30f))
        }
        // the tip, round and lit from above, with the wings and the nostrils under it
        drawOval(Brush.radialGradient(listOf(skin.light, skin.base, skin.shade.copy(alpha = 0.9f)), Offset(x - 0.02f, y + 0.02f), 0.22f), Offset(x - 0.17f, y - 0.07f), Size(0.34f, 0.30f))
        drawOval(skin.shade.copy(alpha = 0.70f), Offset(x - 0.135f, y + 0.135f), Size(0.075f, 0.055f))
        drawOval(skin.shade.copy(alpha = 0.70f), Offset(x + 0.06f, y + 0.135f), Size(0.075f, 0.055f))
        drawOval(Color.White.copy(alpha = 0.38f), Offset(x - 0.08f, y - 0.03f), Size(0.11f, 0.08f))
    }

    // ── eyes, brows, glasses ──────────────────────────────────────────────────────────────────────────────────────────────

    private fun DrawScope.drawEyes(pose: CartoonPose, fx: Float, fy: Float) {
        val open = pose.eyeOpen
        for (s in floatArrayOf(-1f, 1f)) {
            val c = Offset(s * 0.33f + fx, -0.02f + fy)
            val w = 0.215f
            val h = 0.150f * open + 0.006f
            p.reset()
            p.moveTo(c.x - w, c.y)
            p.cubicTo(c.x - w * 0.6f, c.y - h * 1.45f, c.x + w * 0.6f, c.y - h * 1.45f, c.x + w, c.y)
            p.cubicTo(c.x + w * 0.6f, c.y + h * 1.05f, c.x - w * 0.6f, c.y + h * 1.05f, c.x - w, c.y)
            p.close()
            clipPath(p) {
                drawRect(Color(0xFFFDFBF7), Offset(c.x - w, c.y - 0.2f), Size(2 * w, 0.4f))
                // the iris follows the gaze (a little less than the eyeball would turn), with a pupil and two lights
                val ix = c.x + pose.gazeX * 0.075f
                val iy = c.y + pose.gazeY * 0.045f + 0.005f
                drawCircle(Brush.radialGradient(listOf(Color(0xFF8A5A3A), Color(0xFF4A2A1C)), Offset(ix, iy), 0.13f), 0.122f, Offset(ix, iy))
                drawCircle(Color(0xFF140C0A), 0.058f, Offset(ix, iy))
                drawCircle(Color.White, 0.034f, Offset(ix - 0.045f, iy - 0.05f))
                drawCircle(Color.White.copy(alpha = 0.8f), 0.013f, Offset(ix + 0.04f, iy + 0.03f))
                // the shadow of the upper lid on the eye
                drawRect(Color(0x16000000), Offset(c.x - w, c.y - 0.2f), Size(2 * w, 0.2f - h * 0.55f + 0.05f))
            }
            // the lash line, and the fold above the eye
            val lash = Path().apply {
                moveTo(c.x - w, c.y)
                cubicTo(c.x - w * 0.6f, c.y - h * 1.45f, c.x + w * 0.6f, c.y - h * 1.45f, c.x + w, c.y)
            }
            drawPath(lash, Color(0xFF1B1210), style = Stroke(width = 0.030f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawArc(Color(0x22502820), 200f, 140f, false, Offset(c.x - w * 0.9f, c.y - 0.20f * open - 0.04f), Size(w * 1.8f, 0.2f), style = Stroke(width = 0.02f, cap = StrokeCap.Round))
        }
    }

    private fun DrawScope.drawBrows(pose: CartoonPose, fx: Float, fy: Float) {
        val lift = pose.brow * 0.06f
        val tilt = (pose.brow - 0.25f) * 0.045f                    // raised, the inner ends go up; lowered, they go down (a frown)
        for (s in floatArrayOf(-1f, 1f)) {
            val y = -0.33f + fy - lift
            val inner = Offset(s * 0.085f + fx, y + tilt * -1f + 0.02f)
            val outer = Offset(s * 0.53f + fx, y + 0.02f + tilt * 1.2f)
            val mid = Offset(s * 0.31f + fx, y - 0.075f)
            p.reset()
            p.moveTo(inner.x, inner.y - 0.02f)
            p.cubicTo(inner.x + s * 0.06f, mid.y - 0.03f, mid.x + s * 0.06f, mid.y - 0.05f, outer.x, outer.y - 0.03f)
            p.cubicTo(outer.x + s * 0.01f, outer.y + 0.05f, mid.x + s * 0.02f, mid.y + 0.06f, inner.x, inner.y + 0.08f)
            p.close()
            drawPath(p, Brush.verticalGradient(listOf(hairMid, hairDark), mid.y - 0.08f, mid.y + 0.1f))
        }
    }

    private fun DrawScope.drawGlasses(fx: Float, fy: Float) {
        val frame = Color(0xFF14110F)
        val width = 0.062f
        val cyL = -0.02f + fy
        val centres = floatArrayOf(-0.335f + fx, 0.335f + fx)
        val radius = 0.285f
        for (cxl in centres) {
            // a faint tint and a reflection on the lens
            drawCircle(Color(0x12FFC8B8), radius, Offset(cxl, cyL))
            clipPath(Path().apply { addOval(Rect(cxl - radius, cyL - radius, cxl + radius, cyL + radius)) }) {
                drawArc(Color.White.copy(alpha = 0.16f), 205f, 60f, false, Offset(cxl - radius * 0.78f, cyL - radius * 0.78f), Size(radius * 1.56f, radius * 1.56f), style = Stroke(width = 0.05f, cap = StrokeCap.Round))
            }
            drawCircle(frame, radius, Offset(cxl, cyL), style = Stroke(width = width))
            drawCircle(Color.White.copy(alpha = 0.18f), radius - width * 0.4f, Offset(cxl - 0.01f, cyL - 0.01f), style = Stroke(width = 0.012f))
        }
        // the bridge, and the arms going back to the ears
        val bridge = Path().apply {
            moveTo(centres[0] + radius - 0.01f, cyL - 0.03f)
            quadraticBezierTo((centres[0] + centres[1]) / 2f, cyL - 0.10f, centres[1] - radius + 0.01f, cyL - 0.03f)
        }
        drawPath(bridge, frame, style = Stroke(width = width * 0.85f, cap = StrokeCap.Round))
        drawLine(frame, Offset(centres[0] - radius, cyL - 0.02f), Offset(-0.83f + fx * 0.3f, -0.04f), strokeWidth = width * 0.8f, cap = StrokeCap.Round)
        drawLine(frame, Offset(centres[1] + radius, cyL - 0.02f), Offset(0.83f + fx * 0.3f, -0.04f), strokeWidth = width * 0.8f, cap = StrokeCap.Round)
    }

    // ── hair ──────────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The mass of hair behind the head and the ears. */
    private fun DrawScope.drawHairBack(dx: Float) {
        p.reset()
        p.moveTo(-0.92f + dx, 0.05f)
        p.cubicTo(-1.06f + dx, -0.5f, -0.9f + dx, -1.05f, -0.2f + dx, -1.12f)
        p.cubicTo(0.5f + dx, -1.18f, 1.05f + dx, -0.75f, 0.94f + dx, 0.02f)
        p.lineTo(0.7f, -0.2f); p.lineTo(-0.7f, -0.2f); p.close()
        drawPath(p, hairDark)
    }

    /** The hair over the forehead: a big swept quiff of curls. */
    private fun DrawScope.drawHairFront(dx: Float) {
        // the base of the hair, with its hairline over the forehead
        p.reset()
        p.moveTo(-0.84f + dx, -0.10f)
        p.cubicTo(-0.96f + dx, -0.62f, -0.70f + dx, -1.05f, -0.10f + dx, -1.06f)
        p.cubicTo(0.55f + dx, -1.08f, 0.98f + dx, -0.70f, 0.84f + dx, -0.10f)
        p.cubicTo(0.80f + dx, -0.30f, 0.66f + dx, -0.50f, 0.50f + dx, -0.56f)
        p.cubicTo(0.30f + dx, -0.66f, 0.10f + dx, -0.62f, -0.06f + dx, -0.54f)
        p.cubicTo(-0.30f + dx, -0.64f, -0.52f + dx, -0.56f, -0.62f + dx, -0.40f)
        p.cubicTo(-0.74f + dx, -0.32f, -0.80f + dx, -0.22f, -0.84f + dx, -0.10f)
        p.close()
        drawPath(p, Brush.verticalGradient(listOf(hairMid, hairDark), -1.1f, -0.4f))
        // the curls: petals swept up and to the right, with a light along the top of each
        val curls = floatArrayOf(
            // x, y, radius, angle
            -0.62f, -0.78f, 0.17f, -100f, -0.42f, -0.98f, 0.19f, -85f, -0.18f, -1.12f, 0.21f, -60f,
            0.08f, -1.18f, 0.23f, -35f, 0.34f, -1.12f, 0.22f, -15f, 0.58f, -0.98f, 0.20f, 10f,
            0.76f, -0.78f, 0.17f, 35f, 0.84f, -0.52f, 0.14f, 60f, -0.80f, -0.52f, 0.14f, -120f,
            -0.30f, -0.86f, 0.15f, -70f, 0.0f, -0.92f, 0.16f, -50f, 0.28f, -0.88f, 0.15f, -20f,
            0.50f, -0.76f, 0.13f, 20f, -0.52f, -0.66f, 0.12f, -95f,
        )
        var i = 0
        while (i < curls.size) {
            val cxl = curls[i] + dx; val cyl = curls[i + 1]; val rad = curls[i + 2]; val ang = curls[i + 3] * (PI.toFloat() / 180f)
            drawOval(Brush.radialGradient(listOf(hairLight.copy(alpha = 0.85f), hairMid, hairDark), Offset(cxl + cos(ang) * rad * 0.35f, cyl + sin(ang) * rad * 0.35f), rad * 1.5f),
                Offset(cxl - rad, cyl - rad * 0.82f), Size(rad * 2f, rad * 1.64f))
            // the curl's light
            drawArc(hairLight.copy(alpha = 0.55f), curls[i + 3] - 50f, 100f, false, Offset(cxl - rad * 0.75f, cyl - rad * 0.62f), Size(rad * 1.5f, rad * 1.24f), style = Stroke(width = 0.018f, cap = StrokeCap.Round))
            i += 4
        }
        // the fringe: small curls along the hairline, one falling a little lower on the left
        val fringe = floatArrayOf(-0.56f, -0.50f, 0.115f, -0.33f, -0.58f, 0.10f, -0.10f, -0.585f, 0.095f, 0.16f, -0.60f, 0.10f, 0.42f, -0.55f, 0.11f, 0.62f, -0.44f, 0.10f)
        var j = 0
        while (j < fringe.size) {
            val fx0 = fringe[j] + dx; val fy0 = fringe[j + 1]; val fr = fringe[j + 2]
            drawOval(Brush.radialGradient(listOf(hairLight.copy(alpha = 0.7f), hairMid, hairDark), Offset(fx0 - fr * 0.2f, fy0 - fr * 0.3f), fr * 1.5f), Offset(fx0 - fr, fy0 - fr * 0.85f), Size(fr * 2f, fr * 1.7f))
            j += 3
        }
    }

}
