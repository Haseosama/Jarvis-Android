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

class LocalModelStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fakeTaskFile(bytes: Int, header: ByteArray = byteArrayOf('P'.code.toByte(), 'K'.code.toByte())): File {
        val f = tmp.newFile()
        f.outputStream().use { out ->
            out.write(header)
            out.write(ByteArray(maxOf(0, bytes - header.size)))
        }
        return f
    }

    @Test fun `nothing is installed at first, and a real-looking bundle imports cleanly`() {
        val store = LocalModelStore(tmp.newFolder())
        assertFalse(store.installed())
        assertNull(store.sizeMb())
        val source = fakeTaskFile(LOCAL_MODEL_MIN_BYTES.toInt() + 1000)
        val error = store.import(source)
        assertNull(error, error)
        assertTrue(store.installed())
        assertNotNull(store.sizeMb())
    }

    @Test fun `a file that is too small, too big, or not a zip is refused`() {
        val store = LocalModelStore(tmp.newFolder())
        assertNotNull(store.import(fakeTaskFile(1000)))
        assertFalse(store.installed())
        assertNotNull(store.import(fakeTaskFile(1000, header = byteArrayOf('H'.code.toByte(), 'I'.code.toByte()))))
        assertFalse(store.installed())
    }

    @Test fun `removing deletes the model, and importing again replaces it`() {
        val store = LocalModelStore(tmp.newFolder())
        store.import(fakeTaskFile(LOCAL_MODEL_MIN_BYTES.toInt() + 5000))
        assertTrue(store.installed())
        store.remove()
        assertFalse(store.installed())
        val second = fakeTaskFile(LOCAL_MODEL_MIN_BYTES.toInt() + 9000)
        assertNull(store.import(second))
        assertTrue(store.installed())
        assertEquals((LOCAL_MODEL_MIN_BYTES + 9000) / 1_000_000L, store.sizeMb())
    }

    @Test fun `looksLikeModelFile checks the size range and the task or litertlm header`() {
        val pk = byteArrayOf('P'.code.toByte(), 'K'.code.toByte())
        val lm = "LITERTLM".toByteArray(Charsets.US_ASCII)
        assertTrue(looksLikeModelFile(LOCAL_MODEL_MIN_BYTES, pk))
        assertTrue(looksLikeModelFile(LOCAL_MODEL_MIN_BYTES, lm))
        assertFalse(looksLikeModelFile(LOCAL_MODEL_MIN_BYTES - 1, pk))
        assertFalse(looksLikeModelFile(LOCAL_MODEL_MAX_BYTES + 1, lm))
        assertFalse(looksLikeModelFile(LOCAL_MODEL_MIN_BYTES, byteArrayOf('G'.code.toByte(), 'F'.code.toByte())))
        assertFalse(looksLikeModelFile(LOCAL_MODEL_MIN_BYTES, "LITERT".toByteArray(Charsets.US_ASCII)))
        assertEquals(LOCAL_MODEL_FILE, modelFileName(pk))
        assertEquals(LOCAL_MODEL_LM_FILE, modelFileName(lm))
    }

    @Test fun `a litertlm file imports under its own name, and replaces a task file`() {
        val dir = tmp.newFolder()
        val store = LocalModelStore(dir)
        assertNull(store.import(fakeTaskFile(LOCAL_MODEL_MIN_BYTES.toInt() + 10)))
        assertEquals(File(dir, LOCAL_MODEL_FILE), store.file)
        assertNull(store.import(fakeTaskFile(LOCAL_MODEL_MIN_BYTES.toInt() + 10, header = "LITERTLM".toByteArray(Charsets.US_ASCII))))
        assertEquals(File(dir, LOCAL_MODEL_LM_FILE), store.file)
        assertFalse(File(dir, LOCAL_MODEL_FILE).exists())
        assertTrue(store.installed())
    }
}
