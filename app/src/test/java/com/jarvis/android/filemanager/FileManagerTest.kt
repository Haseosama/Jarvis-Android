package com.jarvis.android.filemanager

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tree in memory, addressed like the real one. */
private class MemoryTree : FileTree {
    private class Entry(var isDir: Boolean, var bytes: ByteArray = ByteArray(0))

    private val entries = linkedMapOf<List<String>, Entry>(emptyList<String>() to Entry(true))

    fun put(path: String, content: String = "") {
        val segments = path.split('/')
        for (i in 1 until segments.size) entries.getOrPut(segments.take(i)) { Entry(true) }
        entries[segments] = Entry(false, content.toByteArray())
    }

    fun dir(path: String) {
        val segments = path.split('/')
        for (i in 1..segments.size) entries.getOrPut(segments.take(i)) { Entry(true) }
    }

    fun has(path: String) = entries.containsKey(path.split('/'))
    fun text(path: String) = entries[path.split('/')]?.bytes?.toString(Charsets.UTF_8)

    override fun list(dir: List<String>): List<FileNode>? {
        if (entries[dir]?.isDir != true) return null
        return entries.filterKeys { it.size == dir.size + 1 && it.take(dir.size) == dir }
            .map { (k, v) -> FileNode(k.last(), v.isDir, v.bytes.size.toLong()) }
    }

    override fun stat(path: List<String>): FileNode? = entries[path]?.let { FileNode(path.lastOrNull() ?: "/", it.isDir, it.bytes.size.toLong()) }
    override fun read(path: List<String>, maxBytes: Int): ByteArray? = entries[path]?.takeIf { !it.isDir }?.bytes?.take(maxBytes)?.toByteArray()

    override fun write(path: List<String>, bytes: ByteArray): Boolean {
        if (entries[path.dropLast(1)]?.isDir != true) return false
        entries[path] = Entry(false, bytes)
        return true
    }

    override fun mkdir(path: List<String>): Boolean {
        if (entries[path.dropLast(1)]?.isDir != true) return false
        entries.getOrPut(path) { Entry(true) }
        return true
    }

    override fun delete(path: List<String>): Boolean {
        if (path.isEmpty() || !entries.containsKey(path)) return false
        entries.keys.filter { it.take(path.size) == path }.forEach { entries.remove(it) }
        return true
    }

    private fun relocate(path: List<String>, newPath: List<String>): Boolean {
        if (!entries.containsKey(path) || entries.containsKey(newPath) || entries[newPath.dropLast(1)]?.isDir != true) return false
        val moved = entries.filterKeys { it.take(path.size) == path }
        moved.keys.forEach { entries.remove(it) }
        moved.forEach { (k, v) -> entries[newPath + k.drop(path.size)] = v }
        return true
    }

    override fun rename(path: List<String>, newName: String) = relocate(path, path.dropLast(1) + newName)
    override fun move(path: List<String>, destDir: List<String>) = relocate(path, destDir + path.last())

    override fun copy(path: List<String>, destDir: List<String>): Boolean {
        val source = entries.filterKeys { it.take(path.size) == path }
        val target = destDir + path.last()
        if (source.isEmpty() || entries.containsKey(target) || entries[destDir]?.isDir != true) return false
        source.forEach { (k, v) -> entries[target + k.drop(path.size)] = Entry(v.isDir, v.bytes.copyOf()) }
        return true
    }
}

class FileManagerTest {
    private fun setup(block: MemoryTree.() -> Unit = {}): Pair<MemoryTree, FileManager> {
        val tree = MemoryTree().apply(block)
        return tree to FileManager(tree, clock = { 1_000L })
    }

    @Test
    fun `paths are split and unsafe ones refused`() {
        assertEquals(listOf("a", "b.txt"), parsePath("/a/b.txt/"))
        assertEquals(emptyList<String>(), parsePath(""))
        assertNull(parsePath("../secret"))
        assertNull(parsePath("a/../../b"))
        assertNull(parsePath("a//b"))
        assertNull(parsePath("a\\b"))
        assertNull(parsePath("./a"))
    }

    @Test
    fun `nothing is done with an unsafe path`() {
        val (tree, m) = setup { put("a.txt", "x") }
        assertTrue(m.read("../a.txt").message.startsWith("Chemin invalide"))
        assertTrue(m.delete("..").message.startsWith("Chemin invalide"))
        assertTrue(tree.has("a.txt"))
    }

