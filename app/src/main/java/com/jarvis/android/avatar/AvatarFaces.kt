package com.jarvis.android.avatar

/** One of the heads the user can choose: its mesh in the assets, the colour of its eyebrows (they follow the hair), whether the fine
 * strands are drawn, and how the brows and the lashes are drawn (a finer brow and a longer lash read more feminine). */
internal data class AvatarFace(
    val label: String, val asset: String, val browColour: Int, val fibres: Boolean = true, val cartoon: Boolean = false,
    val browScale: Float = 1f, val lashScale: Float = 1f,
    /** An android: a silver porcelain skin with circuits etched in it, whatever skin tone is chosen (see AvatarRenderer.androidLook). */
    val androidLook: Boolean = false,
    /** A ring of light behind the head. */
    val halo: Boolean = false,
    /** The natural lip colour, from the skin (0) towards a rose (1), when no lip tone is chosen. */
    val lipTint: Float = 0.7f,
    /** A textured character (see CharacterMesh): the folder of its files in the assets; the head asset then only drives the animation. */
    val character: String? = null,
    /** Who made the character, shown under the choice (their licence asks for it). */
    val credit: String = "",
)

/**
 * The faces, in the order of the setting. Classique is a head scan, Léa and Marc are sculpted heads (see tools/avatar/export_head.py and
 * assets/avatar/NOTICE.txt), each with a hair style and a colour of its own.
 */
internal val BUILT_IN_FACES = listOf(
    AvatarFace("Classique", "avatar/head_mesh.bin", 0xFF34241C.toInt()),
    AvatarFace("Léa", "avatar/head_mesh_lea.bin", 0xFF1A1E27.toInt(), fibres = false, browScale = 0.65f, lashScale = 1.35f, androidLook = true, halo = true),
    AvatarFace("Marc", "avatar/head_mesh_marc.bin", 0xFF2B2928.toInt(), lipTint = 0.3f),
    // a drawn character, not a mesh: the file is only what gives the animation its object (see CartoonAvatar.kt)
    AvatarFace("Dessin animé", "avatar/head_mesh.bin", 0xFF1F1614.toInt(), fibres = false, cartoon = true),
)

/** The built-in faces, then the textured characters found in the assets (set once at start-up, see [CharacterCatalog]). */
@Volatile internal var characterFaces: List<AvatarFace> = emptyList()

internal val AVATAR_FACES: List<AvatarFace> get() = BUILT_IN_FACES + characterFaces

internal fun avatarFace(index: Int): AvatarFace = AVATAR_FACES.let { it[index.coerceIn(0, it.lastIndex)] }
