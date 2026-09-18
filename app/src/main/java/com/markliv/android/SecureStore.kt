package com.markliv.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStoreException(message: String) : Exception(message)

class SecureStore(context: Context, private val name: String = DEFAULT_NAME) {

    private val storageFile: File = File(context.noBackupFilesDir, name)
    private val backupFile: File = File(context.noBackupFilesDir, name + BACKUP_SUFFIX)
    private val atomicFile: AtomicFile = AtomicFile(storageFile)

    init {
        if (name.isBlank() || !NAME_PATTERN.matches(name)) {
            throw SecureStoreException(ERROR_INVALID_NAME)
        }
    }

    fun read(): String? {
        if (!storageFile.exists() && !backupFile.exists()) return null
        val raw = try {
            atomicFile.readFully()
        } catch (error: IOException) {
            throw SecureStoreException(ERROR_UNREADABLE)
        }
        val blob = try {
            Base64.decode(String(raw, Charsets.UTF_8), Base64.NO_WRAP)
        } catch (error: IllegalArgumentException) {
            throw SecureStoreException(ERROR_CORRUPTED)
        }
        if (blob.size < MIN_BLOB_LENGTH_BYTES || blob[0] != FORMAT_VERSION) {
            throw SecureStoreException(ERROR_CORRUPTED)
        }
        val iv = blob.copyOfRange(1, 1 + IV_LENGTH_BYTES)
        val ciphertext = blob.copyOfRange(1 + IV_LENGTH_BYTES, blob.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (error: GeneralSecurityException) {
            throw SecureStoreException(ERROR_CORRUPTED)
        }
    }

    fun save(apiKey: String) {
        if (apiKey.isBlank()) {
            throw SecureStoreException(ERROR_MISSING_KEY)
        }
        val encoded = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            if (iv.size != IV_LENGTH_BYTES) {
                throw SecureStoreException(ERROR_ENCRYPT)
            }
            val ciphertext = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
            val blob = ByteArray(1 + IV_LENGTH_BYTES + ciphertext.size)
            blob[0] = FORMAT_VERSION
            iv.copyInto(blob, 1)
            ciphertext.copyInto(blob, 1 + IV_LENGTH_BYTES)
            Base64.encodeToString(blob, Base64.NO_WRAP)
        } catch (error: GeneralSecurityException) {
            throw SecureStoreException(ERROR_ENCRYPT)
        }
        var stream: FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(encoded.toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
            atomicFile.finishWrite(stream)
            stream = null
        } catch (error: IOException) {
            if (stream != null) {
                atomicFile.failWrite(stream)
            }
            throw SecureStoreException(ERROR_SAVE)
        }
    }

    fun clear() {
        atomicFile.delete()
        if (storageFile.exists() || backupFile.exists()) {
            throw SecureStoreException(ERROR_CLEAR)
        }
    }

    private fun secretKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE)
            keyStore.load(null)
            val alias = KEY_ALIAS_PREFIX + name
            if (!keyStore.containsAlias(alias)) {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
                generator.init(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(KEY_LENGTH_BITS)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
                generator.generateKey()
            }
            val secretKey = keyStore.getKey(alias, null) as? SecretKey
            secretKey ?: throw SecureStoreException(ERROR_KEYSTORE)
        } catch (error: IOException) {
            throw SecureStoreException(ERROR_KEYSTORE)
        } catch (error: GeneralSecurityException) {
            throw SecureStoreException(ERROR_KEYSTORE)
        }
    }

    private companion object {
        const val DEFAULT_NAME = "gemini_credentials"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS_PREFIX = "com.markliv.android.secure."
        private const val BACKUP_SUFFIX = ".bak"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION: Byte = 1
        private const val IV_LENGTH_BYTES = 12
        private const val TAG_LENGTH_BITS = 128
        private const val KEY_LENGTH_BITS = 256
        private const val MIN_BLOB_LENGTH_BYTES = 1 + IV_LENGTH_BYTES + TAG_LENGTH_BITS / 8 + 1
        private val NAME_PATTERN = Regex("[a-zA-Z0-9._-]+")

        private const val ERROR_INVALID_NAME = "Nom de stockage sécurisé invalide."
        private const val ERROR_MISSING_KEY = "Clé API manquante. Renseignez votre clé Gemini avant l'enregistrement."
        private const val ERROR_KEYSTORE = "Accès au magasin de clés sécurisé impossible."
        private const val ERROR_ENCRYPT = "Chiffrement sécurisé de la clé API impossible."
        private const val ERROR_SAVE = "Écriture sécurisée impossible. Réessayez l'enregistrement."
        private const val ERROR_UNREADABLE = "Lecture du stockage sécurisé impossible."
        private const val ERROR_CORRUPTED = "Contenu du stockage sécurisé invalide ou corrompu. Enregistrez à nouveau votre clé API."
        private const val ERROR_CLEAR = "Suppression du stockage sécurisé impossible."
    }
}
