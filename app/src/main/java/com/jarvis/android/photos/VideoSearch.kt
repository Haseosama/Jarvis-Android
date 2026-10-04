package com.jarvis.android.photos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.jarvis.android.text.normalize

/*
 * "Montre la vidéo de l'anniversaire", "la dernière vidéo", "les vidéos de samedi": the phone's own videos, found by the words of
 * their name or album and by the day they were taken, played in place of the avatar. Nothing leaves the phone.
 */

internal data class PhoneVideo(val id: Long, val name: String, val album: String, val takenAt: Long, val durationMs: Long) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
}

internal fun videoPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

internal fun hasVideoPermission(context: Context): Boolean =
    context.checkSelfPermission(videoPermission()) == PackageManager.PERMISSION_GRANTED ||
        (Build.VERSION.SDK_INT >= 34 && context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)

/** The phone's videos taken in [start, end) (all of them when null), newest first. */
internal fun queryVideos(context: Context, start: Long?, end: Long?, limit: Int = 2_000): List<PhoneVideo> {
    val out = ArrayList<PhoneVideo>()
    val projection = arrayOf(
        MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DURATION,
    )
    val (selection, args) = if (start != null && end != null) {
        "(${MediaStore.Video.Media.DATE_TAKEN} >= ? AND ${MediaStore.Video.Media.DATE_TAKEN} < ?) OR " +
            "((${MediaStore.Video.Media.DATE_TAKEN} IS NULL OR ${MediaStore.Video.Media.DATE_TAKEN} = 0) AND ${MediaStore.Video.Media.DATE_ADDED} >= ? AND ${MediaStore.Video.Media.DATE_ADDED} < ?)" to
            arrayOf(start.toString(), end.toString(), (start / 1000).toString(), (end / 1000).toString())
    } else null to null
    context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, selection, args, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
        while (c.moveToNext() && out.size < limit) {
            val taken = c.getLong(3).takeIf { it > 0 } ?: (c.getLong(4) * 1000)
            out += PhoneVideo(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), taken, c.getLong(5))
        }
    }
    return out.sortedByDescending { it.takenAt }
}

/**
 * The videos whose name or album holds every word of [words] (case, accents and the file's extension aside; short words such as
 * "la", "de" ignored), newest first; all of them when there are no words.
 */
internal fun matchVideos(videos: List<PhoneVideo>, words: String): List<PhoneVideo> {
    val wanted = normalize(words).split(' ').filter { it.length >= 3 && it !in SMALL_WORDS }
    if (wanted.isEmpty()) return videos
    return videos.filter { v ->
        val hay = normalize(v.name.substringBeforeLast('.') + " " + v.album)
        wanted.all { it in hay }
    }
}

private val SMALL_WORDS = setOf("les", "des", "une", "video", "videos", "film", "films", "mes", "mon", "est", "que", "qui", "pour", "avec", "dans", "sur")

/** "1 min 05": a video's length, said aloud. */
internal fun durationWords(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min" + if (s % 60 > 0) " %02d".format(s % 60) else ""
        else -> "${s / 3600} h %02d".format((s % 3600) / 60)
    }
}
