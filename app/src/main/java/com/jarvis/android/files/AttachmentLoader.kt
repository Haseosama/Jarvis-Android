package com.jarvis.android.files

import com.jarvis.android.i18n.*
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.jarvis.android.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun loadAttachment(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    try {
        var name = "fichier"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size > MAX_FILE_BYTES) return@withContext ERROR_FILE_TOO_BIG
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(MAX_FILE_BYTES + 1) }
            ?: return@withContext tr("Impossible de lire ce fichier.")
        if (bytes.size > MAX_FILE_BYTES) return@withContext ERROR_FILE_TOO_BIG
        val container = (context.applicationContext as JarvisApp).container
        container.attachedFiles.attach(AttachedFile(name, context.contentResolver.getType(uri).orEmpty(), bytes))
        null
    } catch (_: Exception) {
        tr("Impossible de lire ce fichier.")
    }
}
