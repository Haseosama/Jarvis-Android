package com.jarvis.android.memory

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Source of the AES key that protects a [SecureStore]. */
internal interface StoreKeyProvider {
    /** The current key, created on first use. */
    fun key(): SecretKey

    /** Forgets the current key so the next [key] call creates a new one. */
    fun discard()
}

/** Key kept in the Android Keystore: it never leaves the device and cannot be backed up. */
internal class KeystoreKeyProvider(private val alias: String) : StoreKeyProvider {
    private val keyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun key(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun discard() {
        try {
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        } catch (_: Exception) {
            // Best effort: a stale key just fails to decrypt and is replaced on the next write.
        }
    }
}

/**
 * One small secret (the API key) in a file, AES-256-GCM encrypted.
 *
 * Meant to live in `noBackupFilesDir`: files there are never backed up, so the file cannot
 * outlive its Keystore key after a restore (the cause of the earlier startup crash).
 * Every write goes to a temporary file first and the previous good copy is kept as `.bak`,
 * so an interrupted write cannot destroy the stored value. A value that cannot be decrypted
 * reads as absent and the unreadable files are removed, instead of raising.
 */
internal class SecureStore(
    private val dir: File,
    private val name: String,
    private val keys: StoreKeyProvider,
) {
    private val main get() = File(dir, name)
    private val backup get() = File(dir, "$name.bak")
    private val temp get() = File(dir, "$name.tmp")

    @Synchronized
    fun read(): String? {
        var found = false
        for (file in listOf(main, backup)) {
            if (!file.exists()) continue
            found = true
            try {
                return decrypt(file)
            } catch (_: UnreadableException) {
                // Try the other copy.
            } catch (_: Exception) {
                // Keystore momentarily unavailable: report nothing but keep the files.
                return null
            }
        }
        if (found) {
            // Present but undecryptable (its key is gone): nothing to recover, so start clean.
            main.delete()
            backup.delete()
            keys.discard()
        }
        return null
    }

    /** True when the value is on disk and reads back identical. */
    @Synchronized
    fun write(value: String): Boolean = try {
        dir.mkdirs()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keys.key()) }
        cipher.updateAAD(name.toByteArray())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray())
        FileOutputStream(temp).use { out ->
            out.write(payload)
            out.fd.sync()
        }
        if (main.exists()) {
            backup.delete()
            main.renameTo(backup)
        }
        temp.renameTo(main) && decrypt(main) == value
    } catch (_: Exception) {
        temp.delete()
        false
    }

    @Synchronized
    fun delete(): Boolean {
        temp.delete()
        val a = !main.exists() || main.delete()
        val b = !backup.exists() || backup.delete()
        return a && b
    }

    private class UnreadableException : Exception()

    /** Throws [UnreadableException] when the content is corrupt or was sealed with another key. */
    private fun decrypt(file: File): String {
        val bytes = file.readBytes()
        if (bytes.size <= IV_SIZE) throw UnreadableException()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_SIZE))
        }
        cipher.updateAAD(name.toByteArray())
        return try {
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE))
        } catch (_: AEADBadTagException) {
            throw UnreadableException()
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
