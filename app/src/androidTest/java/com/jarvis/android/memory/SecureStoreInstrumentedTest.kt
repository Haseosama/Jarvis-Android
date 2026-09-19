package com.jarvis.android.memory

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * SecureStore against the real Android Keystore. It runs inside the app's process, so it only
 * uses a scratch directory in the cache and a throw-away Keystore alias: the real API keys are
 * never touched.
 */
@RunWith(AndroidJUnit4::class)
class SecureStoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var keys: KeystoreKeyProvider

    @Before
    fun setUp() {
        val id = UUID.randomUUID().toString()
        dir = File(context.cacheDir, "securestore-test-$id").apply { mkdirs() }
        keys = KeystoreKeyProvider("jarvis_test_$id")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
        keys.discard()
    }

    private fun store() = SecureStore(dir, "k.enc", keys)

    @Test
    fun keystoreKeyIsNotExtractable() {
        assertNull(keys.key().encoded)
    }

    @Test
    fun valueSurvivesANewStoreInstance() {
        assertTrue(store().write("AIzaSecretValue"))
        assertEquals("AIzaSecretValue", store().read())
    }

    @Test
    fun fileDoesNotContainTheValueInClear() {
        store().write("AIzaSecretValue")
        val raw = File(dir, "k.enc").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("AIzaSecretValue"))
    }

    @Test
    fun overwriteKeepsAReadableBackup() {
        val s = store()
        s.write("one")
        s.write("two")
        assertEquals("two", s.read())
        File(dir, "k.enc").writeBytes(ByteArray(40) { 9 })
        assertEquals("one", s.read())
    }

    @Test
    fun aRestoredFileWithoutItsKeyReadsAsAbsentAndIsWiped() {
        val s = store()
        s.write("secret")
        // What a backup restore does: the file is back, the Keystore key is not.
        keys.discard()
        assertNull(s.read())
        assertFalse(File(dir, "k.enc").exists())
        assertTrue(s.write("fresh"))
        assertEquals("fresh", s.read())
    }

    @Test
    fun deleteRemovesEverything() {
        val s = store()
        s.write("one")
        s.write("two")
        assertTrue(s.delete())
        assertNull(s.read())
    }

    @Test
    fun aFileSealedForAnotherNameIsRejected() {
        store().write("secret")
        File(dir, "k.enc").copyTo(File(dir, "other.enc"))
        assertNull(SecureStore(dir, "other.enc", keys).read())
    }
}
