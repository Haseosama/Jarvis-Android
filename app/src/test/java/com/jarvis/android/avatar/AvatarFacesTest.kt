package com.jarvis.android.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AvatarFacesTest {
    /** The faces made of a mesh; the cartoon one is drawn and only borrows a file to build its animation. */
    private val faces = AVATAR_FACES.filter { !it.cartoon }

    private val meshes: List<HeadMesh> by lazy {
        faces.map { HeadMesh.parse(File("src/main/assets/" + it.asset).readBytes()) }
    }

    @Test fun `every face is a complete head with the same rig`() {
        for ((i, mesh) in meshes.withIndex()) {
            val name = faces[i].label
            assertEquals(name, mapOf("eye_l" to 16, "eye_r" to 16, "brow_l" to 5, "brow_r" to 5, "lips_out" to 20, "lips_in" to 20), mesh.landmarks.mapValues { it.value.size })
            assertEquals(name, 2, mesh.eyeCount.size)
            assertTrue(name, mesh.crown > mesh.bottom)
            assertEquals(name, mesh.vertexCount * 3, mesh.verts.size)
            assertTrue("$name has hair", mesh.faceGroup.count { it > 1.5f } > 5_000)
            // slicked-back hair (Léa) is mostly the smooth cap, with a few flat locks and a bun; the other styles have hundreds
            assertTrue("$name has locks", mesh.lockCount > 100 && mesh.lockRows >= 2)
        }
    }

    @Test fun `the eyes and the mouth stay where a face has them`() {
        for ((i, mesh) in meshes.withIndex()) {
            val name = faces[i].label
            for (e in 0..1) {
                val (x, y, z) = Triple(mesh.eyeCentre[3 * e], mesh.eyeCentre[3 * e + 1], mesh.eyeCentre[3 * e + 2])
                assertTrue("$name eye $e y=$y", y in -0.2f..0.25f)
                assertTrue("$name eye $e x=$x", kotlin.math.abs(x + 0.035f) in 0.15f..0.6f)
                assertTrue("$name eye $e z=$z", z > 0.0f)
            }
            assertTrue("$name lips y=${mesh.lipCentre[1]}", mesh.lipCentre[1] in -0.75f..-0.35f)
        }
    }

    @Test fun `the faces are different heads`() {
        assertEquals(faces.size, meshes.map { it.vertexCount }.toSet().size)
        // the depth of the head from the nose back differs (the nose tip itself is where each head is placed, the same for all), and the
        // jaw is not the same width
        val depth = meshes.map { m -> (0 until m.vertexCount).maxOf { m.verts[3 * it + 2] } - (0 until m.vertexCount).minOf { m.verts[3 * it + 2] } }
        assertEquals("$depth", faces.size, depth.map { "%.2f".format(it) }.toSet().size)
        fun jawWidth(m: HeadMesh): Float {
            // the skin only: the hair of a long style hangs across this height
            val skin = HashSet<Int>()
            for (t in 0 until m.faceCount) if (m.faceGroup[t] <= 1.5f) for (k in 0..2) skin += m.faces[3 * t + k]
            val near = skin.filter { m.verts[3 * it + 1] in -0.78f..-0.62f && m.verts[3 * it + 2] > 0.2f }
            return near.maxOf { m.verts[3 * it] } - near.minOf { m.verts[3 * it] }
        }
        val widths = meshes.map { jawWidth(it) }
        // three different heads (the scan, a woman's sculpt whose cheeks reach lower, a man's sculpt): at this height, just under the
        // mouth, their widths are clearly apart
        for (i in widths.indices) for (j in i + 1 until widths.size) {
            assertTrue("jaw widths $widths", kotlin.math.abs(widths[i] - widths[j]) > 0.04f)
        }
    }

    @Test fun `each face has its own eyebrow colour and an index outside the list is clamped`() {
        assertEquals(AVATAR_FACES.size, AVATAR_FACES.map { it.browColour }.toSet().size)
        assertEquals(AVATAR_FACES.first(), avatarFace(-3))
        assertEquals(AVATAR_FACES.last(), avatarFace(99))
        assertNotEquals(avatarFace(0).asset, avatarFace(1).asset)
    }

    @Test fun `lea reads more feminine, with finer brows and longer lashes than the other faces`() {
        val lea = AVATAR_FACES[1]
        assertTrue(lea.browScale < 1f)
        assertTrue(lea.lashScale > 1f)
        for (i in listOf(0, 2, 3)) {
            assertTrue(AVATAR_FACES[i].browScale == 1f)
            assertTrue(AVATAR_FACES[i].lashScale == 1f)
        }
    }

    @Test fun `no face is heavier than a quarter more than the original`() {
        val base = meshes[0].faceCount
        for ((i, mesh) in meshes.withIndex()) assertTrue("${faces[i].label}: ${mesh.faceCount} faces against $base", mesh.faceCount <= base * 1.25)
    }
}
