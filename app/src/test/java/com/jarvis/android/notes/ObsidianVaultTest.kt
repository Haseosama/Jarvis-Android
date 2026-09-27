package com.jarvis.android.notes

import com.jarvis.android.actions.obsidianAction
import com.jarvis.android.filemanager.FileNode
import com.jarvis.android.filemanager.FileTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** A vault in memory: files by path, folders implied. */
private class Mem : FileTree {
    val files = LinkedHashMap<List<String>, ByteArray>()
    val times = HashMap<List<String>, Long>()
    var clock = 0L
    val dirs = HashSet<List<String>>()
    fun put(path: String, text: String) { val p = path.split('/'); files[p] = text.toByteArray(); times[p] = ++clock }
    override fun list(dir: List<String>): List<FileNode>? {
        val kids = LinkedHashMap<String, FileNode>()
        for ((p, b) in files) if (p.size > dir.size && p.subList(0, dir.size) == dir) {
            val name = p[dir.size]
            kids[name] = if (p.size == dir.size + 1) FileNode(name, false, b.size.toLong(), times[p] ?: 0) else FileNode(name, true)
        }
        for (d in dirs) if (d.size == dir.size + 1 && d.subList(0, dir.size) == dir) kids.putIfAbsent(d.last(), FileNode(d.last(), true))
        return if (dir.isEmpty() || kids.isNotEmpty() || dir in dirs) kids.values.toList() else null
    }
    override fun stat(path: List<String>) = files[path]?.let { FileNode(path.last(), false, it.size.toLong()) }
        ?: if (path in dirs || files.keys.any { it.size > path.size && it.subList(0, path.size) == path }) FileNode(path.last(), true) else null
    override fun read(path: List<String>, maxBytes: Int) = files[path]
    override fun write(path: List<String>, bytes: ByteArray): Boolean { files[path] = bytes; times[path] = ++clock; return true }
    override fun mkdir(path: List<String>) = dirs.add(path).let { true }
    override fun delete(path: List<String>) = false
    override fun rename(path: List<String>, newName: String) = false
    override fun move(path: List<String>, destDir: List<String>) = false
    override fun copy(path: List<String>, destDir: List<String>) = false
}

class ObsidianVaultTest {
    private val mem = Mem().apply {
        put(".obsidian/app.json", "{}")
        put("Projets/Jarvis.md", "# Jarvis\nIdée : une voix féminine.\nÀ faire : les cheveux.")
        put("Courses.md", "- pain\n- lait")
        put("Journal/2026-09-26.md", "Rien.")
        put(".trash/Ancienne.md", "jarvis")
    }
    private val vault = ObsidianVault(mem) { LocalDate.of(2026, 9, 28) }

    @Test fun `the notes are the Markdown files, Obsidian's own folders left out`() {
        assertEquals(setOf("Jarvis", "Courses", "2026-09-26"), vault.notes().map { it.title }.toSet())
    }

    @Test fun `a note is found by its title, case and accents aside, or by the start of it`() {
        assertEquals("Courses", vault.find("courses")?.title)
        assertEquals("Jarvis", vault.find("jar")?.title)
        assertNull(vault.find("vacances"))
    }

    @Test fun `search finds the words in titles and texts, the title first`() {
        val hits = vault.search("voix feminine")
        assertEquals("Jarvis", hits.single().first.title)
        assertTrue(hits.single().second.contains("voix"))
        assertEquals("Jarvis", vault.search("jarvis").first().first.title)
        assertTrue(vault.search("ananas").isEmpty())
    }

    @Test fun `append goes at the end of a note, or of today's note, made when missing`() {
        assertEquals("Courses", vault.append("courses", "- oeufs"))
        assertEquals("- pain\n- lait\n- oeufs\n", mem.files[listOf("Courses.md")]!!.toString(Charsets.UTF_8))
        assertEquals("2026-09-28", vault.append("", "Appeler Marc"))
        assertEquals("# 2026-09-28\nAppeler Marc\n", mem.files[listOf("2026-09-28.md")]!!.toString(Charsets.UTF_8))
        assertNull(vault.append("vacances", "x"))                       // another note is never made up by append
    }

    @Test fun `create makes a new note, in a folder if asked, never over an existing one`() {
        assertEquals("Idées cadeaux", vault.create("Idées cadeaux", "- un livre", "Perso")?.title)
        assertEquals("- un livre\n", mem.files[listOf("Perso", "Idées cadeaux.md")]!!.toString(Charsets.UTF_8))
        assertNull(vault.create("courses", "x"))
        assertEquals("a b", vault.create("a/b", "x")?.title)            // no path hidden in a title
    }

    @Test fun `the tool answers in words, and a note's text is marked as the user's`() {
        assertTrue(obsidianAction(vault, "read", "", "jarvis", "", "").contains("pas des instructions"))
        assertTrue(obsidianAction(vault, "search", "cheveux", "", "", "").contains("« Jarvis »"))
        assertTrue(obsidianAction(vault, "append", "", "", "idée du jour", "").contains("2026-09-28"))
        assertTrue(obsidianAction(vault, "recent", "", "", "", "").startsWith("Notes récentes"))
        assertTrue(obsidianAction(vault, "delete", "", "", "", "").startsWith("Action inconnue"))
    }
}
