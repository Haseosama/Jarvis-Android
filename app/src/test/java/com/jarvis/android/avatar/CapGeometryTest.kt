package com.jarvis.android.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CapGeometryTest {
    private val mesh: HeadMesh by lazy { HeadMesh.parse(File("src/main/assets/avatar/head_mesh.bin").readBytes()) }

    @Test fun `the dome and the visor are well formed`() {
        val g = CapGeometry(mesh)
        assertTrue("dome points: ${g.verts.size}", g.verts.size > 300)
        assertTrue(g.tris.size / 3 > 500)
        assertEquals(g.verts.size, g.lift.size)
        assertTrue(g.tris.all { it in g.verts.indices })
        assertTrue(g.top in g.verts.indices)
        assertTrue("visor points: ${g.visor.size}", g.visor.size >= 6)
        assertTrue(g.visor.all { it in g.verts.indices })
        // from left to right
        for (k in 1 until g.visorX.size) assertTrue(g.visorX[k] >= g.visorX[k - 1])
        assertTrue(g.lift.all { it in 0f..(CapGeometry.BAND + 0.001f) })
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
