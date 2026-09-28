package com.jarvis.android.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build

/**
 * A photo of the phone, read for the screen: at most [maxSide] pixels on its long side, turned the way it was taken. Null when it
 * cannot be read (gone, or no permission).
 */
internal fun loadPhoto(context: Context, uri: Uri, maxSide: Int): Bitmap? = try {
    if (Build.VERSION.SDK_INT >= 29) {
        // the system's own reduced copy, already turned upright
        context.contentResolver.loadThumbnail(uri, android.util.Size(maxSide, maxSide), null)
    } else {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = sampleFor(bounds.outWidth, bounds.outHeight, maxSide)
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        val degrees = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
        if (bitmap == null || degrees == 0) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
    }
} catch (_: Exception) {
    null
}

/** The power of two a picture of [w] by [h] is divided by so that its long side stays at or above [maxSide] (1: as it is). */
internal fun sampleFor(w: Int, h: Int, maxSide: Int): Int {
    var sample = 1
    while (maxOf(w, h) / (sample * 2) >= maxSide) sample *= 2
    return sample
}
