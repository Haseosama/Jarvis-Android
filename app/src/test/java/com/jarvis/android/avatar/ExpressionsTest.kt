package com.jarvis.android.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

class ExpressionsTest {
    private val mesh: HeadMesh by lazy { HaseoFace.refine(HeadMesh.parse(File("src/main/assets/avatar/head_mesh.bin").readBytes())) }

    private fun expressionOf(text: String) = feelingOf(text)?.expression

    // ── what the words show ─────────────────────────────────────────────────

    @Test fun `good news smiles, bad news worries, surprise lifts the brows`() {
        assertEquals(Expression.JOY, expressionOf("Parfait, c’est fait !"))
        assertEquals(Expression.JOY, expressionOf("Bon anniversaire, Haseo"))
        assertEquals(Expression.CONCERN, expressionOf("Désolé, je n’ai pas pu envoyer le message"))
        assertEquals(Expression.CONCERN, expressionOf("Attention, vigilance orange aux orages"))
        assertEquals(Expression.SURPRISE, expressionOf("Oh, incroyable"))
        assertEquals(Expression.JOY, expressionOf("Great news, it worked"))
        assertEquals(Expression.CONCERN, expressionOf("Sorry, that failed"))
    }

    @Test fun `plain words show nothing and a question lifts the brows`() {
        assertNull(feelingOf("Il est quinze heures trente"))
        assertEquals(Expression.QUESTION, feelingOf("Voulez-vous que je l’appelle", ended = '?')?.expression)
        assertEquals(Expression.QUESTION, expressionOf("Je lance la musique ?"))
        // words are matched whole: "superbe" is not "super", "problématique" is not "problème"
        assertNull(feelingOf("Le superbus problematique"))
    }

    @Test fun `reassurance is not bad news`() {
        assertEquals(Expression.JOY, expressionOf("Pas de problème, je m’en occupe"))
        assertEquals(Expression.JOY, expressionOf("Aucune coupure prévue demain"))
        assertEquals(Expression.JOY, expressionOf("No problem"))
    }

    @Test fun `worry wins a tie and an exclamation strengthens`() {
        assertEquals(Expression.CONCERN, expressionOf("Super, mais attention"))
        val plain = feelingOf("C’est parfait")!!.strength
        val loud = feelingOf("C’est parfait !")!!.strength
        assertTrue("$loud should be more than $plain", loud > plain)
        assertTrue(feelingOf("Super, génial, parfait, bravo, excellent !")!!.strength <= 1f)
    }

    @Test fun `the tracker follows sentences as they come in pieces`() {
        val t = ExpressionTracker()
        assertEquals(Expression.NEUTRAL, t.feeling.expression)
        t.feed("Malheureu")
        assertEquals(Expression.NEUTRAL, t.feeling.expression)
        t.feed("sement, le train")
        assertEquals(Expression.CONCERN, t.feeling.expression)        // shows mid-sentence
        t.feed(" est annulé. Il est")
        assertEquals(Expression.CONCERN, t.feeling.expression)        // kept until the next sentence shows something
        t.feed(" midi.")
        assertEquals(Expression.NEUTRAL, t.feeling.expression)        // a plain sentence brings the face back
        t.feed("Vous venez ce soir ?")
        assertEquals(Expression.QUESTION, t.feeling.expression)
        t.reset()
        assertEquals(Feeling.NEUTRAL, t.feeling)
    }

    @Test fun `targets scale with strength and stay in range`() {
        assertEquals(ExpressionTargets(), expressionTargets(Feeling.NEUTRAL))
        val joy = expressionTargets(Feeling(Expression.JOY, 1f))
        val halfJoy = expressionTargets(Feeling(Expression.JOY, 0.5f))
        assertTrue(joy.smile > 0f && abs(halfJoy.smile - joy.smile / 2) < 1e-4f)
        assertTrue(expressionTargets(Feeling(Expression.CONCERN, 1f)).let { it.smile < 0f && it.inner > 0.5f })
        assertTrue(expressionTargets(Feeling(Expression.SURPRISE, 1f)).let { it.widen > 0.5f && it.brow > 0.5f && it.jaw > 0f })
        for (e in Expression.entries) {
            val g = expressionTargets(Feeling(e, 3f))   // strength is clamped
            assertTrue(g.smile in -1f..1f && g.inner in 0f..1f && g.widen in 0f..1f && g.jaw in 0f..1f)
        }
    }

