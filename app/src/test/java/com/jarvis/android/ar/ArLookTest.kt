package com.jarvis.android.ar

import com.jarvis.android.avatar.HeadMesh
import com.jarvis.android.avatar.HoloAvatar
import com.jarvis.android.avatar.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class ArLookTest {
    /** A camera at the origin looking down -z, y up: the identity view. */
    private val identity = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    /** A perspective projection (column-major, as ARCore's) with a vertical field of [fovY] radians. */
    private fun perspective(fovY: Float, aspect: Float, near: Float = 0.1f, far: Float = 100f): FloatArray {
        val f = 1f / kotlin.math.tan(fovY / 2f)
        return FloatArray(16).also {
            it[0] = f / aspect; it[5] = f
            it[10] = (far + near) / (near - far); it[11] = -1f
            it[14] = 2f * far * near / (near - far)
        }
    }

    @Test
    fun `a point straight ahead lands in the middle of the screen`() {
        val p = projectPoint(identity, perspective(1.0f, 0.5f), 0f, 0f, -2f, 1080, 2160)
        assertNotNull(p)
        assertEquals(540f, p!!.x, 0.5f); assertEquals(1080f, p.y, 0.5f)
        assertEquals(2f, p.depth, 1e-4f)
    }

    @Test
    fun `higher is up the screen, right is right, nearer is bigger`() {
        val proj = perspective(1.0f, 0.5f)
        val up = projectPoint(identity, proj, 0.3f, 0.3f, -2f, 1080, 2160)!!
        assertTrue(up.x > 540f && up.y < 1080f)
        val near = projectPoint(identity, proj, 0f, 0f, -0.5f, 1080, 2160)!!
        val far = projectPoint(identity, proj, 0f, 0f, -2f, 1080, 2160)!!
        assertEquals(4f, near.scale / far.scale, 1e-3f)
        // a metre seen 1 m away with a 1-radian field: 1 / (2 tan 0.5) of the screen's height
        val oneMetre = projectPoint(identity, proj, 0f, 0f, -1f, 1080, 2160)!!
        assertEquals(2160f / (2f * kotlin.math.tan(0.5f)), oneMetre.scale, 0.5f)
    }

    @Test
    fun `a point behind the camera is not drawn`() {
        assertNull(projectPoint(identity, perspective(1.0f, 0.5f), 0f, 0f, 1f, 1080, 2160))
    }

    @Test
    fun `the face square puts the head's centre on the point`() {
        val head = ScreenPoint(500f, 900f, 0.6f, 1800f)
        val (left, top, side) = faceSquare(head, 5000f).toList()
        assertEquals(AR_HEAD_HALF * 1800f / FACE_HEAD_SHARE, side, 1e-2f)
        assertEquals(500f, left + side / 2f, 1e-2f)
        assertEquals(900f, top + FACE_CENTRE_SHARE * side, 1e-2f)
        assertEquals(800f, faceSquare(ScreenPoint(0f, 0f, 0.01f, 1e6f), 800f)[2], 1e-3f)
    }

    @Test
    fun `angles wrap the short way round`() {
        assertEquals(0.2f, angleBetween(3.1f, 3.1f + 0.2f - 2f * Math.PI.toFloat()), 1e-4f)
        assertEquals(-0.3f, angleBetween(0.1f, -0.2f), 1e-5f)
    }

    @Test
    fun `facing the camera, the face looks straight at it`() {
        val look = ArLook()
        val a = look.step(1f / 30f, 0f, 0f, 0f, 0f, 0f, 0.5f)
        a.forEach { assertEquals(0f, it, 1e-5f) }
    }

    @Test
    fun `walking round it, the head turns to you late and the eyes make up for it`() {
        val look = ArLook()
        look.step(1f / 30f, 0f, 0f, 0f, 0f, 0f, 0.5f)
        // the camera moves a quarter turn round the head at once: to the head's right (+x)
        val moved = look.step(1f / 30f, 0f, 0f, 0f, 0.5f, 0f, 0f)
        // the face still mostly looks where you were: it shows turned towards the screen's left (from the new place) ...
        assertTrue("yaw ${moved[0]}", moved[0] < -0.3f)
        // ... and its eyes swing right, towards you
        assertTrue("gaze ${moved[2]}", moved[2] > 0.5f)
        // after a few seconds the body has caught up: it faces you again
        var settled = moved
        repeat(150) { settled = look.step(1f / 30f, 0f, 0f, 0f, 0.5f, 0f, 0f) }
        assertEquals(0f, settled[0], 0.01f); assertEquals(0f, settled[2], 0.03f)
        assertEquals(kotlin.math.atan2(0.5f, 0f), look.facing!!, 0.02f)
    }

    @Test
    fun `the turn takes the short way round, whichever side you go`() {
        val look = ArLook()
        // the camera behind the seam of atan2 (-z), stepping across it
        look.step(1f / 30f, 0f, 0f, 0f, 0.01f, 0f, -0.5f)
        val across = look.step(1f / 30f, 0f, 0f, 0f, -0.01f, 0f, -0.5f)
        assertTrue(abs(across[0]) < 0.05f)
    }

    @Test
    fun `seen from above it lifts its face and its eyes to you`() {
        val look = ArLook()
        val a = look.step(1f / 30f, 0f, 0f, 0f, 0f, 0.4f, 0.4f)  // 45 degrees above
        assertTrue("pitch ${a[1]}", a[1] > 0.1f && a[1] <= ArLook.MAX_PITCH)
        assertTrue("gaze ${a[3]}", a[3] < -0.4f)
        val below = look.step(1f / 30f, 0f, 0f, 0f, 0f, -0.4f, 0.4f)
        assertTrue(below[1] < 0f && below[3] > 0f)
    }

    @Test
    fun `the further round you go, the more it shows, up to a limit`() {
        val small = ArLook().also { it.step(1f, 0f, 0f, 0f, 0f, 0f, 1f) }.step(0f, 0f, 0f, 0f, sin(0.3f), 0f, cos(0.3f))
        val big = ArLook().also { it.step(1f, 0f, 0f, 0f, 0f, 0f, 1f) }.step(0f, 0f, 0f, 0f, sin(2.5f), 0f, cos(2.5f))
        assertTrue(abs(small[0]) < abs(big[0]))
        assertEquals(ArLook.MAX_YAW, abs(big[0]), 1e-5f)
    }

    private val mesh: HeadMesh by lazy { HeadMesh.parse(File("src/main/assets/avatar/head_mesh.bin").readBytes()) }

    @Test
    fun `with someone to look at, the eyes rest on them and the head turns as told`() {
        val a = HoloAvatar(mesh, Random(3))
        a.aim = floatArrayOf(0.4f, 0f, -0.8f, 0.3f)
        var t = 0f
        while (t < 1.5f) { a.step(1f / 30f, 0f, false, Mood.IDLE, null); t += 1f / 30f }
        assertEquals(-0.8f, a.gaze[0], 0.05f); assertEquals(0.3f, a.gaze[1], 0.05f)
        // the head shows the turn asked, give or take the little idle sway kept
        assertTrue("yaw ${a.yaw}", abs(a.yaw - 0.4f) < 0.15f)
    }

    @Test
    fun `now and then the eyes glance aside, then come back`() {
        val a = HoloAvatar(mesh, Random(5))
        a.aim = floatArrayOf(0f, 0f, 0f, 0f)
        var away = 0; var on = 0
        var t = 0f
        while (t < 20f) {
            a.step(1f / 30f, 0f, false, Mood.IDLE, null); t += 1f / 30f
            if (t > 1f) { if (abs(a.gaze[0]) > 0.15f || abs(a.gaze[1]) > 0.15f) away++ else on++ }
        }
        assertTrue("away $away on $on", away > 0 && on > 2 * away)
    }
}
