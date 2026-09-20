package com.jarvis.android.weather

/** A position fix. */
internal data class Fix(val latitude: Double, val longitude: Double, val timeMs: Long, val accuracyMeters: Float)

internal const val MAX_FIX_AGE_MS = 30 * 60_000L

private val HERE_WORDS = setOf(
    "", "ici", "ici meme", "ici même", "ma position", "ma position actuelle", "ma localisation", "ma ville", "chez moi",
    "ou je suis", "où je suis", "autour de moi", "here", "my location", "current location", "me", "moi",
)

/** True when the user did not name a place, or asked for "here": the weather then uses their position. */
internal fun isHereRequest(city: String?): Boolean = city.orEmpty().trim().lowercase() in HERE_WORDS

/** The most recent fix that is recent enough, or null. Ties go to the more accurate one. */
internal fun pickFreshest(candidates: List<Fix>, nowMs: Long, maxAgeMs: Long = MAX_FIX_AGE_MS): Fix? =
    candidates
        .filter { nowMs - it.timeMs in 0..maxAgeMs && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
        .maxWithOrNull(compareBy<Fix> { it.timeMs / 60_000 }.thenBy { -it.accuracyMeters })

/** "votre position" or "votre position (Lyon)". */
internal fun positionLabel(place: String?): String =
    place?.trim()?.takeIf { it.isNotEmpty() }?.let { "votre position ($it)" } ?: "votre position"
