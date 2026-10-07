package com.jarvis.android.instagram

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream

/** Instagram's size limit for a photo. */
private const val IG_MAX_BYTES = 8 * 1024 * 1024

/**
 * The photo at [uri] ready for Instagram: turned upright, cropped to a ratio Instagram accepts, at most 1440 pixels wide, and
 * re-encoded as a fresh JPEG, which carries none of the original's metadata (no GPS position). Null when it cannot be read or
 * when, against all expectations, metadata survived.
 */
internal fun instagramJpeg(context: Context, uri: Uri, maxWidth: Int = IG_MAX_WIDTH): ByteArray? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val rotation = try {
        resolver.openInputStream(uri)?.use { stream ->
            @Suppress("DEPRECATION")
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    } catch (_: Exception) {
        0
    }
    val options = BitmapFactory.Options().apply { inSampleSize = instagramSampleSize(bounds.outWidth, bounds.outHeight) }
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
    val upright = if (rotation == 0) decoded else {
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            .also { if (it !== decoded) decoded.recycle() }
    }
    val box = instagramCrop(upright.width, upright.height)
    val cropped = if (box.width == upright.width && box.height == upright.height) upright
    else Bitmap.createBitmap(upright, box.left, box.top, box.width, box.height).also { if (it !== upright) upright.recycle() }
    val (w, h) = instagramSize(cropped.width, cropped.height).let { (sw, sh) ->
        if (sw <= maxWidth) sw to sh else maxWidth to maxOf(1, sh * maxWidth / sw)
    }
    val sized = if (w == cropped.width) cropped else Bitmap.createScaledBitmap(cropped, w, h, true).also { if (it !== cropped) cropped.recycle() }
    var quality = 90
    var bytes: ByteArray
    do {
        bytes = ByteArrayOutputStream().use { out -> sized.compress(Bitmap.CompressFormat.JPEG, quality, out); out.toByteArray() }
        quality -= 10
    } while (bytes.size > IG_MAX_BYTES && quality >= 50)
    sized.recycle()
    return bytes.takeUnless { jpegHasMetadata(it) || it.size > IG_MAX_BYTES }
}
