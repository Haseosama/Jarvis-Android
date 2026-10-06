package com.jarvis.android.avatar

import kotlin.math.pow

/*
 * How a smooth surface is lit at one vertex: the head's (AvatarRenderer.vertexColour), and the key light the robot in augmented reality
 * is lit by too (ar/RobotPaint.kt). Normals are unit vectors in view space (+z to the viewer).
 */

/** How much a surface facing ([nx], [ny], [nz]) takes of the key light, which comes from above the viewer's left (0..1). */
internal fun keyLight(nx: Float, ny: Float, nz: Float): Float = (nx * -0.55f + ny * 0.50f + nz * 0.52f).coerceIn(0f, 1f)

/** [rgb] with its channels scaled by [k], fully opaque. */
internal fun litRgb(rgb: Int, k: Float): Int =
    opaque((((rgb shr 16) and 0xFF) * k).toInt().coerceIn(0, 255), (((rgb shr 8) and 0xFF) * k).toInt().coerceIn(0, 255), ((rgb and 0xFF) * k).toInt().coerceIn(0, 255))

/** From [base] towards [other] by [f], fully opaque. */
internal fun mixRgbOpaque(base: Int, other: Int, f: Float): Int {
    fun ch(shift: Int): Int {
        val b0 = (base shr shift) and 0xFF
        val c0 = (other shr shift) and 0xFF
        return (b0 + (c0 - b0) * f).toInt().coerceIn(0, 255)
    }
    return opaque(ch(16), ch(8), ch(0))
}

/** The skin tones of the settings, 1..4, and 5 the light blue of the blue hologram, opaque. */
internal fun skinTone(skin: Int): Int = 0xFF000000.toInt() or SKIN_TONE_RGB[(skin - 1).coerceIn(0, SKIN_TONE_RGB.size - 1)]

internal const val DEEP_BLUE = 0xFF0C2160.toInt()   // the hologram's ink: brows, lashes, lid crease and lip line, for contrast against the warm or blue skin

private val SKIN_TONE_RGB = intArrayOf(0xF1C9A8, 0xD9A47C, 0xB07A54, 0x7A4E36, 0x69B4F0)

/**
 * Skin of [rgb] at a vertex facing ([vx], [vy], [vz]), the voice at [amp]: lit softly, then, unless [matte] (the holograms), a faint
 * sheen and, unless [porcelain] too, a touch of warmth where the light lands most, like blood under thin skin.
 */
internal fun shadeSkin(rgb: Int, vx: Float, vy: Float, vz: Float, amp: Float, matte: Boolean, porcelain: Boolean): Int {
    val vlam = keyLight(vx, vy, vz)
    val k = (0.30f + 0.85f * vlam + 0.10f * vz.coerceIn(0f, 1f)).coerceIn(0.15f, 1.15f) * (0.94f + 0.12f * amp)
    val c = litRgb(rgb, k)
    if (matte) return c
    val spec = (vx * -0.22f + vy * 0.28f + vz * 0.93f).coerceIn(0f, 1f).toDouble().pow(30.0).toFloat()
    val sheen = (spec * 26f).toInt()
    val blush = if (porcelain) 0 else (vlam * vlam * 9f).toInt()
    return opaque(
        (((c shr 16) and 0xFF) + sheen + blush).coerceAtMost(255),
        (((c shr 8) and 0xFF) + (sheen * 0.9f).toInt() + (blush * 0.35f).toInt()).coerceAtMost(255),
        (((c and 0xFF) + (sheen * 0.8f).toInt()).coerceAtMost(255)),
    )
}

/**
 * The hologram over a lit colour [c] at a vertex facing ([vz] towards the viewer, [vlam] its key light): a little brighter than the
 * plain looks and a cool light at the contour only; [blueMix]: light blue, deeper in the hollows, with a deep blue edge all round.
 */
internal fun holoSkin(c: Int, vlam: Float, vz: Float, blueMix: Boolean, primary: Int): Int {
    val edge = (1f - vz.coerceIn(0f, 1f)).toDouble().pow(2.4).toFloat()          // 0 facing the viewer, 1 at the contour
    return if (blueMix) {
        val hollow = mixRgbOpaque(litRgb(c, 1.16f), DEEP_BLUE, 0.55f * (1f - vlam))
        mixRgbOpaque(hollow, DEEP_BLUE, 0.80f * edge)
    } else {
        mixRgbOpaque(litRgb(c, 1.16f), mixRgbOpaque(primary, 0xFFFFFFFF.toInt(), 0.45f), 0.62f * edge)   // a soft bright edge instead of dots
    }
}

private fun opaque(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
