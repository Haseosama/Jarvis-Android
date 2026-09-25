package com.jarvis.android.filemanager

import com.jarvis.android.core.UndoEntry
import com.jarvis.android.offline.normalize
import java.util.Locale

/** One file or folder as the manager sees it. */
internal data class FileNode(val name: String, val isDirectory: Boolean, val size: Long = 0, val modified: Long = 0)

/**
 * The folder tree the manager works in, addressed by path segments below its root. The real
 * implementation sits on the Storage Access Framework (only the folder the user granted); tests use a
 * tree in memory. Nothing outside this tree can ever be reached.
 */
internal interface FileTree {
    fun list(dir: List<String>): List<FileNode>?
    fun stat(path: List<String>): FileNode?
    fun read(path: List<String>, maxBytes: Int): ByteArray?
    fun write(path: List<String>, bytes: ByteArray): Boolean
    fun mkdir(path: List<String>): Boolean
    fun delete(path: List<String>): Boolean
    fun rename(path: List<String>, newName: String): Boolean
    fun move(path: List<String>, destDir: List<String>): Boolean
    fun copy(path: List<String>, destDir: List<String>): Boolean
}

internal class FileResult(val message: String, val undo: UndoEntry? = null)

internal const val TRASH_DIR = ".jarvis_trash"
internal const val MAX_LISTED = 100
internal const val MAX_READ_CHARS = 4_000
internal const val MAX_WRITE_CHARS = 200_000
internal const val MAX_VISITED = 5_000
internal const val MAX_DEPTH = 6
internal const val MAX_CONTENT_SEARCH_READS = 300     // files actually opened and read, not just listed
internal const val MAX_CONTENT_SEARCH_HITS = 20
internal const val MAX_CONTENT_SEARCH_FILE_BYTES = 300_000  // a file bigger than this is skipped rather than read in full
internal const val CONTENT_SNIPPET_RADIUS = 60         // characters kept on each side of a match

/** Extensions never worth opening for a text search: the read would just be wasted decoding noise. */
internal val NOT_TEXT_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "mp3", "mp4", "wav", "m4a", "aac", "ogg", "flac",
    "mov", "avi", "mkv", "webm", "zip", "rar", "7z", "apk", "pdf", "exe", "dll", "so", "bin", "ttf", "otf",
)

/** Path text to segments, or null when it is unsafe ("..", ".", empty segments, backslashes, a trash path). */
internal fun parsePath(text: String): List<String>? {
    val cleaned = text.trim().trim('/')
    if (cleaned.isEmpty()) return emptyList()
    if ('\\' in cleaned || '\u0000' in cleaned) return null
    val parts = cleaned.split('/')
    if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
    return parts
}

internal fun joinPath(path: List<String>): String = if (path.isEmpty()) "/" else "/" + path.joinToString("/")

/** The same "too many replacement characters" heuristic `read` uses to refuse a binary file. */
internal fun looksLikeText(bytes: ByteArray): Boolean {
    if (bytes.isEmpty()) return true
    val text = bytes.toString(Charsets.UTF_8)
    return text.count { it == '�' } <= 5
}

/**
 * The first case-insensitive match of [query] in [text], as up to [radius] characters of context on each side
 * (an ellipsis where it was cut, runs of whitespace collapsed for a readable one-line result), or null when
 * [query] does not occur. Plain substring matching, not the accent-folding `normalize()` uses elsewhere: a
 * file-content search is closer to grep than to a spoken command, and folding would break mapping the match
 * back to a real slice of the original text.
 */
internal fun contentSnippet(text: String, query: String, radius: Int = CONTENT_SNIPPET_RADIUS): String? {
    if (query.isEmpty()) return null
    val at = text.indexOf(query, ignoreCase = true)
    if (at < 0) return null
    val start = (at - radius).coerceAtLeast(0)
    val end = (at + query.length + radius).coerceAtMost(text.length)
    val core = text.substring(start, end).replace(Regex("\\s+"), " ").trim()
    return (if (start > 0) "…" else "") + core + (if (end < text.length) "…" else "")
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.FRANCE, "%.1f Go", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.FRANCE, "%.1f Mo", bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} Ko"
    else -> "$bytes o"
}

