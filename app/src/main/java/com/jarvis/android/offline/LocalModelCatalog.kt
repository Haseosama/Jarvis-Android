package com.jarvis.android.offline

/*
 * The local models Jarvis can download by itself, so the user picks one in a list instead of fetching a file in the browser and
 * importing it (importing stays possible for anything else). Each is either a MediaPipe .task bundle or a LiteRT-LM .litertlm file, the two
 * formats LocalLlm.kt loads; the official ones come from Google's LiteRT community on Hugging Face, the uncensored ones are community
 * conversions (abliterated or fine-tuned so they do not refuse). Sizes (and the SHA-256 where Hugging Face shows it publicly) are those of
 * the published files, checked after the download so a cut or swapped file is never installed.
 *
 * Gemma's files are gated: Hugging Face only serves them to an account that accepted Google's licence on the model's page, so those
 * entries need that account's access token. The Qwen ones are Apache 2.0 and download with no account at all.
 */

internal data class LocalModelChoice(
    val id: String,
    val label: String,
    val description: String,
    val repo: String,
    val file: String,
    val bytes: Long,
    val sha256: String?,
    val needsHfToken: Boolean,
    val uncensored: Boolean = false,
) {
    val downloadUrl: String get() = "https://huggingface.co/$repo/resolve/main/$file?download=true"
    val pageUrl: String get() = "https://huggingface.co/$repo"

    /** Size for the list: "≈ 555 Mo" or "≈ 1,6 Go". */
    val sizeLabel: String get() =
        if (bytes >= 1_000_000_000L) "≈ " + String.format(java.util.Locale.FRANCE, "%.1f", bytes / 1e9) + " Go" else "≈ ${bytes / 1_000_000L} Mo"
}

internal val LOCAL_MODEL_CATALOG = listOf(
    LocalModelChoice(
        id = "gemma4-e2b",
        label = "Gemma 4 E2B (Google)",
        description = "Le plus capable de la liste, sans compte. Lourd : pour un téléphone récent (8 Go de mémoire conseillés).",
        repo = "litert-community/gemma-4-E2B-it-litert-lm",
        file = "gemma-4-E2B-it.litertlm",
        bytes = 2_588_147_712L,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        needsHfToken = false,
    ),
    LocalModelChoice(
        id = "gemma3-1b",
        label = "Gemma 3 1B (Google)",
        description = "Bon en français pour sa taille, et léger. Compte Hugging Face nécessaire (licence Gemma).",
        repo = "litert-community/Gemma3-1B-IT",
        file = "gemma3-1b-it-int4.task",
        bytes = 554_661_243L,
        sha256 = null,
        needsHfToken = true,
    ),
    LocalModelChoice(
        id = "qwen2.5-0.5b",
        label = "Qwen 2.5 0,5B (Alibaba)",
        description = "Libre, sans compte. Rapide et léger, réponses simples.",
        repo = "litert-community/Qwen2.5-0.5B-Instruct",
        file = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        bytes = 546_660_344L,
        sha256 = "e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2",
        needsHfToken = false,
    ),
    LocalModelChoice(
        id = "qwen2.5-1.5b",
        label = "Qwen 2.5 1,5B (Alibaba)",
        description = "Libre, sans compte. Plus malin mais plus lourd : pour un téléphone récent (6 Go de mémoire ou plus).",
        repo = "litert-community/Qwen2.5-1.5B-Instruct",
        file = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        bytes = 1_597_913_616L,
        sha256 = "8d867a7c93a6acf2892f08e0174e2f6f351ad256b7e3cfb6d6cd9c89794b42e0",
        needsHfToken = false,
    ),
    LocalModelChoice(
        id = "gemma3-270m",
        label = "Gemma 3 270M (Google)",
        description = "Tout petit et très rapide, réponses basiques. Compte Hugging Face nécessaire (licence Gemma).",
        repo = "litert-community/gemma-3-270m-it",
        file = "gemma3-270m-it-q8.task",
        bytes = 303_950_933L,
        sha256 = null,
        needsHfToken = true,
    ),
    LocalModelChoice(
        id = "gemma4-e2b-abliterated",
        label = "Gemma 4 E2B non censuré (abliterated)",
        description = "Gemma 4 dont le refus a été retiré, Conversion de DuoNeural, sans compte ; peu testée. Téléphone récent.",
        repo = "DuoNeural/Gemma-4-Abliterated-LiteRT",
        file = "Gemma-4-E2B-Abliterated.litertlm",
        bytes = 2_556_215_296L,
        sha256 = "e8c197596ccf8ec20553eb467675291a30212f3a27a52f75e37f9c9b1b1c6db5",
        needsHfToken = false,
        uncensored = true,
    ),
)

internal fun localModelChoice(id: String?): LocalModelChoice? = LOCAL_MODEL_CATALOG.firstOrNull { it.id == id }

/** The name the model is kept under once installed, which tells LocalLlm.kt which engine loads it. */
internal val LocalModelChoice.installedName: String get() = if (file.endsWith(".litertlm")) LOCAL_MODEL_LM_FILE else LOCAL_MODEL_FILE

/** Why a finished download is refused, or null when it is the expected file: exact size, the format's header, and the hash when known. */
internal fun checkDownloadedModel(choice: LocalModelChoice, size: Long, firstBytes: ByteArray, sha256: () -> String?): String? {
    if (size != choice.bytes) return "Fichier reçu incomplet ou inattendu (${size / 1_000_000L} Mo au lieu de ${choice.bytes / 1_000_000L})."
    if (!looksLikeModelFile(size, firstBytes) || modelFileName(firstBytes) != choice.installedName) return "Le fichier reçu n’est pas le modèle attendu."
    val expected = choice.sha256 ?: return null
    val actual = sha256() ?: return "Fichier reçu illisible."
    return if (actual.equals(expected, ignoreCase = true)) null else "Le fichier reçu est corrompu (empreinte différente)."
}
