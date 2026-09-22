package com.jarvis.android.offline

import android.content.Context
import java.io.File

/*
 * The local model, for a real offline conversation instead of the fixed commands alone (see OfflineIntents.kt). Gemma's own weights are
 * gated by Google on Hugging Face (a licence the user accepts there): the app cannot fetch them itself the way it fetches the open
 * wake-word models, so the user downloads the .task file in their own browser once they have accepted that licence, and imports it here —
 * the same way a custom wake-word model, trained outside the app, is imported. Nothing about the model or its use ever leaves the phone.
 */

internal const val LOCAL_MODEL_DIR = "local_llm"
internal const val LOCAL_MODEL_FILE = "model.task"
internal const val LOCAL_MODEL_MIN_BYTES = 40_000_000L        // the smallest usable Gemma .task bundles are tens of MB
internal const val LOCAL_MODEL_MAX_BYTES = 4_500_000_000L     // a generous ceiling: nothing this app would ask for is bigger

/** A MediaPipe .task bundle is a zip archive (model, tokenizer, metadata together): a plain size check would accept an unrelated file. */
internal fun looksLikeTaskBundle(size: Long, firstBytes: ByteArray): Boolean =
    size in LOCAL_MODEL_MIN_BYTES..LOCAL_MODEL_MAX_BYTES && firstBytes.size >= 2 && firstBytes[0] == 'P'.code.toByte() && firstBytes[1] == 'K'.code.toByte()

internal class LocalModelStore(private val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, LOCAL_MODEL_DIR))

    val file: File get() = File(dir, LOCAL_MODEL_FILE)

    fun installed(): Boolean = file.isFile && file.length() >= LOCAL_MODEL_MIN_BYTES

    /** The installed model's size in whole MB, or null when there is none. */
    fun sizeMb(): Long? = file.takeIf { it.isFile }?.length()?.let { it / 1_000_000L }

    /** Copies [source] in as the model, after checking it looks like a real .task bundle. Null on success. */
    fun import(source: File): String? {
        val size = source.length()
        val head = try { source.inputStream().use { it.readNBytes(2) } } catch (_: java.io.IOException) { return "Fichier illisible." }
        if (!looksLikeTaskBundle(size, head)) {
            return "Ce fichier ne ressemble pas à un modèle .task (attendu : quelques centaines de Mo, un fichier zip)."
        }
        return try {
            dir.mkdirs()
            val tmp = File(dir, "$LOCAL_MODEL_FILE.tmp")
            source.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(file)) { file.writeBytes(tmp.readBytes()); tmp.delete() }
            null
        } catch (_: java.io.IOException) {
            "Copie du modèle impossible (place manquante sur le téléphone ?)."
        }
    }

    fun remove() {
        dir.deleteRecursively()
    }
}
