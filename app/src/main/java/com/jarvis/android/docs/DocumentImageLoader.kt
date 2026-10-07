package com.jarvis.android.docs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.jarvis.android.photos.hasPhotoPermission
import com.jarvis.android.photos.queryPhotos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.ZoneId

private const val MAX_SOURCE_BYTES = 40 * 1024 * 1024
private const val MAX_SIDE = 1600

/**
 * The pictures of the image blocks of [blocks], by source; a source that cannot be read (missing file, no gallery permission,
 * unreachable address) is simply absent, and the writers then say the image is missing.
 */
internal suspend fun loadDocumentImages(context: Context, blocks: List<Block>, client: OkHttpClient?): Map<String, DocImage> = withContext(Dispatchers.IO) {
    blocks.filterIsInstance<Block.Image>().map { it.source }.distinct().mapNotNull { source ->
        runCatching { loadDocumentImage(context, source, client) }.getOrNull()?.let { source to it }
    }.toMap()
}

/** One picture for a document, from any source [imageSource] reads; null when it cannot be had. */
internal fun loadDocumentImage(context: Context, source: String, client: OkHttpClient?): DocImage? {
    val bytes: ByteArray = when (val s = imageSource(source) ?: return null) {
        is ImageSource.Gallery, is ImageSource.GalleryDay -> {
            if (!hasPhotoPermission(context)) return null
            val zone = ZoneId.systemDefault()
            val (start, end, rank) = when (s) {
                is ImageSource.Gallery -> Triple(0L, Long.MAX_VALUE / 2, s.rank)
                is ImageSource.GalleryDay -> Triple(s.day.atStartOfDay(zone).toInstant().toEpochMilli(), s.day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), s.rank)
                else -> return null
            }
            val photo = queryPhotos(context, start, end, "", limit = rank).getOrNull(rank - 1) ?: return null
            context.contentResolver.openInputStream(photo.uri)?.use { it.readLimited() } ?: return null
        }
        is ImageSource.Web -> {
            val http = client ?: return null
            http.newCall(Request.Builder().url(s.url).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                r.body?.byteStream()?.readLimited() ?: return null
            }
        }
        is ImageSource.Local -> {
            if (s.uri.startsWith("/")) File(s.uri).takeIf { it.isFile }?.inputStream()?.use { it.readLimited() } ?: return null
            else context.contentResolver.openInputStream(Uri.parse(s.uri))?.use { it.readLimited() } ?: return null
        }
    }
    return documentJpeg(bytes)
}

private fun InputStream.readLimited(): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
        if (out.size() > MAX_SOURCE_BYTES) return null
    }
    return out.toByteArray()
}

/** A picture's bytes turned upright, at most [maxSide] pixels on its longest side, re-encoded as a JPEG without metadata. */
internal fun documentJpeg(bytes: ByteArray, maxSide: Int = MAX_SIDE): DocImage? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val rotation = try {
        @Suppress("DEPRECATION")
        when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Exception) {
        0
    }
    val longest = maxOf(decoded.width, decoded.height)
    val scale = if (longest > maxSide) maxSide.toFloat() / longest else 1f
    val matrix = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
    val bitmap = if (rotation == 0 && scale == 1f) decoded
    else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
    // white under any transparency: a JPEG has none, and a PNG's clear parts would otherwise turn black
    val flat = if (bitmap.hasAlpha()) Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
        android.graphics.Canvas(it).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
        bitmap.recycle()
    } else bitmap
    val jpeg = ByteArrayOutputStream().use { out -> flat.compress(Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray() }
    val image = DocImage(jpeg, flat.width, flat.height)
    flat.recycle()
    return image
}
