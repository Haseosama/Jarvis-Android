package com.jarvis.android.avatar

import com.jarvis.android.memory.avatarModelIndex
import com.jarvis.android.memory.decodeLooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class HaseoTest {
    private val classique: HeadMesh by lazy { HeadMesh.parse(File("src/main/assets/avatar/head_mesh.bin").readBytes()) }
    private val haseo: HeadMesh by lazy { HaseoFace.refine(classique) }

    private fun moved(a: HeadMesh, b: HeadMesh, i: Int) = (0..2).maxOf { abs(a.verts[3 * i + it] - b.verts[3 * i + it]) }

    @Test fun `haseo is the last built-in face, on the classique scan, with his own eyes`() {
        val face = avatarFace(HASEO_INDEX)
        assertEquals("Haseo", face.label)
        assertEquals(4, HASEO_INDEX)                       // ConfigStore.avatarModelIndex counts on it
        assertEquals(BUILT_IN_FACES.lastIndex, HASEO_INDEX)
        assertEquals(avatarFace(0).asset, face.asset)
        assertTrue(face.sculptable())
        assertFalse(avatarFace(3).sculptable())             // the drawn face
    }

    @Test fun `a character chosen before haseo came keeps pointing at it`() {
        assertEquals(2, avatarModelIndex(2, savedWithHaseo = false))
        assertEquals(5, avatarModelIndex(4, savedWithHaseo = false))
        assertEquals(4, avatarModelIndex(4, savedWithHaseo = true))
        assertEquals(listOf("Soir" to "eyeSize=0.4;faceWidth=-0.2"), decodeLooks("Soir=eyeSize=0.4;faceWidth=-0.2\n=junk"))
    }

    @Test fun `the refined head keeps the rig and reshapes only the face`() {
        assertEquals(classique.vertexCount, haseo.vertexCount)
        assertTrue(classique.faces.contentEquals(haseo.faces))
        assertTrue(classique.paint.contentEquals(haseo.paint))
        assertFalse(haseo.verts.any { it.isNaN() } || haseo.normals.any { it.isNaN() })
        val changed = (0 until classique.vertexCount).count { moved(classique, haseo, it) > 1e-5f }
        assertTrue("$changed vertices moved", changed in 1_000..10_000)
        // nothing goes far, and the back of the head (z < -0.1) never moves
        assertTrue((0 until classique.vertexCount).all { moved(classique, haseo, it) < 0.12f })
        assertTrue((0 until classique.nHead).filter { classique.verts[3 * it + 2] < -0.1f }.all { moved(classique, haseo, it) == 0f })
        // the eyeballs are larger, round the same centres
        val e = 0
        val first = classique.eyeFirst[e]
        val r0 = (first until first + classique.eyeCount[e]).maxOf { abs(classique.verts[3 * it] - classique.eyeCentre[0]) }
        val r1 = (first until first + classique.eyeCount[e]).maxOf { abs(haseo.verts[3 * it] - haseo.eyeCentre[0]) }
        assertEquals(1.16f, r1 / r0, 0.01f)
    }

    @Test fun `the lid openings are wider and the two eyes match`() {
        fun width(m: HeadMesh, right: Boolean): Float {
            val xs = ArrayList<Float>()
            val mid = 0.5f * (m.eyeCentre[0] + m.eyeCentre[3])
            for (k in 0 until m.eyelidRim.size / 3) for (v in intArrayOf(m.eyelidRim[3 * k], m.eyelidRim[3 * k + 1])) {
                val x = m.verts[3 * v]
                if ((x > mid) == right) xs += x
            }
            return xs.max() - xs.min()
        }
        for (right in listOf(true, false)) assertTrue(width(haseo, right) > width(classique, right) * 1.05f)
        assertEquals(width(haseo, true), width(haseo, false), 0.02f)
    }

    @Test fun `the polygon levels give the counts of jarvis 2 and keep every index valid`() {
        val counts = PolygonLevel.entries.associateWith { PolygonMesh.apply(haseo, it) }
        assertEquals(37_161, counts.getValue(PolygonLevel.MEDIUM).faceCount)
        assertEquals(84_555, counts.getValue(PolygonLevel.HIGH).faceCount)
        assertEquals(148_644, counts.getValue(PolygonLevel.ULTRA).faceCount)
        assertTrue(counts.getValue(PolygonLevel.ECO).faceCount < counts.getValue(PolygonLevel.LOW).faceCount)
        assertTrue(counts.getValue(PolygonLevel.LOW).faceCount < 37_161)
        for ((level, m) in counts) {
            assertTrue("$level", m.faces.all { it in 0 until m.vertexCount })
            assertEquals("$level", m.faceCount, m.faceGroup.size)
            assertEquals("$level", m.vertexCount, m.jaw.size)
            assertEquals("$level", m.vertexCount, m.paint.size)
            assertEquals("$level", level.webR0, m.webR0)
            // the old vertices keep their place: the landmarks, lids and lips still point at the same points
            for (i in 0 until haseo.vertexCount step 97) assertEquals(haseo.verts[3 * i], m.verts[3 * i])
        }
        assertSame(PolygonLevel.MEDIUM, PolygonLevel.of("nope"))
        assertSame(PolygonLevel.ULTRA, PolygonLevel.of("ultra"))
    }

    @Test fun `the sliders reshape the face, round trip and stay in range`() {
        assertSame(haseo, FaceCustomizer.apply(haseo, emptyMap()))
        val wide = FaceCustomizer.apply(haseo, mapOf("jawWidth" to 1f))
        val narrow = FaceCustomizer.apply(haseo, mapOf("jawWidth" to -1f))
        fun jaw(m: HeadMesh) = (0 until m.nHead).filter { m.verts[3 * it + 1] in -0.8f..-0.6f && m.verts[3 * it + 2] > 0.1f }.let { s -> s.maxOf { m.verts[3 * it] } - s.minOf { m.verts[3 * it] } }
        assertTrue(jaw(wide) > jaw(haseo) && jaw(haseo) > jaw(narrow))
        val big = FaceCustomizer.apply(haseo, mapOf("eyeSize" to 1f))
        assertFalse(big.verts.any { it.isNaN() })
        val first = haseo.eyeFirst[0]
        fun spread(m: HeadMesh) = (first until first + haseo.eyeCount[0]).maxOf { abs(m.verts[3 * it] - m.eyeCentre[0]) }
        assertEquals(1.3f, spread(big) / spread(haseo), 0.02f)        // the eyeballs grow with the slider, round their moved centres
        assertEquals(mapOf("eyeSize" to 1f, "noseWidth" to -0.33f), FaceCustomizer.normalize(mapOf("eyeSize" to 3f, "noseWidth" to -0.333f, "bogus" to 1f, "mouthWidth" to 0f)))
        val r = FaceCustomizer.random(42)
        assertEquals(r, FaceCustomizer.random(42))
        assertEquals(r, FaceCustomizer.decode(FaceCustomizer.encode(r)))
        assertTrue(r.values.all { it in -0.65f..0.65f })
        assertEquals(FaceCustomizer.PARAMS.size, FaceCustomizer.IDS.toSet().size)
        assertTrue(FaceCustomizer.PRESETS.all { (_, _, v) -> v.keys.all { it in FaceCustomizer.IDS } })
    }

    @Test fun `retouches move welded twins together, round trip and leave the hair alone`() {
        val topo = MeshSculpt.topology(haseo)
        assertTrue(topo.limit < haseo.vertexCount)                 // the hair is after the editable part
        assertTrue((0 until haseo.faceCount).filter { haseo.faceGroup[it] > 1.5f }.all { t -> (0..2).all { haseo.faces[3 * t + it] >= topo.limit } })
        val twin = topo.members.entries.first { it.value.size > 1 }
        val offsets = MeshSculpt.addDelta(emptyMap(), topo, mapOf(twin.key to 1f), floatArrayOf(0.01f, 0f, 0f))
        assertEquals(twin.value.toSet(), offsets.keys)
        val sculpted = MeshSculpt.apply(haseo, offsets)
        for (i in twin.value) assertEquals(haseo.verts[3 * i] + 0.01f, sculpted.verts[3 * i], 1e-5f)
        assertEquals(offsets.keys, MeshSculpt.decode(MeshSculpt.encode(offsets)).keys)
        // an offset on the hair or out of range is ignored
        assertSame(haseo, MeshSculpt.apply(haseo, mapOf(haseo.vertexCount - 1 to floatArrayOf(0.1f, 0f, 0f))))
        assertTrue(MeshSculpt.normalize(mapOf(3 to floatArrayOf(5f, Float.NaN, 0f)))[3]!!.contentEquals(floatArrayOf(MeshSculpt.MAX_OFFSET, 0f, 0f)))
        assertEquals("Lea.txt", SculptStore.fileName("Léa"))
    }

    @Test fun `the soft influence follows the surface and fades out`() {
        val topo = MeshSculpt.topology(haseo)
        val lids = MeshSculpt.selectEyelids(haseo, topo, left = true)
        assertTrue(lids.size > 50)
        val left = haseo.eyeCentre.let { minOf(it[0], it[3]) }
        assertTrue(lids.all { haseo.verts[3 * it] < 0.5f * (haseo.eyeCentre[0] + haseo.eyeCentre[3]) && abs(haseo.verts[3 * it] - left) < 0.3f })
        val w = MeshSculpt.softWeights(topo, haseo.verts, lids, 0.05f)
        assertTrue(lids.all { w[it] == 1f })
        assertTrue(w.size > lids.size && w.values.all { it in 0f..1f })
        assertTrue(MeshSculpt.grow(topo, lids).size > lids.size && MeshSculpt.shrink(topo, lids).size < lids.size)
    }

    @Test fun `copying one side on the other makes the face symmetric and leaves the source untouched`() {
        val topo = MeshSculpt.topology(haseo)
        val mid = 0.5f * (haseo.eyeCentre[0] + haseo.eyeCentre[3])
        val plane = MeshSculpt.symmetryPlane(haseo, topo, mid)
        assertTrue(abs(plane - mid) < 0.1f)
        // push the right lids out of the face, then copy the left side onto the right: the right side is pulled back towards its mirror
        val right = MeshSculpt.selectEyelids(haseo, topo, left = false)
        val pushed = MeshSculpt.addDelta(emptyMap(), topo, right.associateWith { 1f }, floatArrayOf(0f, 0f, 0.03f))
        val res = MeshSculpt.symmetrize(haseo, topo, pushed, fromLeft = true, plane = plane)
        assertTrue(res.moved > 1000)
        val after = MeshSculpt.displayVerts(haseo.verts, res.offsets, topo.limit)
        for (r in topo.reps) if (haseo.verts[3 * r] < plane - 0.01f && r !in right) {
            assertEquals(haseo.verts[3 * r + 1], after[3 * r + 1], 1e-5f)    // the source side never moves
        }
        val lifted = right.map { after[3 * it + 2] - haseo.verts[3 * it + 2] }.average()
        // the surface is snapped back onto the mirror of the other side, as in Jarvis 2.0 (which leaves the same 0.02 here)
        assertTrue("lifted $lifted", lifted < 0.024)
    }

    @Test fun `the editor picks what is in front of the camera`() {
        val topo = MeshSculpt.topology(haseo)
        val cam = MeshSculpt.Camera(width = 800f, height = 800f)
        val scene = MeshSculpt.scene(cam, haseo.verts, topo, haseo.normals)
        val hit = MeshSculpt.pickVertex(scene, topo, 400f, 400f, 28f)
        assertTrue(hit >= 0 && haseo.verts[3 * hit + 2] > 0f)               // on the face, not the back of the head
        val t = MeshSculpt.pickTriangle(scene, 400f, 400f)
        assertTrue(t >= 0 && MeshSculpt.trianglePoints(topo, t).size == 3)
        val boxed = MeshSculpt.boxSelect(scene, topo, 300f, 300f, 500f, 500f)
        assertTrue(boxed.isNotEmpty() && boxed.all { haseo.verts[3 * it + 2] > -0.2f })
        // a drag to the right moves the points to the viewer's right when seen from the front
        assertTrue(cam.screenToWorld(10f, 0f)[0] > 0f && cam.screenToWorld(0f, 10f)[1] < 0f)
    }
}
