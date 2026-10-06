package com.jarvis.android.ar

import com.jarvis.android.avatar.PolygonLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class ArRobotTest {
    private val model = RobotModel.parse(File("src/main/assets/avatar/robot_mesh.bin").readBytes())
    private val view = lookAt(0f, 0.26f, 0.55f, 0f, 0.12f, 0f)
    private val proj = perspective(0.75f, 1080f / 2160f)
    private fun ys(mesh: RobotMesh, where: (Int) -> Boolean = { true }) = (0 until mesh.vertexCount).filter(where).map { mesh.pos[3 * it + 1] }
    private fun level(i: Int) = model.levels[i]
    private fun of(part: Int, side: Int, w: Float = 0.9f): (Int) -> Boolean {
        val l = level(2)
        return { l.part[it] == part && l.side[it] == side && l.weight[it] > w }
    }
    private fun still() = ArRobot(model).also { r -> repeat(90) { r.step(1f / 30f, 0f) } }   // its hello waved

    @Test
    fun `a robot two units tall standing on its soles, its head on top, at every polygon level`() {
        assertEquals(4, model.levels.size)
        assertEquals(listOf(10_000, 16_000, 24_000, 40_000), model.levels.map { it.tris.size / 3 })
        for (l in PolygonLevel.values()) {
            val mesh = still().build(l)
            assertEquals(ROBOT_FEET, ys(mesh).min(), 0.03f)
            assertEquals(1f, ys(mesh).max(), 0.05f)
        }
        val l = level(2)
        val head = (0 until l.vertexCount).filter { l.part[it] == HEAD && l.weight[it] > 0.9f }
        assertTrue(head.size > l.vertexCount / 5)
        assertTrue(head.all { l.pos[3 * it + 1] > 0f })
        // both arms and both legs, each on its own side
        for (part in listOf(ARM, LEG)) for (s in listOf(-1, 1)) {
            val vs = (0 until l.vertexCount).filter(of(part, s))
            assertTrue("$part $s", vs.size > 200)
            assertTrue(vs.all { l.pos[3 * it] * s > 0f })
        }
        // its lights: the eyes on the front of the face, and the trim
        val eyes = (0 until l.vertexCount).filter { l.eye[it] > 0.5f }
        assertTrue(eyes.size > 10)
        assertTrue(eyes.all { l.pos[3 * it + 2] > 0.1f && l.pos[3 * it + 1] in 0.2f..0.65f })
        assertTrue((0 until l.vertexCount).count { l.glow[it] > 0.5f } > eyes.size)
    }

    @Test
    fun `the triangles face out`() {
        val mesh = still().build(PolygonLevel.ECO)
        val p = mesh.pos
        var bad = 0
        for (t in 0 until mesh.triangleCount) {
            val i = mesh.tris.sliceArray(3 * t until 3 * t + 3)
            val ab = floatArrayOf(p[3 * i[1]] - p[3 * i[0]], p[3 * i[1] + 1] - p[3 * i[0] + 1], p[3 * i[1] + 2] - p[3 * i[0] + 2])
            val ac = floatArrayOf(p[3 * i[2]] - p[3 * i[0]], p[3 * i[2] + 1] - p[3 * i[0] + 1], p[3 * i[2] + 2] - p[3 * i[0] + 2])
            val n = cross(ab, ac)
            if (length(n) < 1e-7f) continue
            val sum = (0..2).map { j -> floatArrayOf(mesh.nrm[3 * i[j]], mesh.nrm[3 * i[j] + 1], mesh.nrm[3 * i[j] + 2]) }
                .reduce { x, y -> floatArrayOf(x[0] + y[0], x[1] + y[1], x[2] + y[2]) }
            if (n[0] * sum[0] + n[1] * sum[1] + n[2] * sum[2] <= 0f) bad++
        }
        assertTrue("$bad", bad < mesh.triangleCount / 100)
        for (v in 0 until mesh.vertexCount) assertEquals(1f, length(floatArrayOf(mesh.nrm[3 * v], mesh.nrm[3 * v + 1], mesh.nrm[3 * v + 2])), 1e-3f)
        // the front of its face looks forwards
        val front = (0 until mesh.vertexCount).maxBy { if (abs(p[3 * it]) < 0.05f && abs(p[3 * it + 1] - 0.45f) < 0.1f) p[3 * it + 2] else -9f }
        assertTrue(mesh.nrm[3 * front + 2] > 0.8f)
    }

    @Test
    fun `put down, it waves with its right hand, then lets it fall`() {
        val r = ArRobot(model)
        val rightHand = of(ARM, -1); val leftHand = of(ARM, 1)
        val rest = still().build()
        val rightTop = ys(rest, rightHand).max(); val leftTop = ys(rest, leftHand).max()
        repeat(20) { r.step(1f / 30f, 0f) }
        assertTrue(ys(r.build(), rightHand).max() > rightTop + 0.4f)
        // the left hand stays down
        assertTrue(ys(r.build(), leftHand).max() < leftTop + 0.06f)
        repeat(70) { r.step(1f / 30f, 0f) }
        assertTrue(ys(r.build(), rightHand).max() < rightTop + 0.06f)
        // and waves again when put down somewhere else
        r.greet(); repeat(20) { r.step(1f / 30f, 0f) }
        assertTrue(ys(r.build(), rightHand).max() > rightTop + 0.4f)
    }

    @Test
    fun `speaking, its arms come forward and its eyes light up`() {
        val quiet = still()
        val talking = still().also { r -> repeat(30) { r.step(1f / 30f, 0.6f) } }
        assertTrue(talking.talk > 0.9f)
        fun handFront(r: ArRobot) = r.build().let { m -> (0 until m.vertexCount).filter(of(ARM, -1)).maxOf { m.pos[3 * it + 2] } }
        assertTrue("${handFront(talking)} vs ${handFront(quiet)}", handFront(talking) > handFront(quiet) + 0.2f)
        assertTrue(talking.build().voice > 0.9f)
        repeat(150) { talking.step(1f / 30f, 0f) }
        assertTrue(talking.talk < 0.05f)
    }

    @Test
    fun `its head turns to where it is told to look, its body stays`() {
        val r = still()
        val eyes = (0 until level(2).vertexCount).filter { level(2).eye[it] > 0.5f }
        val body = (0 until level(2).vertexCount).filter { level(2).part[it] == BODY && level(2).weight[it] < 0.01f && level(2).pos[3 * it + 2] > 0.2f }
        fun meanX(m: RobotMesh, vs: List<Int>) = vs.map { m.pos[3 * it] }.average().toFloat()
        val ahead = r.build(); val eyesAhead = meanX(ahead, eyes); val bodyAhead = meanX(ahead, body)
        repeat(30) { r.step(1f / 30f, 0f, yaw = 0.8f) }
        val turned = r.build()
        // turned to its left (+x)
        assertTrue(meanX(turned, eyes) > eyesAhead + 0.2f)
        assertEquals(bodyAhead, meanX(turned, body), 0.06f)
        // and no further than it can
        repeat(30) { r.step(1f / 30f, 0f, yaw = 3f) }
        assertTrue(meanX(r.build(), eyes) < 0.6f)
    }

    @Test
    fun `now and then its eyes go out for a blink`() {
        val r = ArRobot(model)
        val seen = ArrayList<Float>()
        repeat(30 * 8) { r.step(1f / 30f, 0f); seen += r.eyes }
        assertTrue(seen.min() < 0.3f)
        assertTrue(seen.count { it > 0.99f } > seen.size * 0.9f)
    }

    @Test
    fun `turning, it steps from one foot to the other, its soles staying on the table`() {
        val r = still()
        val lifted = HashSet<Int>()
        var lowest = 9f
        repeat(60) {
            r.step(1f / 30f, 0f, turnRate = 1.2f)
            val m = r.build()
            for (s in listOf(-1, 1)) if (ys(m, of(LEG, s)).min() > ROBOT_FEET + 0.03f) lifted += s
            lowest = minOf(lowest, ys(m).min())
        }
        assertEquals(setOf(-1, 1), lifted)
        assertTrue(lowest > ROBOT_FEET - 0.03f)
        // standing still, both feet down
        val calm = still().build()
        for (s in listOf(-1, 1)) assertEquals(ROBOT_FEET, ys(calm, of(LEG, s)).min(), 0.02f)
    }

    @Test
    fun `its lights glow brighter with the voice and go dark in a blink, the chrome is lit by the key light`() {
        val primary = 0xFF00E5FF.toInt()
        val teal = 0xFF30C8B8.toInt()
        val calm = robotVertexColour(teal, 0f, 0f, 1f, 1f, 1f, 1f, 1f, 0f, primary)
        val loud = robotVertexColour(teal, 0f, 0f, 1f, 1f, 1f, 1f, 1f, 1f, primary)
        val shut = robotVertexColour(teal, 0f, 0f, 1f, 1f, 1f, 1f, 0f, 0f, primary)
        fun sum(c: Int) = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
        assertTrue(sum(loud) > sum(calm))
        assertTrue(sum(shut) < sum(calm) / 2)
        // the trim (a light but not an eye) stays on in a blink
        assertEquals(calm, robotVertexColour(teal, 0f, 0f, 1f, 1f, 1f, 0f, 0f, 0f, primary))
        val grey = 0xFF909498.toInt()
        val lit = robotVertexColour(grey, -0.55f, 0.5f, 0.67f, 0.67f, 0f, 0f, 1f, 0f, primary)
        val dark = robotVertexColour(grey, 0.55f, -0.5f, 0.67f, 0.67f, 0f, 0f, 1f, 0f, primary)
        assertEquals(0xFF, lit ushr 24)
        assertTrue(sum(lit) > sum(dark))
    }

    @Test
    fun `seen from in front, it stands on its shadow in the middle of the screen, looking at you`() {
        val stage = ArStage(model)
        var p: ArPlacement? = null
        // once its hello is waved (while it waves, its arm reaches out to the side)
        repeat(90) { p = stage.frame(1f / 30f, 0f, view, proj, 1080, 2160, 0f, 0f, 0f, 0f, 0.26f, 0.55f) }
        assertNotNull(p)
        val xs = (0 until p!!.body.pos.size / 2).map { p!!.body.pos[2 * it] }
        val ys = (0 until p!!.body.pos.size / 2).map { p!!.body.pos[2 * it + 1] }
        assertEquals(p!!.shadowY, ys.max(), 80f)   // the toes come a little forward of the feet's point
        assertEquals(540f, (xs.min() + xs.max()) / 2f, 40f)
        assertTrue(ys.max() - ys.min() in 400f..1600f)
    }

    @Test
    fun `the triangles turned away are not drawn, the runs cover them all, each handed over from offset 0`() {
        val mesh = still().build()
        val list = robotDrawList(mesh, 0f, 0f, 0f, 0f, ROBOT_UNIT, view, proj, 1080, 2160)!!
        assertTrue(list.count in mesh.triangleCount / 4 until mesh.triangleCount)
        assertEquals(list.count, list.chunkEnd.last())
        val pos = FloatArray(list.count * 6); val colour = IntArray(list.count * 3)
        var done = 0
        for (c in 0 until FigureDrawList.CHUNKS) {
            val n = list.chunk(c, pos, colour)
            for (k in 0 until n * 6) assertEquals(list.pos[done * 6 + k], pos[k])
            for (k in 0 until n * 3) assertEquals(list.colour[done * 3 + k], colour[k])
            done += n
        }
        assertEquals(list.count, done)
    }

    @Test
    fun `turned round, its left goes to the other side`() {
        val mesh = still().build(PolygonLevel.ECO)
        val i = (0 until mesh.vertexCount).maxBy { mesh.pos[3 * it] }
        val pos = floatArrayOf(mesh.pos[3 * i], mesh.pos[3 * i + 1], mesh.pos[3 * i + 2], 0f, -0.9f, 0f, 0f, -0.9f, 0.1f)
        fun screenX(facing: Float): Float {
            // both windings, so the single triangle shows whichever way it faces
            val one = RobotMesh(pos, FloatArray(9) { if (it % 3 == 2) 1f else 0f }, IntArray(3) { -1 }, FloatArray(3), FloatArray(3), intArrayOf(0, 1, 2, 0, 2, 1))
            return robotDrawList(one, 0f, 0f, 0f, facing, ROBOT_UNIT, view, proj, 1080, 2160)!!.pos[0]
        }
        assertTrue(screenX(0f) > 540f)
        assertTrue(screenX(Math.PI.toFloat()) < 540f)
    }

    @Test
    fun `behind the camera, nothing is placed`() {
        assertNull(ArStage(model).frame(1f / 30f, 0f, view, proj, 1080, 2160, 0f, 0f, 2f, 0f, 0.26f, 0.55f))
    }

    @Test
    fun `moving the whole picture moves everything`() {
        val p = ArStage(model).frame(1f / 30f, 0f, view, proj, 1080, 2160, 0f, 0f, 0f, 0f, 0.26f, 0.55f)!!
        val q = p.shifted(100f, -50f)
        assertEquals(p.shadowX + 100f, q.shadowX, 1e-3f); assertEquals(p.shadowY - 50f, q.shadowY, 1e-3f)
        assertEquals(p.body.pos[0] + 100f, q.body.pos[0], 1e-3f); assertEquals(p.body.pos[1] - 50f, q.body.pos[1], 1e-3f)
    }
}