private val CATEGORIES = mapOf(
    "Images" to setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg"),
    "Documents" to setOf("pdf", "doc", "docx", "txt", "md", "rtf", "odt", "xls", "xlsx", "csv", "ppt", "pptx"),
    "Audio" to setOf("mp3", "wav", "aac", "flac", "ogg", "m4a"),
    "Videos" to setOf("mp4", "mkv", "avi", "mov", "webm", "3gp"),
    "Archives" to setOf("zip", "rar", "7z", "tar", "gz"),
    "Apps" to setOf("apk", "aab"),
)

/** The folder a file of this name is sorted into by "organize". */
internal fun categoryFor(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return CATEGORIES.entries.firstOrNull { ext in it.value }?.key ?: "Autres"
}

/** The file actions of the assistant, on a [FileTree]. Every change returns how to take it back. */
internal class FileManager(private val tree: FileTree, private val clock: () -> Long = System::currentTimeMillis) {

    private fun bad(path: String) = FileResult("Chemin invalide : « $path ». Utilisez un chemin relatif au dossier de travail, sans « .. ».")

    private fun undoOf(label: String, block: () -> String) = UndoEntry(label) { block() }

    fun list(pathText: String): FileResult {
        val path = parsePath(pathText) ?: return bad(pathText)
        val nodes = tree.list(path)?.filter { it.name != TRASH_DIR }
            ?: return FileResult("Dossier introuvable : ${joinPath(path)}.")
        if (nodes.isEmpty()) return FileResult("${joinPath(path)} est vide.")
        val sorted = nodes.sortedWith(compareByDescending<FileNode> { it.isDirectory }.thenBy { it.name.lowercase() })
        val shown = sorted.take(MAX_LISTED).joinToString("\n") {
            if (it.isDirectory) "📁 ${it.name}/" else "📄 ${it.name} (${formatSize(it.size)})"
        }
        val more = if (sorted.size > MAX_LISTED) "\n… ${sorted.size - MAX_LISTED} autres éléments." else ""
        return FileResult("${joinPath(path)} : ${sorted.size} élément(s)\n$shown$more")
    }

    fun info(pathText: String): FileResult {
        val path = parsePath(pathText) ?: return bad(pathText)
        val node = if (path.isEmpty()) FileNode("/", true) else tree.stat(path)
            ?: return FileResult("Introuvable : ${joinPath(path)}.")
        val kind = if (node.isDirectory) "dossier" else "fichier, ${formatSize(node.size)}"
        return FileResult("${joinPath(path)} : $kind.")
    }

    fun read(pathText: String, maxChars: Int = MAX_READ_CHARS): FileResult {
        val path = parsePath(pathText)?.takeIf { it.isNotEmpty() } ?: return bad(pathText)
        val node = tree.stat(path) ?: return FileResult("Fichier introuvable : ${joinPath(path)}.")
        if (node.isDirectory) return FileResult("${joinPath(path)} est un dossier : utilisez list.")
        val limit = maxChars.coerceIn(1, MAX_READ_CHARS)
        val bytes = tree.read(path, limit * 4) ?: return FileResult("Lecture impossible : ${joinPath(path)}.")
        val text = bytes.toString(Charsets.UTF_8)
        if (text.count { it == '\uFFFD' } > 5) return FileResult("${joinPath(path)} n’est pas un fichier texte lisible (${formatSize(node.size)}).")
        val cut = text.take(limit)
        return FileResult(cut + if (text.length > limit || node.size > bytes.size) "\n… (début du fichier seulement)" else "")
    }

    fun createFile(pathText: String, content: String): FileResult {
        val path = parsePath(pathText)?.takeIf { it.isNotEmpty() } ?: return bad(pathText)
        if (content.length > MAX_WRITE_CHARS) return FileResult("Contenu trop long ($MAX_WRITE_CHARS caractères maximum).")
        if (tree.stat(path) != null) return FileResult("${joinPath(path)} existe déjà : utilisez write pour le modifier.")
        if (path.size > 1 && tree.stat(path.dropLast(1)) == null && !tree.mkdir(path.dropLast(1))) return FileResult("Impossible de créer le dossier parent.")
        if (!tree.write(path, content.toByteArray())) return FileResult("Création impossible : ${joinPath(path)}.")
        return FileResult("Fichier créé : ${joinPath(path)}.", undoOf("création de ${path.last()}") { if (tree.delete(path)) "Fichier supprimé : ${joinPath(path)}." else "Impossible de supprimer ${joinPath(path)}." })
    }