    @Test
    fun `list shows folders first and hides the trash`() {
        val (_, m) = setup { put("b.txt", "12345"); dir("Docs"); dir(".jarvis_trash") }
        val text = m.list("").message
        assertTrue(text.indexOf("Docs/") < text.indexOf("b.txt"))
        assertFalse(text.contains(".jarvis_trash"))
        assertTrue(text.contains("2 élément"))
        assertTrue(m.list("nope").message.startsWith("Dossier introuvable"))
    }

    @Test
    fun `read returns the start of a text file and refuses binary or folders`() {
        val (_, m) = setup { put("n.txt", "Bonjour"); put("big.txt", "x".repeat(10_000)); dir("D") }
        assertEquals("Bonjour", m.read("n.txt").message)
        assertTrue(m.read("big.txt").message.endsWith("(début du fichier seulement)"))
        assertTrue(m.read("D").message.contains("est un dossier"))
        assertTrue(m.read("absent.txt").message.startsWith("Fichier introuvable"))
    }

    @Test
    fun `create file makes the parent folder and can be undone`() = runBlocking {
        val (tree, m) = setup()
        val r = m.createFile("Notes/idee.txt", "Une idée")
        assertEquals("Une idée", tree.text("Notes/idee.txt"))
        r.undo!!.revert()
        assertFalse(tree.has("Notes/idee.txt"))
    }

    @Test
    fun `create refuses to overwrite`() {
        val (tree, m) = setup { put("a.txt", "old") }
        assertTrue(m.createFile("a.txt", "new").message.contains("existe déjà"))
        assertEquals("old", tree.text("a.txt"))
    }

    @Test
    fun `write replaces or appends and undo restores the previous content`() = runBlocking {
        val (tree, m) = setup { put("a.txt", "abc") }
        val r = m.write("a.txt", "XYZ", append = false)
        assertEquals("XYZ", tree.text("a.txt"))
        r.undo!!.revert()
        assertEquals("abc", tree.text("a.txt"))
        m.write("a.txt", "def", append = true)
        assertEquals("abcdef", tree.text("a.txt"))
    }

    @Test
    fun `write to a new file is undone by deleting it`() = runBlocking {
        val (tree, m) = setup()
        val r = m.write("new.txt", "x", append = false)
        assertEquals("x", tree.text("new.txt"))
        r.undo!!.revert()
        assertFalse(tree.has("new.txt"))
    }

    @Test
    fun `delete goes to the trash and undo puts it back`() = runBlocking {
        val (tree, m) = setup { put("Docs/cv.pdf", "pdf") }
        val r = m.delete("Docs/cv.pdf")
        assertFalse(tree.has("Docs/cv.pdf"))
        assertTrue(tree.has(".jarvis_trash/1000_cv.pdf"))
        assertTrue(r.message.contains("corbeille"))
        val back = r.undo!!.revert()
        assertTrue(back, tree.has("Docs/cv.pdf"))
        assertFalse(tree.has(".jarvis_trash/1000_cv.pdf"))
    }

    @Test
    fun `undo of a delete refuses to overwrite a newer file`() = runBlocking {
        val (tree, m) = setup { put("a.txt", "old") }
        val r = m.delete("a.txt")
        tree.put("a.txt", "newer")
        assertTrue(r.undo!!.revert().contains("existe déjà"))
        assertEquals("newer", tree.text("a.txt"))
    }

    @Test
    fun `the trash itself cannot be deleted through the manager`() {
        val (tree, m) = setup { put(".jarvis_trash/x.txt", "x") }
        assertTrue(m.delete(".jarvis_trash").message.startsWith("Chemin invalide"))
        assertTrue(tree.has(".jarvis_trash/x.txt"))
    }

    @Test
    fun `move and undo, and moving into itself or onto an existing name is refused`() = runBlocking {
        val (tree, m) = setup { put("a.txt", "1"); put("Docs/b.txt", "2"); put("Docs/a.txt", "other"); dir("Docs/Sub") }
        assertTrue(m.move("a.txt", "Docs").message.contains("existe déjà"))
        val r = m.move("Docs/b.txt", "")
        assertTrue(tree.has("b.txt"))
        r.undo!!.revert()
        assertTrue(tree.has("Docs/b.txt"))
        assertTrue(m.move("Docs", "Docs/Sub").message.contains("dans lui-même"))
    }

