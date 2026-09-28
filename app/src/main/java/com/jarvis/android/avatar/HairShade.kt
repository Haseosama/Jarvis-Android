package com.jarvis.android.avatar

import kotlin.math.max

/** A hair colour the user can choose (Settings > Appearance > Couleur des cheveux): the colours a strand takes, root to tip. */
internal data class HairShade(val id: String, val labelFr: String, val labelEn: String, val colours: HairStyle.Colours) {
    fun label(english: Boolean) = if (english) labelEn else labelFr

    /** The brows' colour to go with it: the roots', a little darker. */
    val browColour: Int get() = (0xFF shl 24) or darker(colours.root, 0.85f)
}

internal val HAIR_SHADES = listOf(
    HairShade("black", "Noir", "Black", HairStyle.Colours(0x1C1918, 0x0A0909, 0x3E3936)),
    HairShade("brown", "Brun", "Brown", HairStyle.Colours(0x3B2A20, 0x1A120D, 0x6A4E3A)),
    HairShade("chestnut", "Châtain", "Chestnut", HairStyle.Colours(0x5E4029, 0x2A1C12, 0x8E6C4C)),
    HairShade("blond", "Blond", "Blond", HairStyle.Colours(0xB08A55, 0x6A4E2C, 0xE2C68E)),
    HairShade("red", "Roux", "Red", HairStyle.Colours(0x8E3C1C, 0x4A1C0C, 0xC46E3C)),
    HairShade("grey", "Poivre et sel", "Salt and pepper", HairStyle.Colours(0x4A4744, 0x242220, 0x8A8782, grey = 0.45f, greyRgb = 0x9C9994)),
    HairShade("white", "Blanc", "White", HairStyle.Colours(0xCFCCC7, 0x8E8B86, 0xF2F0EC)),
    HairShade("blue", "Bleu", "Blue", HairStyle.Colours(0x1E3A6E, 0x0C1A36, 0x4A78C0)),
    HairShade("pink", "Rose", "Pink", HairStyle.Colours(0xC0607E, 0x6A2A40, 0xF0A0B8)),
    HairShade("purple", "Violet", "Purple", HairStyle.Colours(0x5A2A7A, 0x2A1040, 0x9A6AC0)),
)

internal fun hairShade(id: String): HairShade? = HAIR_SHADES.firstOrNull { it.id == id }

private fun lum(rgb: Int): Float = 0.2126f * ((rgb shr 16) and 0xFF) + 0.7152f * ((rgb shr 8) and 0xFF) + 0.0722f * (rgb and 0xFF)

private fun mixRgb(a: Int, b: Int, t: Float): Int {
    var out = 0
    for (s in intArrayOf(16, 8, 0)) {
        val x = (a shr s) and 0xFF; val y = (b shr s) and 0xFF
        out = out or ((x + (y - x) * t.coerceIn(0f, 1f)).toInt().coerceIn(0, 255) shl s)
    }
    return out
}

private fun darker(rgb: Int, k: Float): Int = mixRgb(0, rgb, k)

/**
 * The face's own hair (painted into the head asset) in the colours [to]: every hair vertex keeps how light it was against the face's
 * colours [from] (the dark roots, the lighter tips and strands; the cover over the skin, in its alpha, is left as it was).
 */
internal fun recolourHair(head: HeadMesh, from: HairStyle.Colours, to: HairStyle.Colours): HeadMesh {
    val lb = max(lum(from.body), 1f)
    val kr = lum(from.root) / lb
    val kt = max(lum(from.tip) / lb, 1.05f)
    val paint = head.paint.copyOf()
    for (i in paint.indices) {
        val p = paint[i]
        val a = (p ushr 24) and 0xFF
        if (p == 0 || a == 0xFF) continue                     // not hair: the skin, or what is painted inside the head (eyes, mouth)
        val k = lum(p and 0xFFFFFF) / lb
        val rgb = if (k <= 1f) mixRgb(to.root, to.body, (k - kr) / max(1f - kr, 0.05f))
        else mixRgb(to.body, to.tip, (k - 1f) / (kt - 1f))
        paint[i] = (a shl 24) or rgb
    }
    return head.copyWith(paint = paint)
}

internal fun HeadMesh.copyWith(paint: IntArray = this.paint, hairSway: FloatArray? = this.hairSway) = HeadMesh(
    verts, normals, jaw, brow, lips, fade, faceGroup, faces, edges, landmarks, lipCentre, nHead, nFace, crown, bottom, paint, lid, lipMask,
    eyeFirst, eyeCount, eyeCentre, eyelidRim, mouthUpper, mouthLower, lockFirst, lockCount, lockRows, hairSway,
)