    fun createFolder(pathText: String): FileResult {
        val path = parsePath(pathText)?.takeIf { it.isNotEmpty() } ?: return bad(pathText)
        if (tree.stat(path) != null) return FileResult("${joinPath(path)} existe déjà.")
        if (!tree.mkdir(path)) return FileResult("Création impossible : ${joinPath(path)}.")
        return FileResult("Dossier créé : ${joinPath(path)}.", undoOf("création du dossier ${path.last()}") { if (tree.delete(path)) "Dossier supprimé." else "Impossible de supprimer ${joinPath(path)}." })
    }

    /** Writes [content]; an existing file is kept for undo. Callers must have asked for confirmation before overwriting. */
    fun write(pathText: String, content: String, append: Boolean): FileResult {
        val path = parsePath(pathText)?.takeIf { it.isNotEmpty() } ?: return bad(pathText)
        val existing = tree.stat(path)
        if (existing?.isDirectory == true) return FileResult("${joinPath(path)} est un dossier.")
        val previous = if (existing != null) tree.read(path, MAX_WRITE_CHARS * 4) else null
        if (existing != null && (previous == null || existing.size > previous.size)) {
            return FileResult("Fichier trop gros pour être modifié en toute sécurité (annulation impossible).")
        }
        val text = (if (append && previous != null) previous.toString(Charsets.UTF_8) else "") + content
        if (text.length > MAX_WRITE_CHARS) return FileResult("Contenu trop long ($MAX_WRITE_CHARS caractères maximum).")
        if (!tree.write(path, text.toByteArray())) return FileResult("Écriture impossible : ${joinPath(path)}.")
        val undo = undoOf("écriture de ${path.last()}") {
            if (previous == null) { tree.delete(path); "Fichier supprimé (il n’existait pas avant)." }
            else if (tree.write(path, previous)) "Contenu précédent de ${joinPath(path)} rétabli." else "Impossible de rétablir ${joinPath(path)}."
        }
        return FileResult(if (existing == null) "Fichier créé : ${joinPath(path)}." else "Fichier modifié : ${joinPath(path)}.", undo)
    }

    fun rename(pathText: String, newName: String): FileResult {
        val path = parsePath(pathText)?.takeIf { it.isNotEmpty() } ?: return bad(pathText)
        val name = newName.trim()
        if (name.isEmpty() || '/' in name || name == "." || name == "..") return FileResult("Nouveau nom invalide.")
        if (tree.stat(path) == null) return FileResult("Introuvable : ${joinPath(path)}.")
        val target = path.dropLast(1) + name
        if (tree.stat(target) != null) return FileResult("${joinPath(target)} existe déjà.")
        if (!tree.rename(path, name)) return FileResult("Renommage impossible.")
        return FileResult("Renommé : ${joinPath(path)} → $name.", undoOf("renommage") { if (tree.rename(target, path.last())) "Nom rétabli : ${path.last()}." else "Impossible de rétablir le nom." })
    }

    fun move(pathText: String, destText: String): FileResult {
        val path = parsePath(pathText)?.takeIf { it.isNotEmpty() } ?: return bad(pathText)
        val dest = parsePath(destText) ?: return bad(destText)
        if (tree.stat(path) == null) return FileResult("Introuvable : ${joinPath(path)}.")
        if (dest.isNotEmpty() && tree.stat(dest)?.isDirectory != true) {
            if (tree.stat(dest) != null || !tree.mkdir(dest)) return FileResult("Destination invalide : ${joinPath(dest)}.")
        }
        if (dest.take(path.size) == path) return FileResult("Impossible de déplacer un dossier dans lui-même.")
        val target = dest + path.last()
        if (tree.stat(target) != null) return FileResult("${joinPath(target)} existe déjà.")
        if (!tree.move(path, dest)) return FileResult("Déplacement impossible.")
        return FileResult("Déplacé : ${joinPath(path)} → ${joinPath(dest)}.", undoOf("déplacement") {
            if (tree.move(target, path.dropLast(1))) "Remis à sa place : ${joinPath(path)}." else "Impossible de remettre ${joinPath(path)}."
        })
    }

    fun copy(pathText: String, destText: String): FileResult {
        val path = parsePath(pathText)?.takeIf { it.isNotEmpty() } ?: return bad(pathText)
        val dest = parsePath(destText) ?: return bad(destText)
        if (tree.stat(path) == null) return FileResult("Introuvable : ${joinPath(path)}.")
        if (dest.isNotEmpty() && tree.stat(dest)?.isDirectory != true && !tree.mkdir(dest)) return FileResult("Destination invalide : ${joinPath(dest)}.")
        val target = dest + path.last()
        if (tree.stat(target) != null) return FileResult("${joinPath(target)} existe déjà.")
        if (!tree.copy(path, dest)) return FileResult("Copie impossible.")
        return FileResult("Copié : ${joinPath(path)} → ${joinPath(dest)}.", undoOf("copie") { if (tree.delete(target)) "Copie supprimée." else "Impossible de supprimer la copie." })
    }

