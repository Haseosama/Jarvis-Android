package com.jarvis.android.ar

import com.jarvis.android.avatar.PolygonLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class ArBodyTest {
    private val model = BodyModel.parse(File("src/main/assets/avatar/body_mesh.bin").readBytes())
    private fun ys(mesh: BodyMesh) = (0 until mesh.vertexCount).map { mesh.pos[3 * it + 1] }
    private fun xs(mesh: BodyMesh) = (0 until mesh.vertexCount).map { mesh.pos[3 * it] }

    @Test
    fun `a figure seven and a half heads tall, under the head, standing on its soles`() {
        val mesh = ArBody(model).build()
        assertEquals(BODY_FEET, ys(mesh).min(), 0.12f)
        // nothing pokes up through the face: the neck ends inside the head's lower half
        assertTrue("top ${ys(mesh).max()}", ys(mesh).max() < -0.2f)
        // broad shoulders and the arms hanging a little out: about three head widths
        val width = xs(mesh).max() - xs(mesh).min()
        assertTrue("width $width", width in 3.6f..5.6f)
        assertEquals(0f, xs(mesh).max() + xs(mesh).min(), 0.25f)
        assertTrue(mesh.part.all { it in JACKET..SKIN })
    }

    @Test
    fun `as many triangles as the head at each polygon level`() {
        // the head's (Haseo's, with his hair) at Eco, Léger, Standard and Haute définition: 20 588, 27 712, 37 161, 84 555
        val head = mapOf(PolygonLevel.ECO to 20_588, PolygonLevel.LOW to 27_712, PolygonLevel.MEDIUM to 37_161, PolygonLevel.HIGH to 84_555)
        for ((level, count) in head) {
            val body = ArBody(model).build(level).triangleCount
            assertTrue("$level: $body against $count", abs(body - count) < count * 0.1f)
        }
        assertEquals(ArBody(model).build(PolygonLevel.HIGH).triangleCount, ArBody(model).build(PolygonLevel.ULTRA).triangleCount)
    }

    @Test
    fun `the level can change between two frames`() {
        val body = ArBody(model)
        val fine = body.build(PolygonLevel.HIGH).triangleCount
        val coarse = body.build(PolygonLevel.ECO).triangleCount
        assertTrue(coarse * 3 < fine)
    }

    @Test
    fun `the triangles face out, and so does every vertex normal`() {
        val mesh = ArBody(model).build(PolygonLevel.ECO)
        val p = mesh.pos
        // each shape's axis is close by: compare against the normals instead, which point away from it
        var bad = 0
        for (t in 0 until mesh.triangleCount) {
            val i = mesh.tris.sliceArray(3 * t until 3 * t + 3)
            val a = floatArrayOf(p[3 * i[0]], p[3 * i[0] + 1], p[3 * i[0] + 2])
            val ab = floatArrayOf(p[3 * i[1]] - a[0], p[3 * i[1] + 1] - a[1], p[3 * i[1] + 2] - a[2])
            val ac = floatArrayOf(p[3 * i[2]] - a[0], p[3 * i[2] + 1] - a[1], p[3 * i[2] + 2] - a[2])
            val n = cross(ab, ac)
            if (length(n) < 1e-7f) continue
            val sum = (0..2).map { j -> floatArrayOf(mesh.nrm[3 * i[j]], mesh.nrm[3 * i[j] + 1], mesh.nrm[3 * i[j] + 2]) }
                .reduce { x, y -> floatArrayOf(x[0] + y[0], x[1] + y[1], x[2] + y[2]) }
            if (n[0] * sum[0] + n[1] * sum[1] + n[2] * sum[2] <= 0f) bad++
        }
        // the simplified sculpt keeps a few tiny folds (a few in a thousand at most), never whole sheets turned inside out
        assertTrue("$bad", bad < mesh.triangleCount / 300)
        // the normals are unit vectors, and on the torso's front they point forwards
        for (v in 0 until mesh.vertexCount) assertEquals(1f, length(floatArrayOf(mesh.nrm[3 * v], mesh.nrm[3 * v + 1], mesh.nrm[3 * v + 2])), 1e-3f)
        val front = (0 until mesh.vertexCount).maxBy { if (abs(p[3 * it + 1] + 3.4f) < 0.1f && abs(p[3 * it]) < 0.1f) p[3 * it + 2] else -9f }
        assertTrue(mesh.nrm[3 * front + 2] > 0.9f)
    }

    @Test
    fun `speaking, the arms move and a hand comes up`() {
        val still = ArBody(model).also { repeat(60) { _ -> it.step(1f / 30f, 0f) } }
        val talking = ArBody(model).also { repeat(60) { _ -> it.step(1f / 30f, 0.6f) } }
        assertEquals(0f, still.talk, 1e-4f)
        assertTrue(talking.talk > 0.9f)
        // the highest point of the hands: higher when talking
        fun handTop(b: ArBody) = b.build(PolygonLevel.ECO).let { m -> (0 until m.vertexCount).filter { m.part[it] == SKIN && m.pos[3 * it + 1] < -2f }.maxOf { m.pos[3 * it + 1] } }
        assertTrue("${handTop(talking)} vs ${handTop(still)}", handTop(talking) > handTop(still) + 1f)
        // and the arms go back down once the voice stops
        repeat(150) { _ -> talking.step(1f / 30f, 0f) }
        assertTrue(talking.talk < 0.05f)
    }

    @Test
    fun `it breathes`() {
        val body = ArBody(model)
        val widths = (0 until 90).map { body.step(1f / 30f, 0f); body.build(PolygonLevel.ECO).let { m -> (0 until m.vertexCount).filter { abs(m.pos[3 * it + 1] + 2.3f) < 0.06f && m.part[it] == JACKET && abs(m.pos[3 * it]) < 1.15f }.maxOf { m.pos[3 * it] } } }
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
        val stage = ArStage(model)
        val p = stage.frame(1f / 30f, 0f, view, proj, 1080, 2160, 0f, 0f, 0f, 0f, 0.30f, 0.70f)
        assertNotNull(p)
        p!!
        val xsOnScreen = (0 until p.body.pos.size / 2).map { p.body.pos[2 * it] }
        val ysOnScreen = (0 until p.body.pos.size / 2).map { p.body.pos[2 * it + 1] }
        // the body's bottom near the feet's point, its top just under the head's centre
        assertEquals(p.shadowY, ysOnScreen.max(), 60f)   // the toes come a little forward of the feet's point
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
    fun `the triangles turned away are not drawn, and the runs cover them all`() {
        val mesh = ArBody(model).build()
        val list = bodyDrawList(mesh, 0f, 0f, 0f, 0f, AR_UNIT, view, proj, 1080, 2160)!!
        assertTrue(list.count in mesh.triangleCount / 4 until mesh.triangleCount)
        assertEquals(list.count, list.chunkEnd.last())
        assertTrue(list.chunkEnd.toList().zipWithNext().all { (a, b) -> a <= b })
        val back = bodyDrawList(mesh, 0f, 0f, 0f, Math.PI.toFloat(), AR_UNIT, view, proj, 1080, 2160)!!
        assertTrue(back.count in mesh.triangleCount / 4 until mesh.triangleCount)
        // no web with a skin
        assertEquals(0, list.lines.size + list.nodes.size)
    }

    @Test
    fun `each run is handed to the canvas from offset 0, and together they are the whole body`() {
        val list = bodyDrawList(ArBody(model).build(), 0f, 0f, 0f, 0f, AR_UNIT, view, proj, 1080, 2160)!!
        val pos = FloatArray(list.count * 6); val colour = IntArray(list.count * 3)
        var done = 0
        for (c in 0 until BodyDrawList.CHUNKS) {
            val n = list.chunk(c, pos, colour)
            for (k in 0 until n * 6) assertEquals(list.pos[done * 6 + k], pos[k])
            for (k in 0 until n * 3) assertEquals(list.colour[done * 3 + k], colour[k])
            done += n
        }
        assertEquals(list.count, done)
    }

    @Test
    fun `turned round, its left goes to the other side`() {
        val mesh = ArBody(model).build(PolygonLevel.ECO)
        // the vertex furthest along +x (the figure's left, on the right of the screen seen from in front)
        val i = (0 until mesh.vertexCount).maxBy { mesh.pos[3 * it] }
        val pos = floatArrayOf(mesh.pos[3 * i], mesh.pos[3 * i + 1], mesh.pos[3 * i + 2], 0f, -5f, 0f, 0f, -5f, 0.1f)
        fun screenX(facing: Float): Float {
            // both windings, so the single triangle shows whichever way it faces
            val one = BodyMesh(pos, FloatArray(9) { if (it % 3 == 2) 1f else 0f }, IntArray(3), intArrayOf(0, 1, 2, 0, 2, 1))
            return bodyDrawList(one, 0f, 0f, 0f, facing, AR_UNIT, view, proj, 1080, 2160)!!.let { l -> l.pos[0] }
        }
        assertTrue(screenX(0f) > 540f)
        assertTrue(screenX(Math.PI.toFloat()) < 540f)
    }

    @Test
    fun `behind the camera, nothing is placed`() {
        val stage = ArStage(model)
        assertNull(stage.frame(1f / 30f, 0f, view, proj, 1080, 2160, 0f, 0f, 2f, 0f, 0.30f, 0.70f))
    }

    @Test
    fun `moving the whole picture moves everything, the web too`() {
        val web = BodyLook(0, 0xFF00E5FF.toInt(), 0xFF0B0F14.toInt(), PolygonLevel.ECO)
        val p = ArStage(model).frame(1f / 30f, 0f, view, proj, 1080, 2160, 0f, 0f, 0f, 0f, 0.30f, 0.70f, web)!!
        assertTrue(p.body.lines.isNotEmpty() && p.body.nodes.isNotEmpty())
        assertEquals(p.body.lines.size, p.body.lineStart.last())
        assertEquals(p.body.nodes.size, p.body.nodeStart.last())
        val q = p.shifted(100f, -50f)
        assertEquals(p.left + 100f, q.left, 1e-3f); assertEquals(p.shadowY - 50f, q.shadowY, 1e-3f)
        assertEquals(p.body.pos[0] + 100f, q.body.pos[0], 1e-3f); assertEquals(p.body.pos[1] - 50f, q.body.pos[1], 1e-3f)
        assertEquals(p.body.lines[0] + 100f, q.body.lines[0], 1e-3f); assertEquals(p.body.nodes[1] - 50f, q.body.nodes[1], 1e-3f)
    }

    @Test
    fun `the skin is the face's, the seams the theme's, lit by the same light`() {
        val primary = 0xFF00E5FF.toInt()
        val look = BodyLook(1, primary, 0xFF0B0F14.toInt(), PolygonLevel.MEDIUM)
        // a hand facing the light is the face's skin tone, lit as the face's skin is
        val lit = bodyVertexColour(SKIN, -0.55f, 0.5f, 0.67f, 0.67f, look, 0f)
        val dark = bodyVertexColour(SKIN, 0.55f, -0.5f, 0.67f, 0.67f, look, 0f)
        assertEquals(0xFF, lit ushr 24)
        assertTrue((lit shr 16 and 0xFF) > (dark shr 16 and 0xFF))
        assertTrue((lit shr 16 and 0xFF) > (lit and 0xFF))   // warm
        val seam = bodyVertexColour(TRIM, 0f, 0f, 1f, 1f, look, 0f)
        assertTrue((seam and 0xFF) > (seam shr 16 and 0xFF))   // bluish, from the cyan theme
        // the blue hologram: a blue skin, its contour deep blue
        val blue = BodyLook(7, primary, 0xFF0B0F14.toInt(), PolygonLevel.MEDIUM)
        val face = bodyVertexColour(SKIN, 0f, 0f, 1f, 1f, blue, 0f)
        val edge = bodyVertexColour(SKIN, 0f, 0f, 1f, 0.05f, blue, 0f)
        assertTrue((face and 0xFF) > (face shr 16 and 0xFF))
        assertTrue((edge and 0xFF) < (face and 0xFF))
    }
}