    // ── the face ────────────────────────────────────────────────────────────

    private fun settle(av: HoloAvatar, feeling: Feeling, seconds: Float = 1.5f) {
        av.feeling = feeling
        av.yawOverride = 0f; av.pitchOverride = 0f; av.rollOverride = 0f
        val quiet = List(2) { AudioViseme(0f, 0f, 0f) }
        repeat((seconds * 30).toInt()) { av.step(1f / 30f, 0f, true, Mood.IDLE, quiet) }
        av.browOverride = 0f   // brows compared apart from their random phrase lift
        av.step(1f / 30f, 0f, true, Mood.IDLE, quiet)
        av.pose()
    }

    /** The height of the two corners of the lips (on the skin), of the middle of the lower lip, and of the mouth line's ends. */
    private fun mouth(av: HoloAvatar): Triple<Float, Float, Float> {
        val cx = mesh.lipCentre[0]
        val ring = mesh.landmarks.getValue("lips_out")
        val corners = listOf(ring.minBy { mesh.verts[3 * it] }, ring.maxBy { mesh.verts[3 * it] })
        val middle = ring.filter { mesh.verts[3 * it + 1] < mesh.lipCentre[1] }.minBy { abs(mesh.verts[3 * it] - cx) }
        val line = listOf(mesh.mouthUpper.first(), mesh.mouthUpper.last())
        fun y(vs: List<Int>) = vs.map { av.pv[3 * it + 1] }.average().toFloat()
        return Triple(y(corners), av.pv[3 * middle + 1], y(line))
    }

    @Test fun `a smile lifts the corners of the mouth and worry drops them`() {
        val neutral = HoloAvatar(mesh, Random(1)).also { settle(it, Feeling.NEUTRAL) }
        val happy = HoloAvatar(mesh, Random(1)).also { settle(it, Feeling(Expression.JOY, 1f)) }
        val worried = HoloAvatar(mesh, Random(1)).also { settle(it, Feeling(Expression.CONCERN, 1f)) }
        assertTrue(happy.smile > 0.6f && worried.smile < -0.3f)
        val (nc, nm, nl) = mouth(neutral)
        val (hc, hm, hl) = mouth(happy)
        val (wc, _, _) = mouth(worried)
        assertTrue("corners should rise: ${hc - nc}", hc - nc > 0.015f)
        assertTrue("the middle should barely move: ${hm - nm}", abs(hm - nm) < (hc - nc) * 0.4f)
        assertTrue("the mouth line should follow the lips: ${hl - nl} vs ${hc - nc}", hl - nl > (hc - nc) * 0.6f)
        assertTrue("corners should drop: ${wc - nc}", wc - nc < -0.008f)
        assertTrue(happy.pv.none { it.isNaN() } && worried.pv.none { it.isNaN() })
    }

    @Test fun `worry lifts the inner ends of the brows more than the outer ones`() {
        val neutral = HoloAvatar(mesh, Random(2)).also { settle(it, Feeling.NEUTRAL) }
        val worried = HoloAvatar(mesh, Random(2)).also { settle(it, Feeling(Expression.CONCERN, 1f)) }
        val ring = mesh.landmarks.getValue("brow_l")
        val mid = 0.5f * (mesh.eyeCentre[0] + mesh.eyeCentre[3])
        val innerV = ring.minBy { abs(mesh.verts[3 * it] - mid) }
        val outerV = ring.maxBy { abs(mesh.verts[3 * it] - mid) }
        val innerLift = worried.pv[3 * innerV + 1] - neutral.pv[3 * innerV + 1]
        val outerLift = worried.pv[3 * outerV + 1] - neutral.pv[3 * outerV + 1]
        assertTrue("inner $innerLift, outer $outerLift", innerLift > 0.01f && innerLift > outerLift + 0.01f)
    }

