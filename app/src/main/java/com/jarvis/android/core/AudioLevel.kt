package com.jarvis.android.core

import kotlin.math.sqrt

/**
 * Loudness of a chunk of little-endian 16-bit mono PCM, from 0 (silence) to 1. Speech sits well
 * below full scale, so the RMS is boosted before clamping to keep the animation lively.
 */
internal fun pcm16Level(pcm: ByteArray): Float {
    val samples = pcm.size / 2
    if (samples == 0) return 0f
    var sum = 0.0
    for (i in 0 until samples) {
        val value = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toInt()
        sum += value.toDouble() * value
    }
    val rms = sqrt(sum / samples) / 32768.0
    return (rms * LEVEL_BOOST).toFloat().coerceIn(0f, 1f)
}

private const val LEVEL_BOOST = 4.0
