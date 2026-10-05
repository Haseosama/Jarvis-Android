package com.jarvis.android.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ArBodyTest {
    private fun ys(mesh: BodyMesh) = (0 until mesh.pos.size / 3).map { mesh.pos[3 * it + 1] }
    private fun xs(mesh: BodyMesh) = (0 until mesh.pos.size / 3).map { mesh.pos[3 * it] }

    @Test
    fun `a figure five and a half heads tall, under the head, standing on its soles`() {
        val mesh = ArBody().build()
        assertTrue(mesh.triangleCount in 300..2000)
        assertEquals(BODY_FEET, ys(mesh).min(), 0.12f)
        // nothing pokes up through the face: the neck ends inside the head's lower half
        assertTrue("top ${ys(mesh).max()}", ys(mesh).max() < -0.3f)
        // shoulders and arms about as wide as two and a half heads
        val width = xs(mesh).max() - xs(mesh).min()
        assertTrue("width $width", width in 2.6f..4.2f)
        assertEquals(0f, xs(mesh).max() + xs(mesh).min(), 0.25f)
        assertTrue(mesh.part.all { it in JACKET..SKIN })
    }

    @Test
    fun `every facet faces out of its shape`() {
        val b = MeshBuilder()
        b.tube(floatArrayOf(0f, 0f, 0f), floatArrayOf(0.3f, -2f, 0.4f), 0.5f, 0.4f, JACKET)
        b.tube(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 1f), 0.3f, 0.2f, SHOES, squash = 0.7f)
        val m = b.mesh()
        val p = m.pos
        for (t in 0 until m.triangleCount) {
            val i = m.tris.sliceArray(3 * t until 3 * t + 3)
            val a = floatArrayOf(p[3 * i[0]], p[3 * i[0] + 1], p[3 * i[0] + 2])
            val ab = floatArrayOf(p[3 * i[1]] - a[0], p[3 * i[1] + 1] - a[1], p[3 * i[1] + 2] - a[2])
            val ac = floatArrayOf(p[3 * i[2]] - a[0], p[3 * i[2] + 1] - a[1], p[3 * i[2] + 2] - a[2])
            val n = cross(ab, ac)
            // the shape's middle: halfway along its axis
            val mid = if (t < m.triangleCount / 2) floatArrayOf(0.15f, -1f, 0.2f) else floatArrayOf(0f, 0f, 0.5f)
            val centroid = (0..2).map { j -> floatArrayOf(p[3 * i[j]], p[3 * i[j] + 1], p[3 * i[j] + 2]) }
                .reduce { x, y -> floatArrayOf(x[0] + y[0], x[1] + y[1], x[2] + y[2]) }.map { it / 3f }
            val out = floatArrayOf(centroid[0] - mid[0], centroid[1] - mid[1], centroid[2] - mid[2])
            assertTrue("facet $t faces in", n[0] * out[0] + n[1] * out[1] + n[2] * out[2] > 0f)
        }
    }

    @Test
    fun `speaking, the arms move and a hand comes up`() {
        val still = ArBody().also { repeat(60) { _ -> it.step(1f / 30f, 0f) } }
        val talking = ArBody().also { repeat(60) { _ -> it.step(1f / 30f, 0.6f) } }
        assertEquals(0f, still.talk, 1e-4f)
        assertTrue(talking.talk > 0.9f)
        // the highest point of the hands: higher, and further forward, when talking
        fun handTop(b: ArBody) = b.build().let { m -> (0 until m.triangleCount).filter { m.part[it] == SKIN }.flatMap { t -> (0..2).map { m.tris[3 * t + it] } }.filter { m.pos[3 * it + 1] < -2f }.maxOf { m.pos[3 * it + 1] } }
        assertTrue("${handTop(talking)} vs ${handTop(still)}", handTop(talking) > handTop(still) + 1f)
        // and the arms go back down once the voice stops
        repeat(150) { _ -> talking.step(1f / 30f, 0f) }
        assertTrue(talking.talk < 0.05f)
    }

    @Test
    fun `it breathes`() {
        val body = ArBody()
        val widths = (0 until 90).map { body.step(1f / 30f, 0f); body.build().let { m -> (0 until m.pos.size / 3).filter { abs(m.pos[3 * it + 1] + 2f) < 0.05f }.maxOf { m.pos[3 * it] } } }
        assertTrue(widths.max() - widths.min() > 0.01f)
    }

    @Test
    fun `limbs hang down at rest and swing forward`() {
        val down = ArBody.limbDirection(1f, 0f, 0f)
        assertEquals(-1f, down[1], 1e-6f)
        val out = ArBody.limbDirection(1f, 0.3f, 0f)
        assertTrue(out[0] > 0.2f)
        assertTrue(ArBody.limbDirection(-1f, 0.3f, 0f)[0] < -0.2f)
        assertTrue(ArBody.limbDirection(1f, 0f, 0.5f)[2] > 0.4f)
    }

    private val view = lookAt(0f, 0.30f, 0.70f, 0f, 0.19f, 0f)
    private val proj = perspective(0.75f, 1080f / 2160f)

    @Test
    fun `seen from in front, the figure stands upright on its feet, the face over the neck`() {
        val stage = ArStage()
        val p = stage.frame(1f / 30f, 0f, view, proj, 1080, 2160, 0f, 0f, 0f, 0f, 0.30f, 0.70f)
        assertNotNull(p)
        p!!
        val xsOnScreen = (0 until p.body.pos.size / 2).map { p.body.pos[2 * it] }
        val ysOnScreen = (0 until p.body.pos.size / 2).map { p.body.pos[2 * it + 1] }
        // the body's bottom near the feet's point, its top just under the head's centre
        assertEquals(p.shadowY, ysOnScreen.max(), 40f)
        val headCentreY = p.top + FACE_CENTRE_SHARE * p.side
        assertTrue(ysOnScreen.min() > headCentreY)
        assertTrue(ysOnScreen.min() < headCentreY + FACE_HEAD_SHARE * p.side)
        // centred on the screen, and the figure tall on it (about half the screen)
        assertEquals(540f, (xsOnScreen.min() + xsOnScreen.max()) / 2f, 30f)
        assertTrue(p.shadowY - headCentreY in 500f..1500f)
        // the face looks straight at the camera in front of it
        stage.aim!!.forEach { assertTrue(abs(it) < 0.2f) }
    }

    @Test
    fun `the facets turned away are not drawn, and the nearer ones are drawn last`() {
        val mesh = ArBody().build()
        val list = bodyDrawList(mesh, 0f, 0f, 0f, 0f, AR_UNIT, view, proj, 1080, 2160)!!
        assertTrue(list.count in mesh.triangleCount / 4 until mesh.triangleCount)
        assertTrue(list.light.all { it in 0f..1f })
        // from behind the figure, the first drawn of the front is not the same as from in front
        val back = bodyDrawList(mesh, 0f, 0f, 0f, Math.PI.toFloat(), AR_UNIT, view, proj, 1080, 2160)!!
        assertTrue(back.count in mesh.triangleCount / 4 until mesh.triangleCount)
    }

    @Test
    fun `turned round, its right goes to the other side`() {
        val mesh = ArBody().build()
        // the vertex furthest along +x (the figure's left hand side seen from in front)
        val i = (0 until mesh.pos.size / 3).maxBy { mesh.pos[3 * it] }
        val one = BodyMesh(floatArrayOf(mesh.pos[3 * i], mesh.pos[3 * i + 1], mesh.pos[3 * i + 2], 0f, -5f, 0f, 0f, -5f, 0.1f), intArrayOf(0, 1, 2), intArrayOf(0))
        fun screenX(facing: Float) = bodyDrawListPoints(one, facing)
        assertTrue(screenX(0f) > 540f)
        assertTrue(screenX(Math.PI.toFloat()) < 540f)
    }

    private fun bodyDrawListPoints(mesh: BodyMesh, facing: Float): Float {
        // both windings, so the single facet shows whichever way it faces
        val both = BodyMesh(mesh.pos, intArrayOf(0, 1, 2, 0, 2, 1), intArrayOf(0, 0))
        val list = bodyDrawList(both, 0f, 0f, 0f, facing, AR_UNIT, view, proj, 1080, 2160)!!
        return list.pos[0]   // the hand's point, first in both facets
    }

    @Test
    fun `behind the camera, nothing is placed`() {
        val stage = ArStage()
        assertNull(stage.frame(1f / 30f, 0f, view, proj, 1080, 2160, 0f, 0f, 2f, 0f, 0.30f, 0.70f))
    }

    @Test
    fun `moving the whole picture moves everything`() {
        val p = ArStage().frame(1f / 30f, 0f, view, proj, 1080, 2160, 0f, 0f, 0f, 0f, 0.30f, 0.70f)!!
        val q = p.shifted(100f, -50f)
        assertEquals(p.left + 100f, q.left, 1e-3f); assertEquals(p.shadowY - 50f, q.shadowY, 1e-3f)
        assertEquals(p.body.pos[0] + 100f, q.body.pos[0], 1e-3f); assertEquals(p.body.pos[1] - 50f, q.body.pos[1], 1e-3f)
    }

    @Test
    fun `the colours are skin on the hands, the theme on the seams, glass for the holograms`() {
        val primary = 0xFF00E5FF.toInt()
        assertEquals(0xFF, bodyColour(SKIN, 1f, 2, primary) ushr 24)
        val skin = bodyColour(SKIN, 0.775f, 1, primary)   // lit at 1.0: the tone itself
        assertEquals(0xF1C9A8, skin and 0xFFFFFF)
        val seam = bodyColour(TRIM, 0.775f, 1, primary)
        assertTrue((seam and 0xFF) > (seam shr 16 and 0xFF))   // bluish, from the cyan theme
        assertTrue(bodyColour(JACKET, 0.5f, 5, primary) ushr 24 < 0xFF)
        assertTrue(bodyColour(JACKET, 1f, 1, primary) and 0xFF > bodyColour(JACKET, 0f, 1, primary) and 0xFF)
    }
}