    /** Moves to the trash folder inside the tree instead of deleting for good; undo brings it back. */
    fun delete(pathText: String): FileResult {
        val path = parsePath(pathText)?.takeIf { it.isNotEmpty() && it.first() != TRASH_DIR } ?: return bad(pathText)
        if (tree.stat(path) == null) return FileResult("Introuvable : ${joinPath(path)}.")
        if (tree.stat(listOf(TRASH_DIR)) == null && !tree.mkdir(listOf(TRASH_DIR))) return FileResult("Corbeille indisponible : rien n’a été supprimé.")
        val original = path.last()
        val trashName = "${clock()}_$original"
        if (!tree.rename(path, trashName)) return FileResult("Suppression impossible.")
        val renamed = path.dropLast(1) + trashName
        if (!tree.move(renamed, listOf(TRASH_DIR))) {
            tree.rename(renamed, original)
            return FileResult("Suppression impossible : rien n’a changé.")
        }
        return FileResult(
            "Mis à la corbeille : ${joinPath(path)} (récupérable dans $TRASH_DIR ; « annuler » le remet en place).",
            undoOf("suppression de $original") {
                val back = path.dropLast(1)
                val inTrash = listOf(TRASH_DIR, trashName)
                when {
                    tree.stat(path) != null -> "${joinPath(path)} existe déjà : rien n’a été remis."
                    !tree.move(inTrash, back) -> "Impossible de remettre ${joinPath(path)}."
                    !tree.rename(back + trashName, original) -> "Remis sous le nom « $trashName »."
                    else -> "Remis en place : ${joinPath(path)}."
                }
            },
        )
    }

    fun find(query: String, extension: String, dirText: String): FileResult {
        val dir = parsePath(dirText) ?: return bad(dirText)
        val wanted = query.trim().lowercase()
        val ext = extension.trim().trimStart('.').lowercase()
        if (wanted.isEmpty() && ext.isEmpty()) return FileResult("Indiquez un nom (query) ou une extension.")
        val hits = mutableListOf<String>()
        var visited = 0
        fun walk(current: List<String>, depth: Int) {
            if (depth > MAX_DEPTH || visited > MAX_VISITED || hits.size >= 50) return
            for (node in tree.list(current).orEmpty()) {
                if (node.name == TRASH_DIR) continue
                visited++
                val path = current + node.name
                val nameOk = wanted.isEmpty() || node.name.lowercase().contains(wanted)
                val extOk = ext.isEmpty() || (!node.isDirectory && node.name.substringAfterLast('.', "").lowercase() == ext)
                if (nameOk && extOk) hits += joinPath(path) + if (node.isDirectory) "/" else " (${formatSize(node.size)})"
                if (node.isDirectory) walk(path, depth + 1)
            }
        }
        walk(dir, 0)
        return FileResult(if (hits.isEmpty()) "Aucun résultat." else "${hits.size} résultat(s) :\n" + hits.joinToString("\n"))
    }

    /**
     * Searches the TEXT of files, not just their names (`find` only matches the name). Binary-looking extensions
     * and files over [MAX_CONTENT_SEARCH_FILE_BYTES] are skipped without opening them; at most
     * [MAX_CONTENT_SEARCH_READS] files are actually read, whichever comes first among size, depth and hit limits.
     */
    fun searchContent(query: String, dirText: String): FileResult {
        val dir = parsePath(dirText) ?: return bad(dirText)
        val q = query.trim()
        if (q.isEmpty()) return FileResult("Indiquez le texte à chercher.")
        if (q.length > 200) return FileResult("Texte à chercher trop long (200 caractères maximum).")
        val hits = mutableListOf<String>()
        var visited = 0
        var opened = 0
        fun walk(current: List<String>, depth: Int) {
            if (depth > MAX_DEPTH) return
            for (node in tree.list(current).orEmpty()) {
                if (visited > MAX_VISITED || opened >= MAX_CONTENT_SEARCH_READS || hits.size >= MAX_CONTENT_SEARCH_HITS) return
                if (node.name == TRASH_DIR) continue
                visited++
                val path = current + node.name
                if (node.isDirectory) {
                    walk(path, depth + 1)
                    continue
                }
                val ext = node.name.substringAfterLast('.', "").lowercase()
                if (ext in NOT_TEXT_EXTENSIONS || node.size !in 1..MAX_CONTENT_SEARCH_FILE_BYTES.toLong()) continue
                opened++
                val bytes = tree.read(path, MAX_CONTENT_SEARCH_FILE_BYTES) ?: continue
                if (!looksLikeText(bytes)) continue
                contentSnippet(bytes.toString(Charsets.UTF_8), q)?.let { snippet -> hits += "${joinPath(path)} : $snippet" }
            }
        }
        walk(dir, 0)
        return FileResult(if (hits.isEmpty()) "Aucun résultat." else "${hits.size} résultat(s) :\n" + hits.joinToString("\n"))
    }

