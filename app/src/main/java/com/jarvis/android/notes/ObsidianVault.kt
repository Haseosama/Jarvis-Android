package com.jarvis.android.notes

import com.jarvis.android.filemanager.FileTree
import com.jarvis.android.text.normalize
import java.time.LocalDate

/**
 * An Obsidian vault: a folder of Markdown notes (with Obsidian's own settings in `.obsidian`, left alone). Notes are found by their
 * title (the file name without `.md`, case and accents aside), searched by their words, read, added to, created. Nothing is deleted or
 * renamed: Obsidian keeps its links by those names.
 */
internal class ObsidianVault(private val tree: FileTree, private val today: () -> LocalDate = { LocalDate.now() }) {
    class Note(val path: List<String>, val title: String, val modified: Long)

    /** Every note of the vault (at most [MAX_NOTES], hidden folders such as .obsidian and .trash left out). */
    fun notes(): List<Note> {
        val out = ArrayList<Note>()
        fun walk(dir: List<String>, depth: Int) {
            if (depth > MAX_DEPTH || out.size >= MAX_NOTES) return
            for (n in tree.list(dir).orEmpty()) {
                if (n.name.startsWith(".")) continue
                if (n.isDirectory) walk(dir + n.name, depth + 1)
                else if (n.name.endsWith(".md", ignoreCase = true)) out += Note(dir + n.name, n.name.dropLast(3), n.modified)
                if (out.size >= MAX_NOTES) return
            }
        }
        walk(emptyList(), 0)
        return out
    }

    /** The note titled [title]: the exact title first, then one that starts with it, then one that contains it. */
    fun find(title: String): Note? {
        val t = normalize(title.removeSuffix(".md"))
        if (t.isEmpty()) return null
        val all = notes()
        return all.firstOrNull { normalize(it.title) == t } ?: all.firstOrNull { normalize(it.title).startsWith(t) }
            ?: all.firstOrNull { normalize(it.title).contains(t) }
    }

    fun text(note: Note): String = tree.read(note.path, MAX_NOTE_BYTES)?.toString(Charsets.UTF_8).orEmpty()

    /** Notes holding every word of [query], in their title or their text; a title match first. Each with a line around the match. */
    fun search(query: String): List<Pair<Note, String>> {
        val words = normalize(query).split(' ').filter { it.length >= 2 }
        if (words.isEmpty()) return emptyList()
        val hits = ArrayList<Triple<Note, String, Int>>()
        var read = 0
        for (n in notes()) {
            val title = normalize(n.title)
            if (words.all { it in title }) { hits += Triple(n, "", 2); continue }
            if (read++ >= MAX_SEARCH_READS) continue
            val body = text(n)
            val flat = normalize(body)
            if (words.all { it in flat }) {
                val line = body.lines().firstOrNull { l -> normalize(l).let { nl -> words.any { it in nl } } }.orEmpty().trim()
                hits += Triple(n, line.take(160), 1)
            }
        }
        return hits.sortedWith(compareByDescending<Triple<Note, String, Int>> { it.third }.thenByDescending { it.first.modified })
            .take(MAX_HITS).map { it.first to it.second }
    }

    /** The most recently changed notes. */
    fun recent(count: Int = 10): List<Note> = notes().sortedByDescending { it.modified }.take(count)

    /** Adds [text] at the end of the note titled [title], or of today's daily note (YYYY-MM-DD.md, created if needed) when [title] is
     * blank. Returns the note's title, or null when the note does not exist (a missing daily note is created, another one is not). */
    fun append(title: String, text: String): String? {
        val note = if (title.isBlank()) {
            val name = today().toString()
            find(name)?.takeIf { normalize(it.title) == normalize(name) } ?: run {
                if (!tree.write(listOf("$name.md"), "# $name\n".toByteArray())) return null
                Note(listOf("$name.md"), name, 0L)
            }
        } else {
            find(title) ?: return null
        }
        val before = text(note)
        val glue = if (before.isEmpty() || before.endsWith("\n")) "" else "\n"
        return if (tree.write(note.path, (before + glue + text.trim() + "\n").toByteArray())) note.title else null
    }

    /** A new note [title] (in [folder], made if needed) holding [text]; null when one with that title already exists or it cannot be written. */
    fun create(title: String, text: String, folder: String = ""): Note? {
        val clean = title.trim().replace(Regex("[\\\\/:*?\"<>|#^\\[\\]]"), " ").replace(Regex("\\s+"), " ").trim().take(120)
        if (clean.isEmpty()) return null
        if (notes().any { normalize(it.title) == normalize(clean) }) return null
        val dir = folder.split('/').map { it.trim() }.filter { it.isNotEmpty() && it != ".." && !it.startsWith(".") }
        if (dir.isNotEmpty() && tree.stat(dir) == null && !tree.mkdir(dir)) return null
        val path = dir + "$clean.md"
        return if (tree.write(path, (text.trim() + "\n").toByteArray())) Note(path, clean, 0L) else null
    }

    companion object {
        const val MAX_NOTES = 3_000
        const val MAX_DEPTH = 8
        const val MAX_NOTE_BYTES = 400_000
        const val MAX_SEARCH_READS = 600
        const val MAX_HITS = 8
    }
}
