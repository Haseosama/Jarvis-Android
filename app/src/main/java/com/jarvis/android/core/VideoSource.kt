package com.jarvis.android.core

/** Where the live session gets its pictures from, if anywhere. */
enum class VideoSource { OFF, SCREEN, CAMERA }

internal const val VIDEO_MAX_SIDE = 768
internal const val VIDEO_MIN_INTERVAL_MS = 1_500L

/**
 * Decides which frames are worth sending: not more often than every [minIntervalMs], and never the
 * same picture twice in a row (a still screen costs nothing).
 */
internal class FrameGate(private val minIntervalMs: Long = VIDEO_MIN_INTERVAL_MS) {
    private var lastSentAt = Long.MIN_VALUE
    private var lastFingerprint: Int? = null

    fun shouldSend(nowMs: Long, fingerprint: Int): Boolean {
        if (lastSentAt != Long.MIN_VALUE && nowMs - lastSentAt < minIntervalMs) return false
        if (fingerprint == lastFingerprint) return false
        lastSentAt = nowMs
        lastFingerprint = fingerprint
        return true
    }

    fun reset() {
        lastSentAt = Long.MIN_VALUE
        lastFingerprint = null
    }
}
