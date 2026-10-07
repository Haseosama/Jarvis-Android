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

    @Test fun `every model in the list is a task file the store would accept, with a unique id`() {
        assertEquals(LOCAL_MODEL_CATALOG.size, LOCAL_MODEL_CATALOG.map { it.id }.toSet().size)
        for (m in LOCAL_MODEL_CATALOG) {
            assertTrue(m.file, m.file.endsWith(".task"))
            assertTrue(m.file, looksLikeTaskBundle(m.bytes, zip))
            assertTrue(m.downloadUrl, m.downloadUrl.startsWith("https://huggingface.co/litert-community/"))
            m.sha256?.let { assertTrue(it, Regex("[0-9a-f]{64}").matches(it)) }
        }
        assertEquals(LOCAL_MODEL_CATALOG[1], localModelChoice(LOCAL_MODEL_CATALOG[1].id))
        assertNull(localModelChoice("inconnu"))
        assertNull(localModelChoice(null))
    }

    @Test fun `sizes read naturally in French`() {
        assertEquals("≈ 1,6 Go", localModelChoice("qwen2.5-1.5b")!!.sizeLabel)
        assertEquals("≈ 554 Mo", localModelChoice("gemma3-1b")!!.sizeLabel)
    }

    @Test fun `a download is only accepted with the exact size, the zip header and the right hash`() {
        val m = LOCAL_MODEL_CATALOG.first { it.sha256 != null }
        assertNull(checkDownloadedModel(m, m.bytes, zip) { m.sha256 })
        assertNull(checkDownloadedModel(m, m.bytes, zip) { m.sha256!!.uppercase() })
        assertNotNull(checkDownloadedModel(m, m.bytes - 1, zip) { m.sha256 })
        assertNotNull(checkDownloadedModel(m, m.bytes, byteArrayOf(0, 0)) { m.sha256 })
        assertNotNull(checkDownloadedModel(m, m.bytes, zip) { "0".repeat(64) })
        assertNotNull(checkDownloadedModel(m, m.bytes, zip) { null })
        val gated = LOCAL_MODEL_CATALOG.first { it.sha256 == null }
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
        store.remove()
        assertFalse(store.installed())
        assertNull(store.label())
        assertTrue(downloads.listFiles().orEmpty().isEmpty())
    }

    @Test fun `readHead reads the first bytes, or fewer from a tiny file`() {
        assertEquals(listOf<Byte>(80, 75), readHead(tmp.newFile().apply { writeBytes(byteArrayOf(80, 75, 3, 4)) })!!.toList())
        assertEquals(listOf<Byte>(80), readHead(tmp.newFile().apply { writeBytes(byteArrayOf(80)) })!!.toList())
        assertNull(readHead(File(tmp.root, "absent")))
    }
}
