package com.jarvis.android.memory

/**
 * Picks which of several API keys to use. Sticks to one key until it is reported as rejected,
 * then moves on to the next configured one (wrapping around). The choice lives in memory only,
 * so a new process starts again from the first key.
 */
internal class KeyRotation(private val slots: () -> List<String?>) {
    private var active = 0

    /** The key to use now: the active one, or the next configured one if that slot is empty. */
    @Synchronized
    fun current(): String? {
        val all = slots()
        for (offset in all.indices) {
            val index = (active + offset) % all.size
            all[index]?.takeIf { it.isNotBlank() }?.let {
                active = index
                return it
            }
        }
        return null
    }

    /**
     * Reports that [key] was refused and returns the next different configured key, or null when
     * there is none (a duplicate of the refused key does not count).
     */
    @Synchronized
    fun rejected(key: String): String? {
        val all = slots()
        val from = all.indexOf(key).takeIf { it >= 0 } ?: active
        for (offset in 1..all.size) {
            val index = (from + offset) % all.size
            val candidate = all[index]
            if (!candidate.isNullOrBlank() && candidate != key) {
                active = index
                return candidate
            }
        }
        return null
    }

    /** 1-based number of the key in use, for display. */
    @Synchronized
    fun activeSlot(): Int = active + 1
}
