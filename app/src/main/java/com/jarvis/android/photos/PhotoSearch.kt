package com.jarvis.android.photos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * "Montre-moi mes photos d'août", "les photos de Brest": the phone's photos by the day they were taken, and, when asked,
 * by where (the GPS position the camera wrote in the photo). Everything is read on the phone; no photo leaves it.
 */

internal data class Photo(val id: Long, val takenAt: Long, val album: String) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
}

internal fun photoPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

internal fun hasPhotoPermission(context: Context): Boolean =
    context.checkSelfPermission(photoPermission()) == PackageManager.PERMISSION_GRANTED ||
        (Build.VERSION.SDK_INT >= 34 && context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)

/** Android hides where a photo was taken from apps without this permission (Android 10 and later). */
internal fun hasPhotoLocationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 29 || context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

/** The days asked for, as [start, end) in milliseconds: one day when only [from] is given, the last 7 days when neither is. */
internal fun photoRange(from: String, to: String, today: LocalDate, zone: ZoneId): Pair<Long, Long>? {
    fun day(s: String): Result<LocalDate?> = s.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it.take(10)) } } ?: Result.success(null)
    val start = day(from).getOrElse { return null }
    val end = day(to).getOrElse { return null }
    val (a, b) = when {
        start == null && end == null -> today.minusDays(6) to today
        start == null -> end!! to end
        end == null -> start to start
        else -> minOf(start, end) to maxOf(start, end)
    }
    return a.atStartOfDay(zone).toInstant().toEpochMilli() to b.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

private val MONTHS = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")

internal fun dayWords(d: LocalDate, withYear: Boolean): String =
    (if (d.dayOfMonth == 1) "1er" else d.dayOfMonth.toString()) + " " + MONTHS[d.monthValue - 1] + if (withYear) " ${d.year}" else ""

/** "12 photos du 3 au 17 août 2026, surtout dans Camera": how many, over which days, and the main album. */
internal fun describePhotos(photos: List<Photo>, zone: ZoneId, place: String = ""): String {
    val where = if (place.isEmpty()) "" else (if (photos.size > 1) " prises" else " prise") + " près de $place"
    if (photos.isEmpty()) return "Aucune photo$where pour cette période."
    val days = photos.map { Instant.ofEpochMilli(it.takenAt).atZone(zone).toLocalDate() }
    val first = days.min()
    val last = days.max()
    val span = when {
        first == last -> "le " + dayWords(first, true)
        first.year != last.year -> "du " + dayWords(first, true) + " au " + dayWords(last, true)
        first.month == last.month -> "du " + (if (first.dayOfMonth == 1) "1er" else first.dayOfMonth.toString()) + " au " + dayWords(last, true)
        else -> "du " + dayWords(first, false) + " au " + dayWords(last, true)
    }
    val count = if (photos.size == 1) "1 photo" else "${photos.size} photos"
    val albums = photos.groupingBy { it.album }.eachCount().entries.sortedByDescending { it.value }
    val main = albums.firstOrNull()?.takeIf { it.key.isNotBlank() && albums.size > 1 && it.value * 2 > photos.size }?.key
    val album = when {
        albums.size == 1 && albums[0].key.isNotBlank() -> ", dans ${albums[0].key}"
        main != null -> ", surtout dans $main"
        else -> ""
    }
    return "$count$where $span$album."
}

/** The photos taken in [start, end), newest first, from the albums whose name contains [album] when it is given. */
internal fun queryPhotos(context: Context, start: Long, end: Long, album: String, limit: Int = 2_000): List<Photo> {
    val out = ArrayList<Photo>()
    val projection = arrayOf(
        MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
    )
    val selection = "(${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} < ?) OR " +
        "((${MediaStore.Images.Media.DATE_TAKEN} IS NULL OR ${MediaStore.Images.Media.DATE_TAKEN} = 0) AND ${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media.DATE_ADDED} < ?)"
    val args = arrayOf(start.toString(), end.toString(), (start / 1000).toString(), (end / 1000).toString())
    val wanted = album.trim().lowercase()
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, args, "${MediaStore.Images.Media.DATE_TAKEN} DESC",
    )?.use { c ->
        while (c.moveToNext() && out.size < limit) {
            val name = c.getString(3).orEmpty()
            if (wanted.isNotEmpty() && !name.lowercase().contains(wanted)) continue
            val taken = c.getLong(1).takeIf { it > 0 } ?: (c.getLong(2) * 1000)
            out += Photo(c.getLong(0), taken, name)
        }
    }
    return out.sortedByDescending { it.takenAt }
}

/** Where [photo] was taken, from the GPS position in the file, or null when it has none (or it cannot be read). */
internal fun photoPosition(context: Context, photo: Photo): Pair<Double, Double>? = try {
    val uri = if (Build.VERSION.SDK_INT >= 29) MediaStore.setRequireOriginal(photo.uri) else photo.uri
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val f = FloatArray(2)
        @Suppress("DEPRECATION")
        if (ExifInterface(stream).getLatLong(f) && !(f[0] == 0f && f[1] == 0f)) f[0].toDouble() to f[1].toDouble() else null
    }
} catch (_: Exception) {
    null
}
