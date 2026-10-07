package com.jarvis.android.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalModelCatalogTest {
    @get:Rule val tmp = TemporaryFolder()

    private val zip = byteArrayOf('P'.code.toByte(), 'K'.code.toByte())
    private val litertlm = "LITERTLM".toByteArray(Charsets.US_ASCII)
    private fun headOf(m: LocalModelChoice) = if (m.installedName == LOCAL_MODEL_LM_FILE) litertlm else zip

    @Test fun `every model in the list is a task file the store would accept, with a unique id`() {
        assertEquals(LOCAL_MODEL_CATALOG.size, LOCAL_MODEL_CATALOG.map { it.id }.toSet().size)
        for (m in LOCAL_MODEL_CATALOG) {
            assertTrue(m.file, m.file.endsWith(".task") || m.file.endsWith(".litertlm"))
            assertTrue(m.file, looksLikeModelFile(m.bytes, headOf(m)))
            assertEquals(m.file, m.installedName, modelFileName(headOf(m)))
            assertTrue(m.downloadUrl, m.downloadUrl.startsWith("https://huggingface.co/"))
            assertTrue("official models come first, uncensored ones last", !m.uncensored || LOCAL_MODEL_CATALOG.dropWhile { !it.uncensored }.all { it.uncensored })
            m.sha256?.let { assertTrue(it, Regex("[0-9a-f]{64}").matches(it)) }
        }
        assertEquals(LOCAL_MODEL_CATALOG[1], localModelChoice(LOCAL_MODEL_CATALOG[1].id))
        assertNull(localModelChoice("inconnu"))
        assertNull(localModelChoice(null))
        assertTrue(LOCAL_MODEL_CATALOG.any { it.uncensored })
        assertTrue(LOCAL_MODEL_CATALOG.filter { it.uncensored }.all { !it.needsHfToken })
    }

    @Test fun `sizes read naturally in French`() {
        assertEquals("≈ 1,6 Go", localModelChoice("qwen2.5-1.5b")!!.sizeLabel)
        assertEquals("≈ 554 Mo", localModelChoice("gemma3-1b")!!.sizeLabel)
        assertEquals("≈ 2,6 Go", localModelChoice("gemma4-e2b")!!.sizeLabel)
    }

    @Test fun `a download is only accepted with the exact size, the right header and the right hash`() {
        val lm = localModelChoice("gemma4-e2b-abliterated")!!
        assertNull(checkDownloadedModel(lm, lm.bytes, litertlm) { lm.sha256 })
        assertNotNull("a task file where a litertlm one is expected", checkDownloadedModel(lm, lm.bytes, zip) { lm.sha256 })
        val m = LOCAL_MODEL_CATALOG.first { it.sha256 != null && it.installedName == LOCAL_MODEL_FILE }
        assertNull(checkDownloadedModel(m, m.bytes, zip) { m.sha256 })
        assertNull(checkDownloadedModel(m, m.bytes, zip) { m.sha256!!.uppercase() })
        assertNotNull(checkDownloadedModel(m, m.bytes - 1, zip) { m.sha256 })
        assertNotNull(checkDownloadedModel(m, m.bytes, byteArrayOf(0, 0)) { m.sha256 })
        assertNotNull(checkDownloadedModel(m, m.bytes, zip) { "0".repeat(64) })
        assertNotNull(checkDownloadedModel(m, m.bytes, zip) { null })
        val gated = LOCAL_MODEL_CATALOG.first { it.sha256 == null && it.installedName == LOCAL_MODEL_FILE }
        var hashed = false
        assertNull(checkDownloadedModel(gated, gated.bytes, zip) { hashed = true; null })
        assertFalse("no hash to compare, so the file is not read twice", hashed)
    }

    @Test fun `a finished download becomes the model, replaces an imported one, and is removed with it`() {
        val dir = tmp.newFolder()
        val downloads = tmp.newFolder()
        val store = LocalModelStore(dir, downloads)
        val imported = tmp.newFile().apply { outputStream().use { it.write(zip); it.write(ByteArray(LOCAL_MODEL_MIN_BYTES.toInt())) } }
        assertNull(store.import(imported))
        assertNull(store.label())

        val part = File(downloads, LOCAL_MODEL_PART_FILE).apply { outputStream().use { it.write(zip); it.write(ByteArray(LOCAL_MODEL_MIN_BYTES.toInt() + 10)) } }
        assertNull(store.installDownloaded(part, "Qwen 2.5 0,5B (Alibaba)"))
        assertTrue(store.installed())
        assertEquals(File(downloads, LOCAL_MODEL_FILE), store.file)
        assertFalse(File(dir, LOCAL_MODEL_FILE).exists())
        assertEquals("Qwen 2.5 0,5B (Alibaba)", store.label())

        assertNull(store.import(imported))
        assertEquals(File(dir, LOCAL_MODEL_FILE), store.file)
        assertFalse(File(downloads, LOCAL_MODEL_FILE).exists())
        assertNull(store.label())

        File(downloads, LOCAL_MODEL_PART_FILE).apply { outputStream().use { it.write(zip); it.write(ByteArray(LOCAL_MODEL_MIN_BYTES.toInt())) } }
            .let { assertNull(store.installDownloaded(it, "Gemma")) }
        val running = File(downloads, LOCAL_MODEL_PART_FILE).apply { writeBytes(zip) }
        store.remove()
        assertFalse(store.installed())
        assertNull(store.label())
        assertTrue("a download under way is left alone", running.exists())

        val lmPart = File(downloads, LOCAL_MODEL_PART_FILE).apply { outputStream().use { it.write(litertlm); it.write(ByteArray(LOCAL_MODEL_MIN_BYTES.toInt())) } }
        assertNull(store.installDownloaded(lmPart, "Gemma 4"))
        assertEquals(File(downloads, LOCAL_MODEL_LM_FILE), store.file)
    }

    @Test fun `readHead reads the first bytes, or fewer from a tiny file`() {
        assertEquals(listOf<Byte>(80, 75), readHead(tmp.newFile().apply { writeBytes(byteArrayOf(80, 75, 3, 4)) }, 2)!!.toList())
        assertEquals("LITERTLM", String(readHead(tmp.newFile().apply { writeBytes("LITERTLM-rest".toByteArray()) })!!))
        assertEquals(listOf<Byte>(80), readHead(tmp.newFile().apply { writeBytes(byteArrayOf(80)) })!!.toList())
        assertNull(readHead(File(tmp.root, "absent")))
    }
}