    @Test fun `surprise opens the eyes wider and parts the lips`() {
        val neutral = HoloAvatar(mesh, Random(3)).also { settle(it, Feeling.NEUTRAL) }
        val surprised = HoloAvatar(mesh, Random(3)).also { settle(it, Feeling(Expression.SURPRISE, 1f)) }
        assertTrue(surprised.widen > 0.6f && surprised.parted > 0.1f)
        val upper = (0 until mesh.vertexCount).filter { mesh.lid[it] > 0.01f }
        val rise = upper.map { surprised.pv[3 * it + 1] - neutral.pv[3 * it + 1] }.average()
        assertTrue("the upper lids should rise: $rise", rise > 0.0008)
        val jaw = (0 until mesh.vertexCount).filter { mesh.jaw[it] > 0.8f }
        val chin = jaw.map { surprised.pv[3 * it + 1] - neutral.pv[3 * it + 1] }.average()
        assertTrue("the jaw should drop a little: $chin", chin < -0.001)
    }

    @Test fun `the feeling fades once the voice has stopped, and asleep there is none`() {
        val av = HoloAvatar(mesh, Random(4))
        settle(av, Feeling(Expression.JOY, 1f))
        repeat(120) { av.step(1f / 30f, 0f, false, Mood.IDLE, null) }
        assertEquals(0f, av.smile, 0.02f)
        val sleeper = HoloAvatar(mesh, Random(5))
        sleeper.feeling = Feeling(Expression.SURPRISE, 1f)
        repeat(90) { sleeper.step(1f / 30f, 0f, true, Mood.ASLEEP, null) }
        assertEquals(0f, sleeper.widen, 0.02f)
    }

    @Test fun `listening keeps a faint warm smile`() {
        val av = HoloAvatar(mesh, Random(6))
        repeat(90) { av.step(1f / 30f, 0f, false, Mood.LISTENING, null) }
        assertTrue(av.smile in 0.05f..0.2f)
    }

    @Test fun `the drawn face smiles too`() {
        val av = HoloAvatar(mesh, Random(7))
        settle(av, Feeling(Expression.JOY, 1f))
        assertTrue(cartoonPose(av).smile > 0.6f)
    }

    // ── the phone's voice ───────────────────────────────────────────────────

    @Test fun `a word becomes mouth frames that open on vowels, shut on m b p and close after`() {
        val frames = wordVisemes("maman")
        assertTrue(frames.size in 8..30)
        assertTrue(frames.any { it.open > 0.5f && it.level > 0.3f })                 // the a
        assertEquals(0f, frames.first().open, 0f)                                   // the m: lips pressed
        assertTrue(frames.takeLast(2).all { it.level == 0f && it.open == 0f })      // the word ends shut
        assertTrue(wordVisemes("…").isEmpty())
        assertTrue(wordVisemes("bonjour", secondsPerUnit = 0.15f).size > wordVisemes("bonjour").size)
    }

    @Test fun `the word frames move the mouth like a voice`() {
        val av = HoloAvatar(mesh, Random(8))
        val frames = wordVisemes("Ah")
        var widest = 0f
        for (f in frames) { av.step(0.02f, 0f, true, Mood.IDLE, listOf(f)); widest = maxOf(widest, av.mouth) }
        assertTrue("mouth should open, was $widest", widest > 0.3f)
        repeat(30) { av.step(1f / 30f, 0f, false, Mood.IDLE, null) }
        assertEquals(0f, av.mouth, 0.02f)
    }
}
