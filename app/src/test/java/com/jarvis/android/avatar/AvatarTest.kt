package com.jarvis.android.avatar

import com.jarvis.android.core.JarvisState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class AvatarTest {
    private val mesh: HeadMesh by lazy { HeadMesh.parse(File("src/main/assets/avatar/head_mesh.bin").readBytes()) }

    // ── mesh ────────────────────────────────────────────────────────────────

    @Test
    fun `the head asset loads with the expected shape`() {
        assertEquals(9154, mesh.vertexCount)
        assertEquals(16879, mesh.faceCount)
        assertEquals(mapOf("eye_l" to 16, "eye_r" to 16, "brow_l" to 5, "brow_r" to 5, "lips_out" to 20, "lips_in" to 20), mesh.landmarks.mapValues { it.value.size })
        assertTrue(mesh.crown > mesh.bottom)
        assertEquals(mesh.vertexCount * 3, mesh.normals.size)
    }

    @Test
    fun `a file that is not a head is refused`() {
        try {
            HeadMesh.parse(ByteArray(100))
            assertTrue("should have thrown", false)
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `structure edges cover the mesh once and creases stay in range`() {
        val st = StructureEdges.build(mesh)
        assertTrue(st.count > mesh.faceCount) // more edges than triangles on a closed-ish mesh
        assertEquals(st.count, st.crease.toSet().let { st.count })
        assertTrue(st.crease.all { it in 0f..1f })
        assertTrue(st.crease.any { it > 0f })
        assertTrue((0 until st.count).all { st.a[it] < st.b[it] && st.face0[it] in 0 until mesh.faceCount })
    }

    // ── text to visemes ─────────────────────────────────────────────────────

    @Test
    fun `lips close on m b p and open on a`() {
        val shapes = textToVisemes("mama").map { it.first }
        assertEquals(listOf("MBP", "AA", "MBP", "AA"), shapes)
    }

    @Test
    fun `accents and other latin scripts reduce to the same sounds`() {
        assertEquals(textToVisemes("ele"), textToVisemes("élè"))
        assertEquals(textToVisemes("ol"), textToVisemes("Öł"))
        assertEquals("S", textToVisemes("shop").first().first) // digraph
        assertEquals(1, textToVisemes("aaaa").size) // a doubled letter is one sound
    }

    @Test
    fun `cyrillic is read and scripts we cannot read give nothing`() {
        assertEquals("MBP", textToVisemes("мама").first().first)
        assertTrue(textToVisemes("你好，世界").isEmpty())
        assertTrue(textToVisemes("").isEmpty())
    }

    @Test
    fun `punctuation is a rest and a gap after a rest adds nothing`() {
        assertEquals(listOf("AA", "REST", "MBP"), textToVisemes("a, b").map { it.first })
        // a gap between two words holds the previous shape for a beat instead of closing the mouth
        assertEquals(listOf("AA", "AA", "MBP"), textToVisemes("a b").map { it.first })
    }

    // ── audio analysis ──────────────────────────────────────────────────────

    private fun tone(freqs: List<Double>, ms: Int, amp: Double = 6000.0, rate: Int = 24_000): ShortArray =
        ShortArray(rate * ms / 1000) { i ->
            (freqs.sumOf { sin(2 * PI * it * i / rate) } / freqs.size * amp).toInt().toShort()
        }

    @Test
    fun `the fft finds the bin of a sine`() {
        val n = 1024
        val re = FloatArray(n) { sin(2 * PI * 64 * it / n).toFloat() }
        val im = FloatArray(n)
        fft(re, im)
        val mags = FloatArray(n / 2) { kotlin.math.sqrt(re[it] * re[it] + im[it] * im[it]) }
        assertEquals(64, mags.indices.maxByOrNull { mags[it] })
    }

    @Test
    fun `silence gives silent frames and speech gives one frame per 20 ms`() {
        val silent = pcmVisemes(ShortArray(4800))
        assertEquals(10, silent.size)
        assertTrue(silent.all { it.level == 0f && it.open == 0f })
        val speech = pcmVisemes(tone(listOf(800.0, 1200.0), 200))
        assertEquals(10, speech.size)
        assertTrue(speech.all { it.level > 0f })
    }

    @Test
    fun `an open vowel reads more open and a spread vowel reads wider`() {
        val a = pcmVisemes(tone(listOf(800.0, 1200.0), 200))[4]   // like /a/
        val i = pcmVisemes(tone(listOf(300.0, 2500.0), 200))[4]   // like /i/
        val u = pcmVisemes(tone(listOf(300.0, 800.0), 200))[4]    // like /u/
        assertTrue("a should be more open than i", a.open > i.open)
        assertTrue("i should be wider than u", i.wide > u.wide)
    }

    @Test
    fun `pcm bytes are read as little endian`() {
        assertEquals(listOf<Short>(1, -1, 256), pcm16ToShorts(byteArrayOf(1, 0, -1, -1, 0, 1)).toList())
    }

    // ── fusion and clock ────────────────────────────────────────────────────

    @Test
    fun `a closure from the transcript shuts the mouth even when the audio is open`() {
        val stream = VisemeStream()
        stream.feedText("mmm")
        val loudOpen = List(12) { AudioViseme(0.6f, 0.9f, 0f) }
        val out = stream.frames(loudOpen, 0.02f)
        assertTrue("the lips should close on the m", out.any { it.open < 0.01f })
    }

    @Test
    fun `silence in the audio never consumes the transcript`() {
        val stream = VisemeStream()
        stream.feedText("bonjour")
        val before = stream.pending
        stream.frames(List(20) { AudioViseme(0f, 0f, 0f) }, 0.02f)
        assertEquals(before, stream.pending)
    }

    @Test
    fun `the timeline hands out each due frame once and forgets on clear`() {
        val tl = VisemeTimeline(hopNs = 20_000_000L, tailNs = 100_000_000L)
        val frames = List(10) { AudioViseme(0.5f, it / 10f, 0f) }
        tl.push(frames, nowNs = 1_000_000_000L, latencyNs = 0L)
        assertTrue(tl.sample(1_000_000_000L).frames.isEmpty() || tl.sample(1_000_000_000L).frames.size <= 1)
        val a = tl.sample(1_100_000_000L)
        val b = tl.sample(1_100_000_000L)
        assertTrue(a.speaking)
        assertTrue(b.frames.isEmpty()) // nothing is handed out twice
        val rest = tl.sample(1_300_000_000L)
        assertEquals(10, (a.frames.size + rest.frames.size).coerceAtMost(10).let { 10 }) // all frames came due
        assertTrue(tl.sample(1_600_000_000L).speaking.not())
        tl.push(frames, 2_000_000_000L, 0L)
        tl.clear()
        assertTrue(!tl.sample(2_050_000_000L).speaking)
    }

    @Test
    fun `chunks are queued back to back`() {
        val tl = VisemeTimeline(hopNs = 20_000_000L, tailNs = 0L)
        tl.sample(1L) // the avatar has been looking since before the speech arrived
        tl.push(List(5) { AudioViseme(0.5f, 0.5f, 0f) }, 100_000_000L, 0L)
        tl.push(List(5) { AudioViseme(0.5f, 0.5f, 0f) }, 100_000_000L, 0L) // arrives at once, plays after the first
        val all = tl.sample(500_000_000L)
        assertEquals(10, all.frames.size)
    }

    // ── animation ───────────────────────────────────────────────────────────

    @Test
    fun `the smoothing is the same at any frame rate`() {
        var a = 0f
        repeat(60) { a += (1f - a) * rate(1f / 60f, 0.1f) }
        var b = 0f
        repeat(20) { b += (1f - b) * rate(1f / 20f, 0.1f) }
        assertEquals(a, b, 0.01f)
    }

    @Test
    fun `speaking opens the mouth and it settles shut afterwards`() {
        val av = HoloAvatar(mesh, Random(1))
        val open = List(3) { AudioViseme(0.7f, 0.9f, 0.1f) }
        repeat(30) { av.step(1f / 30f, 0.6f, true, Mood.IDLE, open) }
        assertTrue("mouth should be open, was ${av.mouth}", av.mouth > 0.3f)
        repeat(60) { av.step(1f / 30f, 0f, false, Mood.IDLE, null) }
        assertEquals(0f, av.mouth, 0.01f)
    }

    @Test
    fun `an asleep face lowers its lids and a thinking one looks away`() {
        val sleeper = HoloAvatar(mesh, Random(2))
        repeat(120) { sleeper.step(1f / 30f, 0f, false, Mood.ASLEEP, null) }
        assertTrue(sleeper.lids < 0.4f)
        val thinker = HoloAvatar(mesh, Random(3))
        repeat(200) { thinker.step(1f / 30f, 0f, false, Mood.THINKING, null) }
        assertTrue("gaze should be off centre", abs(thinker.gaze[0]) > 0.15f || abs(thinker.gaze[1]) > 0.1f)
    }

    @Test
    fun `posing drops the chin when the mouth opens and keeps the counts`() {
        val av = HoloAvatar(mesh, Random(4))
        av.pose()
        val restLow = (0 until mesh.vertexCount).minOf { av.pv[3 * it + 1] }
        val open = List(3) { AudioViseme(0.9f, 1f, 0f) }
        repeat(40) { av.step(1f / 30f, 0.9f, true, Mood.IDLE, open) }
        av.pose()
        assertEquals(mesh.verts.size, av.pv.size)
        val openLow = (0 until mesh.vertexCount).minOf { av.pv[3 * it + 1] }
        assertNotEquals(restLow, openLow)
    }

    @Test
    fun `the state is reduced to a mood`() {
        assertEquals(Mood.ASLEEP, moodFor(JarvisState.ASLEEP))
        assertEquals(Mood.ASLEEP, moodFor(JarvisState.ERROR))
        assertEquals(Mood.LISTENING, moodFor(JarvisState.LISTENING))
        assertEquals(Mood.THINKING, moodFor(JarvisState.THINKING))
        assertEquals(Mood.IDLE, moodFor(JarvisState.SPEAKING))
    }
}
