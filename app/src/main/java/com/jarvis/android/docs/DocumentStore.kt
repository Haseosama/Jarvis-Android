package com.jarvis.android.docs

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.jarvis.android.R
import com.jarvis.android.i18n.tr
import java.io.File

private const val CHANNEL_ID = "jarvis_documents"

/** Where the documents Jarvis writes are kept, and how they are opened and shared. */
internal object DocumentStore {
    fun folder(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: File(context.filesDir, "documents")
        return File(base, "Jarvis").also { it.mkdirs() }
    }

    /** A file name that does not overwrite an existing one: `name.pdf`, then `name-2.pdf`, `name-3.pdf`… */
    fun uniqueFile(dir: File, base: String, extension: String): File {
        var candidate = File(dir, "$base.$extension")
        var n = 2
        while (candidate.exists()) { candidate = File(dir, "$base-$n.$extension"); n++ }
        return candidate
    }

    fun uriFor(context: Context, file: File) = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun mimeFor(extension: String): String = when (extension.lowercase()) {
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "csv" -> "text/csv"
        "md" -> "text/markdown"
        else -> "text/plain"
    }

    /**
     * The type to open or share a file with. Markdown is plain readable text, and "text/markdown" has no application on most phones (the
     * file then simply does not open), so it goes out as "text/plain".
     */
    fun viewMimeFor(extension: String): String = if (extension.equals("md", ignoreCase = true)) "text/plain" else mimeFor(extension)

    /** A notification with buttons to open and to share [file]: starting an activity straight from a background service is not allowed. */
    fun notifyReady(context: Context, file: File, title: String, text: String, id: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, tr("Documents Jarvis"), NotificationManager.IMPORTANCE_DEFAULT))
        val uri = uriFor(context, file)
        val mime = viewMimeFor(file.extension)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val viewIntent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // With no application for this type, a plain intent does nothing, silently: the chooser at least says so and offers the sharing.
        val view = if (context.packageManager.resolveActivity(viewIntent, 0) != null) viewIntent
        else Intent.createChooser(viewIntent, tr("Ouvrir avec")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val send = Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            tr("Partager"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(PendingIntent.getActivity(context, id, view, flags))
            .addAction(0, tr("Ouvrir"), PendingIntent.getActivity(context, id, view, flags))
            .addAction(0, tr("Partager"), PendingIntent.getActivity(context, id + 1, send, flags))
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // Notifications not allowed: the file is still there.
        }
    }
}
