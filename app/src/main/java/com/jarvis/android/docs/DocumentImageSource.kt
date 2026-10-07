package com.jarvis.android.docs

import java.time.LocalDate

/** A picture ready to go into a document: a JPEG (upright, without metadata) and its size in pixels. */
internal class DocImage(val jpeg: ByteArray, val width: Int, val height: Int)

/** Where the picture of an image block comes from. */
internal sealed interface ImageSource {
    /** The [rank]-th most recent photo of the gallery (1 = the last one taken). */
    data class Gallery(val rank: Int) : ImageSource

    /** The [rank]-th photo taken on [day], the most recent first. */
    data class GalleryDay(val day: LocalDate, val rank: Int) : ImageSource
    data class Web(val url: String) : ImageSource

    /** A `content://` or `file://` address, or a path on the phone. */
    data class Local(val uri: String) : ImageSource
}

private val GALLERY_LATEST = setOf("", "derniere", "dernière", "latest", "last", "recente", "récente")

/**
 * Reads an image source: `galerie:dernière` (or `galerie:3` for the third most recent), `galerie:2026-10-05` (or
 * `galerie:2026-10-05#2`), an `https://` address, a `content://` or `file://` address, or a path. Null when it is none of these.
 */
internal fun imageSource(raw: String): ImageSource? {
    val text = raw.trim().removeSurrounding("<", ">")
    val lower = text.lowercase()
    val gallery = listOf("galerie:", "gallery:", "photo:").firstOrNull { lower.startsWith(it) }
    if (gallery != null) {
        val rest = lower.removePrefix(gallery).trim()
        if (rest in GALLERY_LATEST) return ImageSource.Gallery(1)
        rest.toIntOrNull()?.let { return if (it >= 1) ImageSource.Gallery(it) else null }
        val day = runCatching { LocalDate.parse(rest.substringBefore('#').trim()) }.getOrNull() ?: return null
        val rank = if ('#' in rest) rest.substringAfter('#').trim().toIntOrNull() ?: return null else 1
        return if (rank >= 1) ImageSource.GalleryDay(day, rank) else null
    }
    return when {
        lower.startsWith("https://") || lower.startsWith("http://") -> ImageSource.Web(text)
        lower.startsWith("content://") || lower.startsWith("file://") || text.startsWith("/") -> ImageSource.Local(text)
        else -> null
    }
}

/** The size to draw a [width] × [height] picture so it fits in [maxWidth] × [maxHeight], keeping its proportions (never enlarged past the box). */
internal fun fitBox(width: Int, height: Int, maxWidth: Float, maxHeight: Float): Pair<Float, Float> {
    if (width <= 0 || height <= 0) return maxWidth to maxWidth * 0.75f
    val scale = minOf(maxWidth / width, maxHeight / height)
    return width * scale to height * scale
}
