package com.jarvis.android.memory

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SecureStoreTest {
    private class FakeKeys : StoreKeyProvider {
        var current: SecretKey = generate()
        var unavailable = false
        var discarded = 0

        override fun key(): SecretKey {
            if (unavailable) throw IllegalStateException("keystore indisponible")
            return current
        }

        override fun discard() {
            discarded++
            current = generate()
        }

        fun lose() {
            current = generate()
        }

        private fun generate(): SecretKey =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    private lateinit var dir: File
    private lateinit var keys: FakeKeys

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("securestore").toFile()
        keys = FakeKeys()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun store() = SecureStore(dir, "k.enc", keys)

    @Test
    fun `nothing stored reads as null`() {
        assertNull(store().read())
    }

    @Test
    fun `written value is read back and not stored in clear`() {
        val s = store()
        assertTrue(s.write("AIzaSecretValue"))
        assertEquals("AIzaSecretValue", s.read())
        assertFalse(File(dir, "k.enc").readText(Charsets.ISO_8859_1).contains("AIzaSecretValue"))
    }

    @Test
    fun `a second store instance reads what the first wrote`() {
        store().write("abc")
        assertEquals("abc", store().read())
    }

    @Test
    fun `overwrite replaces the value and keeps the previous one as backup`() {
        val s = store()
        s.write("one")
        s.write("two")
        assertEquals("two", s.read())
        assertTrue(File(dir, "k.enc.bak").exists())
    }

    @Test
    fun `a corrupt main file falls back to the backup`() {
        val s = store()
        s.write("one")
        s.write("two")
        File(dir, "k.enc").writeBytes(ByteArray(40) { 7 })
        assertEquals("one", s.read())
    }

    @Test
    fun `a lost key reads as null, wipes the files and allows a fresh write`() {
        val s = store()
        s.write("secret")
        keys.lose()
        assertNull(s.read())
        assertFalse(File(dir, "k.enc").exists())
        assertFalse(File(dir, "k.enc.bak").exists())
        assertEquals(1, keys.discarded)
        assertTrue(s.write("new"))
        assertEquals("new", s.read())
    }

    @Test
    fun `a momentarily unavailable keystore keeps the files`() {
        val s = store()
        s.write("secret")
        keys.unavailable = true
        assertNull(s.read())
        assertTrue(File(dir, "k.enc").exists())
        keys.unavailable = false
        assertEquals("secret", s.read())
    }

    @Test
    fun `write reports failure when the keystore is unavailable`() {
        keys.unavailable = true
        assertFalse(store().write("x"))
        assertFalse(File(dir, "k.enc").exists())
    }

    @Test
    fun `truncated file reads as null`() {
        File(dir, "k.enc").writeBytes(ByteArray(5))
        assertNull(store().read())
    }

    @Test
    fun `delete removes both copies`() {
        val s = store()
        s.write("one")
        s.write("two")
        assertTrue(s.delete())
        assertNull(s.read())
        assertFalse(File(dir, "k.enc").exists())
        assertFalse(File(dir, "k.enc.bak").exists())
    }

    @Test
    fun `a value sealed under another file name is rejected`() {
        store().write("secret")
        File(dir, "k.enc").copyTo(File(dir, "other.enc"))
        assertNull(SecureStore(dir, "other.enc", keys).read())
    }
}