    fun largest(dirText: String, count: Int): FileResult {
        val dir = parsePath(dirText) ?: return bad(dirText)
        val files = mutableListOf<Pair<String, Long>>()
        var visited = 0
        fun walk(current: List<String>, depth: Int) {
            if (depth > MAX_DEPTH || visited > MAX_VISITED) return
            for (node in tree.list(current).orEmpty()) {
                if (node.name == TRASH_DIR) continue
                visited++
                if (node.isDirectory) walk(current + node.name, depth + 1) else files += joinPath(current + node.name) to node.size
            }
        }
        walk(dir, 0)
        val top = files.sortedByDescending { it.second }.take(count.coerceIn(1, 20))
        return FileResult(if (top.isEmpty()) "Aucun fichier." else top.joinToString("\n") { "${it.first} (${formatSize(it.second)})" })
    }

    fun usage(dirText: String): FileResult {
        val dir = parsePath(dirText) ?: return bad(dirText)
        var total = 0L
        var files = 0
        var visited = 0
        fun walk(current: List<String>, depth: Int) {
            if (depth > MAX_DEPTH || visited > MAX_VISITED) return
            for (node in tree.list(current).orEmpty()) {
                visited++
                if (node.isDirectory) walk(current + node.name, depth + 1) else { total += node.size; files++ }
            }
        }
        walk(dir, 0)
        val partial = if (visited > MAX_VISITED) " (comptage partiel : dossier très grand)" else ""
        return FileResult("${joinPath(dir)} : $files fichier(s), ${formatSize(total)}$partial.")
    }

    /** What "organize" would do to the files directly inside [dirText]: (file, category). */
    fun organizePlan(dirText: String): List<Pair<String, String>>? {
        val dir = parsePath(dirText) ?: return null
        return tree.list(dir)?.filter { !it.isDirectory }?.map { it.name to categoryFor(it.name) }
    }

    /** Sorts the files directly inside the folder into Images/Documents/Audio/... subfolders. */
    fun organize(dirText: String): FileResult {
        val dir = parsePath(dirText) ?: return bad(dirText)
        val plan = organizePlan(dirText) ?: return FileResult("Dossier introuvable : ${joinPath(dir)}.")
        if (plan.isEmpty()) return FileResult("Aucun fichier à ranger dans ${joinPath(dir)}.")
        val moved = mutableListOf<Pair<List<String>, List<String>>>() // from, to (full paths)
        var skipped = 0
        for ((name, category) in plan) {
            val from = dir + name
            val destDir = dir + category
            val to = destDir + name
            if (tree.stat(to) != null) { skipped++; continue }
            if (tree.stat(destDir) == null && !tree.mkdir(destDir)) { skipped++; continue }
            if (tree.move(from, destDir)) moved += from to to else skipped++
        }
        if (moved.isEmpty()) return FileResult("Rien n’a pu être rangé ($skipped ignoré(s)).")
        val undo = undoOf("rangement de ${joinPath(dir)}") {
            var restored = 0
            for ((from, to) in moved.asReversed()) if (tree.move(to, from.dropLast(1))) restored++
            "$restored fichier(s) remis à leur place."
        }
        val summary = plan.groupBy({ it.second }, { it.first }).entries.joinToString(", ") { "${it.key} : ${it.value.size}" }
        return FileResult("${moved.size} fichier(s) rangé(s) dans ${joinPath(dir)} ($summary)." + if (skipped > 0) " $skipped ignoré(s)." else "", undo)
    }
}
