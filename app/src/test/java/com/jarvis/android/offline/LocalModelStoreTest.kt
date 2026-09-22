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

    @Test fun `looksLikeTaskBundle checks the size range and the zip header`() {
        assertTrue(looksLikeTaskBundle(LOCAL_MODEL_MIN_BYTES, byteArrayOf('P'.code.toByte(), 'K'.code.toByte())))
        assertFalse(looksLikeTaskBundle(LOCAL_MODEL_MIN_BYTES - 1, byteArrayOf('P'.code.toByte(), 'K'.code.toByte())))
        assertFalse(looksLikeTaskBundle(LOCAL_MODEL_MAX_BYTES + 1, byteArrayOf('P'.code.toByte(), 'K'.code.toByte())))
        assertFalse(looksLikeTaskBundle(LOCAL_MODEL_MIN_BYTES, byteArrayOf('G'.code.toByte(), 'F'.code.toByte())))
    }
}
