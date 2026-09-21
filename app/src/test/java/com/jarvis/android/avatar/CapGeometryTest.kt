package com.jarvis.android.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CapGeometryTest {
    private val mesh: HeadMesh by lazy { HeadMesh.parse(File("src/main/assets/avatar/head_mesh.bin").readBytes()) }

    @Test fun `the dome is a whole grid with every ring, every column and the top`() {
        val g = CapGeometry(mesh)
        val n = g.rings * g.columns + 1
        assertEquals(n, g.top + 1)
        assertEquals(3 * n, g.bind.size); assertEquals(3 * n, g.weight.size); assertEquals(n, g.stand.size)
        assertTrue(g.bind.all { it in 0 until mesh.vertexCount })
        for (q in 0 until n) {
            val w = g.weight[3 * q] + g.weight[3 * q + 1] + g.weight[3 * q + 2]
            assertTrue("weights of $q add up to $w", w in 0.999f..1.001f)
            assertTrue(g.stand[q] in 0.02f..0.20f)
        }
        // two triangles per quad between rings, and a fan at the top: no hole in the grid
        assertEquals(3 * (2 * (g.rings - 1) * g.columns + g.columns), g.tris.size)
        assertTrue(g.tris.all { it in 0 until n })
    }

    @Test fun `the visor is a run of neighbouring columns at the front`() {
        val g = CapGeometry(mesh)
        assertTrue("visor columns: ${g.visor.size}", g.visor.size in 6..(g.columns / 2))
        for (k in 1 until g.visor.size) assertEquals(g.visor[k - 1] + 1, g.visor[k])
        val middle = g.columns / 2
        assertTrue(g.visor.first() < middle && g.visor.last() >= middle - 1)   // it spans the front
    }

    @Test fun `the hair under the cap is hidden, the hair below it is not`() {
        val g = CapGeometry(mesh)
        val hiddenHair = (0 until mesh.faceCount).count { mesh.faceGroup[it] > 1.5f && g.hiddenFace[it] }
        val shownHair = (0 until mesh.faceCount).count { mesh.faceGroup[it] > 1.5f && !g.hiddenFace[it] }
        assertTrue("hidden $hiddenHair, shown $shownHair", hiddenHair > 500 && shownHair > 500)
        assertTrue((0 until mesh.faceCount).none { mesh.faceGroup[it] <= 1.5f && g.hiddenFace[it] })   // the skin is never hidden
        assertEquals(mesh.lockCount, g.hiddenLock.size)
        assertTrue(g.hiddenLock.any { it } && g.hiddenLock.any { !it })
    }

    @Test fun `the edge is highest at the front and lowest at the back`() {
        assertTrue(CapGeometry.rim(0f, 0.5f) > CapGeometry.rim(0f, -0.6f))
        assertTrue(CapGeometry.rim(0.6f, -0.14f) > 0.3f)   // above the ears
    }
}
