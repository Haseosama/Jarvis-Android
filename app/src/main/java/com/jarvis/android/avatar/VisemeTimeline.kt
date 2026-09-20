package com.jarvis.android.avatar

import kotlin.math.max

/** What the mouth has to play at one instant: the frames whose 20 ms slot came due, and whether a voice is audible. */
internal data class TimelineSample(val frames: List<AudioViseme>, val speaking: Boolean)

/**
 * The mouth's clock. Each chunk of speech is stamped with the moment it will come out of the speaker (right after the
 * chunk before it, and not earlier than "now" plus the output latency), and the avatar reads the frames that have come
 * due since it last looked. So the lips follow what is heard, not what has merely arrived over the network.
 */
internal class VisemeTimeline(private val hopNs: Long = HOP_NS, private val tailNs: Long = TAIL_NS) {
    private class Chunk(val startNs: Long, val frames: List<AudioViseme>)

    private val chunks = ArrayDeque<Chunk>()
    private var endNs = 0L
    private var lastNs = 0L

    @Synchronized
    fun push(frames: List<AudioViseme>, nowNs: Long, latencyNs: Long = LATENCY_NS) {
        if (frames.isEmpty()) return
        val start = max(nowNs + latencyNs, endNs)
        chunks.addLast(Chunk(start, frames))
        endNs = start + frames.size * hopNs
    }

    /** Forgets everything not yet played (the user interrupted, or the session ended). */
    @Synchronized
    fun clear() {
        chunks.clear()
        endNs = 0L
    }

    @Synchronized
    fun sample(nowNs: Long): TimelineSample {
        if (lastNs == 0L || nowNs < lastNs) lastNs = nowNs - hopNs
        val due = ArrayList<AudioViseme>()
        val it = chunks.iterator()
        while (it.hasNext()) {
            val c = it.next()
            for (i in c.frames.indices) {
                val slot = c.startNs + i * hopNs
                if (slot > lastNs && slot <= nowNs) due += c.frames[i]
            }
            if (c.startNs + c.frames.size * hopNs <= nowNs) it.remove()
        }
        lastNs = nowNs
        val speaking = endNs != 0L && nowNs < endNs + tailNs
        return TimelineSample(if (due.size > MAX_BATCH) due.takeLast(MAX_BATCH) else due, speaking)
    }

    companion object {
        const val HOP_NS = 20_000_000L
        /** Time between handing audio to the output and hearing it; erring early is the safe direction for lip-sync. */
        const val LATENCY_NS = 40_000_000L
        const val TAIL_NS = 150_000_000L
        private const val MAX_BATCH = 15
    }
}