    @Test
    fun `copy keeps the original and undo removes the copy`() = runBlocking {
        val (tree, m) = setup { put("a.txt", "1") }
        val r = m.copy("a.txt", "Backup")
        assertTrue(tree.has("a.txt") && tree.has("Backup/a.txt"))
        r.undo!!.revert()
        assertFalse(tree.has("Backup/a.txt"))
        assertTrue(tree.has("a.txt"))
    }

    @Test
    fun `rename and undo, invalid names refused`() = runBlocking {
        val (tree, m) = setup { put("a.txt", "1"); put("b.txt", "2") }
        assertTrue(m.rename("a.txt", "b.txt").message.contains("existe déjà"))
        assertTrue(m.rename("a.txt", "x/y").message.contains("invalide"))
        val r = m.rename("a.txt", "c.txt")
        assertTrue(tree.has("c.txt") && !tree.has("a.txt"))
        r.undo!!.revert()
        assertTrue(tree.has("a.txt"))
    }

    @Test
    fun `find matches names and extensions and skips the trash`() {
        val (_, m) = setup { put("Docs/facture.pdf"); put("Docs/note.txt"); put("Pics/facture.jpg"); put(".jarvis_trash/facture.pdf") }
        val byName = m.find("facture", "", "").message
        assertTrue(byName.contains("/Docs/facture.pdf") && byName.contains("/Pics/facture.jpg"))
        assertFalse(byName.contains(".jarvis_trash"))
        assertTrue(m.find("", "pdf", "").message.contains("1 résultat"))
        assertEquals("Aucun résultat.", m.find("zzz", "", "").message)
        assertTrue(m.find("", "", "").message.startsWith("Indiquez"))
    }

    @Test
    fun `largest lists the biggest files first`() {
        val (_, m) = setup { put("a.bin", "1"); put("D/b.bin", "x".repeat(50)); put("c.bin", "x".repeat(10)) }
        val lines = m.largest("", 2).message.lines()
        assertTrue(lines[0].startsWith("/D/b.bin"))
        assertEquals(2, lines.size)
    }

    @Test
    fun `usage counts files and sizes`() {
        val (_, m) = setup { put("a", "12"); put("D/b", "123") }
        assertTrue(m.usage("").message.contains("2 fichier(s)"))
    }

    @Test
    fun `categories follow the extension`() {
        assertEquals("Images", categoryFor("photo.JPG"))
        assertEquals("Documents", categoryFor("cv.pdf"))
        assertEquals("Audio", categoryFor("a.mp3"))
        assertEquals("Apps", categoryFor("x.apk"))
        assertEquals("Autres", categoryFor("mystere"))
    }

    @Test
    fun `organize sorts files into folders and undo puts them back`() = runBlocking {
        val (tree, m) = setup { put("p.jpg"); put("cv.pdf"); put("m.mp3"); put("Docs/keep.txt") }
        assertEquals(3, m.organizePlan("")!!.size)
        val r = m.organize("")
        assertTrue(tree.has("Images/p.jpg") && tree.has("Documents/cv.pdf") && tree.has("Audio/m.mp3"))
        assertTrue(tree.has("Docs/keep.txt"))
        assertTrue(r.undo!!.revert().startsWith("3 fichier(s)"))
        assertTrue(tree.has("p.jpg") && tree.has("cv.pdf") && tree.has("m.mp3"))
    }

    @Test
    fun `organize leaves a file alone when the target name is taken`() {
        val (tree, m) = setup { put("cv.pdf", "new"); put("Documents/cv.pdf", "old") }
        val r = m.organize("")
        assertEquals("old", tree.text("Documents/cv.pdf"))
        assertTrue(tree.has("cv.pdf"))
        assertNotNull(r.message)
    }

    @Test
    fun `sizes are readable`() {
        assertEquals("512 o", formatSize(512))
        assertEquals("12 Ko", formatSize(12_345))
        assertEquals("1,5 Mo", formatSize(1_500_000))
        assertEquals("2,0 Go", formatSize(2_000_000_000))
    }
}
