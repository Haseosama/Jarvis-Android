package com.jarvis.android.avatar

/** One of the heads the user can choose: its mesh in the assets, the colour of its eyebrows (they follow the hair), and whether the fine strands are drawn. */
internal data class AvatarFace(val label: String, val asset: String, val browColour: Int, val fibres: Boolean = true)

/**
 * The faces, in the order of the setting. They all come from the same scan (see tools/avatar/export_head.py): the others are that scan
 * reshaped (jaw, chin, nose, brows, eyes), with a hair style and a colour of their own.
 */
internal val AVATAR_FACES = listOf(
    AvatarFace("Classique", "avatar/head_mesh.bin", 0xFF34241C.toInt()),
    AvatarFace("Léa", "avatar/head_mesh_lea.bin", 0xFF40201A.toInt(), fibres = false),
    AvatarFace("Marc", "avatar/head_mesh_marc.bin", 0xFF2B2928.toInt()),
)

internal fun avatarFace(index: Int): AvatarFace = AVATAR_FACES[index.coerceIn(0, AVATAR_FACES.lastIndex)]
