package com.jarvis.android.ui

import com.jarvis.android.i18n.*
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.jarvis.android.JarvisApp
import com.jarvis.android.files.AttachedFile
import com.jarvis.android.files.ERROR_FILE_TOO_BIG
import com.jarvis.android.files.MAX_FILE_BYTES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opens the system file picker and keeps the chosen file in memory. [onResult] receives an error
 * message, or null on success. Returns the function that opens the picker.
 */
@Composable
internal fun rememberFileAttacher(onResult: (String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch { onResult(loadAttachment(context, uri)) }
        }
    }
    return { launcher.launch(arrayOf("*/*")) }
}

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
