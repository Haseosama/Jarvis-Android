package com.markliv.android

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storageFile = File(context.noBackupFilesDir, TEST_STORE_NAME)
    private val backupFile = File(context.noBackupFilesDir, TEST_STORE_NAME + BACKUP_SUFFIX)
    private lateinit var store: SecureStore

    @Before
    fun setUp() {
        deleteStorageFiles()
        deleteKeyStoreEntries()
        store = SecureStore(context, TEST_STORE_NAME)
    }

    @After
    fun tearDown() {
        deleteStorageFiles()
        deleteKeyStoreEntries()
    }

    @Test
    fun readReturnsNullWhenNothingStored() {
        assertNull(store.read())
    }

    @Test
    fun roundTripAcrossNewInstancesPreservesApiKey() {
        store.save(API_KEY)
        assertEquals(API_KEY, SecureStore(context, TEST_STORE_NAME).read())
        val rotatedKey = "AIzaSyRotated-9876543210zyxwv"
        SecureStore(context, TEST_STORE_NAME).save(rotatedKey)
        assertEquals(rotatedKey, store.read())
    }

    @Test
    fun storedFileContainsNoPlaintextSecret() {
        store.save(API_KEY)
        assertTrue(storageFile.exists())
        val rawText = String(storageFile.readBytes(), Charsets.ISO_8859_1)
        assertFalse(rawText.contains(API_KEY))
        assertFalse(rawText.contains(base64Of(API_KEY)))
        assertFalse(rawText.contains(base64Of(API_KEY.substring(0, 12))))
    }

    @Test
    fun repeatedEncryptionOfSameSecretUsesDistinctIv() {
        store.save(API_KEY)
        val firstBlob = storedBlob()
        store.save(API_KEY)
        val secondBlob = storedBlob()
        val firstIv = firstBlob.copyOfRange(1, 1 + IV_LENGTH_BYTES)
        val secondIv = secondBlob.copyOfRange(1, 1 + IV_LENGTH_BYTES)
        assertFalse(firstIv.contentEquals(secondIv))
        assertFalse(firstBlob.contentEquals(secondBlob))
        assertEquals(API_KEY, store.read())
    }

    @Test
    fun saveBlankApiKeyThrowsWithoutWritingFile() {
        assertThrows(SecureStoreException::class.java) { store.save("   ") }
        assertFalse(storageFile.exists())
        assertFalse(backupFile.exists())
    }

    @Test
    fun corruptedEncodingFailsClosedAndKeepsFile() {
        store.save(API_KEY)
        storageFile.writeText(CORRUPTED_ENCODING, Charsets.UTF_8)
        assertThrows(SecureStoreException::class.java) { store.read() }
        assertTrue(storageFile.exists())
    }

    @Test
    fun corruptedCiphertextFailsClosedAndKeepsFile() {
        store.save(API_KEY)
        val tampered = storedBlob()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1] + 1).toByte()
        storageFile.writeBytes(Base64.encode(tampered, Base64.NO_WRAP))
        assertThrows(SecureStoreException::class.java) { store.read() }
        assertTrue(storageFile.exists())
    }

    @Test
    fun unknownBlobVersionFailsClosedAndKeepsFile() {
        store.save(API_KEY)
        val tampered = storedBlob()
        tampered[0] = UNKNOWN_VERSION
        storageFile.writeBytes(Base64.encode(tampered, Base64.NO_WRAP))
        assertThrows(SecureStoreException::class.java) { store.read() }
        assertTrue(storageFile.exists())
    }

    @Test
    fun clearRemovesStorageAndResetsRead() {
        store.save(API_KEY)
        assertEquals(API_KEY, store.read())
        store.clear()
        assertFalse(storageFile.exists())
        assertFalse(backupFile.exists())
        assertNull(store.read())
        store.clear()
        assertNull(store.read())
    }

    private fun storedBlob(): ByteArray {
        assertTrue(storageFile.exists())
        return Base64.decode(String(storageFile.readBytes(), Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun base64Of(value: String): String =
        String(Base64.encode(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP), Charsets.US_ASCII)

    private fun deleteStorageFiles() {
        storageFile.delete()
        backupFile.delete()
    }

    private fun deleteKeyStoreEntries() {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE)
        keyStore.load(null)
        val aliases = keyStore.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            if (alias.contains(TEST_STORE_NAME)) {
                keyStore.deleteEntry(alias)
            }
        }
    }

    private companion object {
        const val TEST_STORE_NAME = "markliv_test_credentials"
        const val BACKUP_SUFFIX = ".bak"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val IV_LENGTH_BYTES = 12
        const val API_KEY = "AIzaSyTest-MarkLiv-0123456789abcdef"
        const val CORRUPTED_ENCODING = "not-base64%%%"
        const val UNKNOWN_VERSION: Byte = 99
    }
}
