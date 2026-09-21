package com.jarvis.android.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CircuitTracesTest {
    private val mesh: HeadMesh by lazy { HeadMesh.parse(File("src/main/assets/avatar/head_mesh.bin").readBytes()) }

    @Test fun `there are tracks, all well formed`() {
        val c = CircuitTraces(mesh)
        assertTrue("tracks: ${c.trackCount}", c.trackCount in 40..1500)
        assertTrue(c.count > 100)
        assertEquals(c.count, c.wu.size); assertEquals(c.count, c.fade.size)
        for (i in 0 until c.count) {
            assertTrue(c.wu[i] in -1e-4f..1.0001f && c.wv[i] in -1e-4f..1.0001f && c.wu[i] + c.wv[i] <= 1.0001f)
            for (t in intArrayOf(c.triA[i], c.triB[i], c.triC[i])) assertTrue(t in 0 until mesh.vertexCount)
        }
        for (s in c.segments) assertTrue(s in 0 until c.count)
        assertEquals(c.segments.size / 2, c.segTrack.size)
        assertTrue(c.segTrack.all { it in 0 until c.trackCount })
        assertTrue(c.segAlong.all { it in 0f..1f })
        assertEquals(2 * c.trackCount, c.pads.size)
    }

    @Test fun `the same tracks every time, and none on the hair, the eyes or the mouth`() {
        val a = CircuitTraces(mesh); val b = CircuitTraces(mesh)
        assertTrue(a.triA.contentEquals(b.triA) && a.segments.contentEquals(b.segments))
        for (i in 0 until a.count) {
            for (vtx in intArrayOf(a.triA[i], a.triB[i], a.triC[i])) assertEquals("point $i on a painted vertex", 0, mesh.paint[vtx])
        }
    }
}
