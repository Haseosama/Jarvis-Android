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
import com.jarvis.android.files.loadAttachment

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
