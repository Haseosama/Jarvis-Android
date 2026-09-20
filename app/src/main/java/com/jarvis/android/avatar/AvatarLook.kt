package com.jarvis.android.avatar

/** How the head is drawn: a cyber android (blue skin, neon circuits), a lit skin-toned head with real eyes and hair, or the original blue hologram. */
internal enum class AvatarStyle { CYBER, REALISTIC, HOLOGRAPHIC }

/** The chosen look. [skin], [hair] and [eyes] index the palettes below and only matter for the realistic style. */
internal data class AvatarLook(
    val style: AvatarStyle = AvatarStyle.CYBER,
    val skin: Int = 1,
    val hair: Int = 1,
    val eyes: Int = 0,
) {
    val skinColor: Int get() = SKIN_TONES[skin.coerceIn(0, SKIN_TONES.lastIndex)]
    val hairColor: Int get() = HAIR_COLORS[hair.coerceIn(0, HAIR_COLORS.lastIndex)]
    val eyeColor: Int get() = EYE_COLORS[eyes.coerceIn(0, EYE_COLORS.lastIndex)]
}

/** Skin albedo, light to dark (ARGB). */
internal val SKIN_TONES = intArrayOf(0xFFF2C9AE.toInt(), 0xFFDDAA84.toInt(), 0xFFB98259.toInt(), 0xFF7D4B33.toInt())
internal val SKIN_NAMES = listOf("Clair", "Moyen", "Mat", "Foncé")

/** Hair colours: black, brown, chestnut, blond, red, grey. */
internal val HAIR_COLORS = intArrayOf(0xFF23201F.toInt(), 0xFF4B3323.toInt(), 0xFF7B5433.toInt(), 0xFFC8A45E.toInt(), 0xFF9C4A26.toInt(), 0xFFA9A9A8.toInt())
internal val HAIR_NAMES = listOf("Noir", "Brun", "Châtain", "Blond", "Roux", "Gris")

/** Iris colours: brown, blue, green, hazel. */
internal val EYE_COLORS = intArrayOf(0xFF5B3B22.toInt(), 0xFF4F87B9.toInt(), 0xFF5B8B57.toInt(), 0xFF8B6B3B.toInt())
internal val EYE_NAMES = listOf("Marron", "Bleu", "Vert", "Noisette")
