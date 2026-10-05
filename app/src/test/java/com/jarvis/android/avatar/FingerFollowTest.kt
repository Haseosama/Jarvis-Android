package com.jarvis.android.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class FingerFollowTest {
    private val mesh: HeadMesh by lazy { HeadMesh.parse(File("src/main/assets/avatar/head_mesh.bin").readBytes()) }

    private fun run(avatar: HoloAvatar, seconds: Float, mood: Mood = Mood.IDLE, finger: Pair<Float, Float>? = null) {
        var t = 0f
        while (t < seconds) {
            finger?.let { avatar.follow(it.first, it.second) }
            avatar.step(1f / 30f, 0f, false, mood, null)
            t += 1f / 30f
        }
    }

    /** Where the front of an eye (its pupil) is drawn: x right and y down, as on the screen. */
    private fun pupil(avatar: HoloAvatar, e: Int): Pair<Float, Float> {
        avatar.pose()
        val first = mesh.eyeFirst[e]; val count = mesh.eyeCount[e]
        val front = (first until first + count).sortedByDescending { avatar.pv[3 * it + 2] }.take(maxOf(1, count / 20))
        return front.map { avatar.pv[3 * it] }.average().toFloat() to -front.map { avatar.pv[3 * it + 1] }.average().toFloat()
    }

    @Test
    fun `the eyes aim from where they are at where the finger is`() {
        val straight = fingerGaze(0f, -0.2f, -0.2f)
        assertEquals(0f, straight[0], 1e-6f); assertEquals(0f, straight[1], 1e-6f)
        val lowRight = fingerGaze(0.8f, 1.5f, -0.2f)
        assertTrue(lowRight[0] > 0.3f && lowRight[1] > 0.6f)
        val farLeft = fingerGaze(-30f, -0.2f, -0.2f)
        assertEquals(-1f, farLeft[0], 1e-6f) // far off: the eyes at their full reach
        assertTrue(fingerGaze(-0.5f, -2f, 0f).let { it[0] < 0f && it[1] < 0f })
    }

    @Test
    fun `a finger below and to the right is looked at there on screen, the head turning a little`() {
        val still = HoloAvatar(mesh, Random(4)).also { run(it, 2f) }
        val following = HoloAvatar(mesh, Random(4)).also { run(it, 2f, finger = 1.2f to 2.0f) }
        assertTrue(following.following)
        assertTrue("eyes right: ${following.gaze[0]}", following.gaze[0] > 0.3f)
        assertTrue("eyes down: ${following.gaze[1]}", following.gaze[1] > 0.5f)
        assertTrue("head turned right", following.yaw > still.yaw + 0.05f)
        assertTrue("head bent down", following.pitch > still.pitch + 0.05f)
        // and the pupils are really drawn towards it, against the eye's centre on the same posed head
        for (e in mesh.eyeFirst.indices) {
            val (px, py) = pupil(following, e)
            val (sx, sy) = pupil(still, e)
            assertTrue("pupil $e right of the still one", px - sx > 0.005f)
            assertTrue("pupil $e below the still one", py - sy > 0.005f)
        }
    }

    @Test
    fun `once the finger lifts the eyes wander again`() {
        val a = HoloAvatar(mesh, Random(9))
        run(a, 1f, finger = -1f to 0f)
        assertTrue(a.following)
        run(a, 1.2f)
        assertFalse(a.following)
    }

    @Test
    fun `a finger arriving catches the eye with a blink`() {
        val a = HoloAvatar(mesh, Random(2))
        run(a, 0.5f)
        assertEquals(0f, a.blink, 0f)
        a.follow(0.3f, 0.3f)
        assertEquals(1f, a.blink, 0f)
    }

    @Test
    fun `asleep, closed eyes follow nothing`() {
        val touched = HoloAvatar(mesh, Random(4)).also { run(it, 3f, Mood.ASLEEP, finger = 2f to 2f) }
        val untouched = HoloAvatar(mesh, Random(4)).also { run(it, 3f, Mood.ASLEEP) }
        assertEquals(untouched.gaze[0], touched.gaze[0], 1e-5f)
        assertEquals(untouched.gaze[1], touched.gaze[1], 1e-5f)
        assertEquals(untouched.yaw, touched.yaw, 1e-5f)
    }
}
