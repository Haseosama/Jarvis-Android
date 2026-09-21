package com.jarvis.android.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class WakeLearningTest {
    private val rng = Random(11)

    private fun vec(scale: Float) = FloatArray(EMBEDDING_SIZE) { (rng.nextGaussian() * scale).toFloat() }
    private fun noisy(base: FloatArray, noise: Float) = FloatArray(base.size) { base[it] + (rng.nextGaussian() * noise).toFloat() }

    /** The sound of the word: eight successive embeddings that a user repeats with a little variation. */
    private val pattern = List(8) { vec(3f) }
    private val ambient = vec(1f)

    private fun quiet() = TeachStep(60f, noisy(ambient, 0.2f))
    private fun voiced(k: Int) = TeachStep(1_500f, noisy(pattern[k], 0.4f))

    /** A repetition as the teacher records it: 20 quiet steps, [wait] more, the word, then quiet until 38 steps after the prompt. */
    private fun repetition(wait: Int = 5, word: Boolean = true): TeachClip {
        val steps = mutableListOf<TeachStep>()
        repeat(TEACH_LEAD_STEPS) { steps += quiet() }
        val prompt = steps.size
        repeat(wait) { steps += quiet() }
        if (word) for (k in pattern.indices) steps += voiced(k)
        while (steps.size < prompt + TEACH_SPEAK_STEPS) steps += quiet()
        return TeachClip(steps, prompt)
    }

    /** Ordinary speech: every step loud and different from the word. */
    private fun background(steps: Int = TEACH_BACKGROUND_STEPS) = List(steps) { TeachStep(900f, noisy(vec(2f), 0.3f)) }

    @Test fun `the word is found between the quiet parts`() {
        val speech = findSpeech(repetition(wait = 5))!!
        assertEquals(TEACH_LEAD_STEPS + 5, speech.start)
        assertEquals(TEACH_LEAD_STEPS + 5 + pattern.size, speech.end)
    }

    @Test fun `nothing said, a click and a sound that never stops are not a word`() {
        assertNull(findSpeech(repetition(word = false)))
        val click = repetition(word = false).let { c -> TeachClip(c.steps.mapIndexed { i, s -> if (i == c.prompt + 4) TeachStep(2_000f, s.embedding) else s }, c.prompt) }
        assertNull(findSpeech(click))
        val endless = repetition().let { c -> TeachClip(c.steps.mapIndexed { i, s -> if (i > c.prompt + 3) TeachStep(2_000f, s.embedding) else s }, c.prompt) }
        assertNull(findSpeech(endless))
    }

    @Test fun `a pause inside the phrase does not cut it in two`() {
        val c = repetition(wait = 5)
        val gap = TeachClip(c.steps.mapIndexed { i, s -> if (i == c.prompt + 5 + 3 || i == c.prompt + 5 + 4) TeachStep(80f, s.embedding) else s }, c.prompt)
        val speech = findSpeech(gap)!!
        assertEquals(pattern.size, speech.end - speech.start)
    }

    @Test fun `a window needs sixteen warm embeddings`() {
        val steps = List(30) { TeachStep(10f, if (it < 9) null else vec(1f)) }
        assertNull(windowEndingAt(steps, 14))      // too early
        assertNull(windowEndingAt(steps, 23))      // would start on a step the models had not filled yet
        assertEquals(WINDOW_FLOATS, windowEndingAt(steps, 24)!!.size)
        assertNull(windowEndingAt(steps, 30))      // past the end
    }

    @Test fun `six repetitions and some ordinary speech make a solid word`() {
        val clips = List(TEACH_REPETITIONS) { repetition(wait = 3 + it) }
        val result = learnWord("  Debout Jarvis ", clips, background()) as LearnResult.Learned
        assertEquals("Debout Jarvis", result.word.label)
        assertEquals(TEACH_REPETITIONS, result.repetitions)
        assertEquals(LearnQuality.SOLID, result.quality)
        assertTrue(result.posMin > result.negMax + 0.3f)
        assertTrue(result.word.threshold in result.negMax + 0.12f..0.92f)

        // a new repetition is recognised, ordinary speech and the room are not
        val fresh = repetition(wait = 7)
        val end = findSpeech(fresh)!!.end
        val heard = (end - 1..end + 3).maxOf { result.word.score(windowEndingAt(fresh.steps, it)!!) }
        assertTrue("word scored $heard", heard >= result.word.threshold)
        val other = background()
        val ordinary = (15 until other.size).maxOf { result.word.score(windowEndingAt(other, it)!!) }
        assertTrue("ordinary speech scored $ordinary", ordinary < result.word.threshold)
        val room = List(60) { quiet() }
        assertTrue((15 until room.size).all { result.word.score(windowEndingAt(room, it)!!) < result.word.threshold })
    }

    @Test fun `too few audible repetitions is refused`() {
        val clips = List(3) { repetition() } + List(3) { repetition(word = false) }
        val result = learnWord("mot", clips, background())
        assertTrue(result is LearnResult.Failed)
    }

    @Test fun `repetitions that do not resemble each other are refused`() {
        val different = List(6) {
            val own = List(8) { vec(3f) }                 // a different sound each time
            val steps = mutableListOf<TeachStep>()
            repeat(TEACH_LEAD_STEPS + 5) { steps += quiet() }
            for (k in own.indices) steps += TeachStep(1_500f, noisy(own[k], 0.4f))
            while (steps.size < TEACH_LEAD_STEPS + TEACH_SPEAK_STEPS) steps += quiet()
            TeachClip(steps, TEACH_LEAD_STEPS)
        }
        assertTrue(learnWord("mot", different, background()) is LearnResult.Failed)
    }

    @Test fun `a short background is refused`() {
        val clips = List(TEACH_REPETITIONS) { repetition() }
        assertTrue(learnWord("mot", clips, background(30)) is LearnResult.Failed)
    }

    @Test fun `a word survives being written and read back`() {
        val clips = List(TEACH_REPETITIONS) { repetition(wait = 3 + it) }
        val word = (learnWord("Debout Jarvis", clips, background()) as LearnResult.Learned).word
        val bytes = word.toBytes()
        val back = LearnedWord.fromBytes(bytes)!!
        assertEquals("Debout Jarvis", back.label)
        assertEquals(word.threshold, back.threshold, 0f)
        assertEquals(word.templates.size, back.templates.size)
        assertEquals("Debout Jarvis", LearnedWord.labelOf(bytes))
        val probe = windowEndingAt(clips[0].steps, findSpeech(clips[0])!!.end)!!
        assertEquals(word.score(probe), back.score(probe), 1e-6f)
    }

    @Test fun `damaged files are not words`() {
        assertNull(LearnedWord.fromBytes(ByteArray(10)))
        assertNull(LearnedWord.fromBytes(byteArrayOf()))
        assertNull(LearnedWord.labelOf(byteArrayOf(1, 2, 3, 4, 5)))
        val good = (learnWord("mot", List(TEACH_REPETITIONS) { repetition() }, background()) as LearnResult.Learned).word.toBytes()
        assertNull(LearnedWord.fromBytes(good.copyOf(good.size / 2)))
        assertNotNull(LearnedWord.fromBytes(good))
    }

    @Test fun `the sensitivity moves the threshold of a learned word`() {
        val prudent = adjustLearnedThreshold(0.7f, wakeThresholdFor(0))
        val normal = adjustLearnedThreshold(0.7f, wakeThresholdFor(1))
        val sensitive = adjustLearnedThreshold(0.7f, wakeThresholdFor(2))
        assertEquals(0.7f, normal, 1e-6f)
        assertTrue(prudent > normal && normal > sensitive)
        assertTrue(adjustLearnedThreshold(0.4f, 0.15f) >= 0.35f)
    }
}
