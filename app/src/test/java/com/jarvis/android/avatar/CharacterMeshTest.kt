package com.jarvis.android.avatar

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CharacterMeshTest {
    /** The characters shipped in the public app (the others live in the debug assets, which git ignores). */
    private val folders = File("src/main/assets/avatar/characters").listFiles()?.filter { it.isDirectory }.orEmpty()

    private fun load(dir: File) = CharacterMesh.parse(File(dir, "mesh.bin").readBytes(), JSONObject(File(dir, "meta.json").readText()), null)

    @Test fun `the public app has its two characters`() {
        assertEquals(setOf("adam", "mei"), folders.map { it.name }.toSet())
    }

    @Test fun `only characters we may publish are in the public assets, and each names its author`() {
        for (dir in folders) {
            val meta = JSONObject(File(dir, "meta.json").readText())
            assertTrue(dir.name, meta.getBoolean("public"))
            assertTrue(dir.name, meta.getString("credit").contains("CC-BY-4.0") && meta.getString("credit").contains("sketchfab.com"))
            assertTrue(dir.name, File(dir, "atlas.webp").length() > 10_000)
        }
    }

    @Test fun `every character is a well formed bust within the triangle budget`() {
        for (dir in folders) {
            val c = load(dir)
            val name = dir.name
            assertTrue(name, c.faceCount in 1000..46_451)
            assertTrue(name, c.faces.all { it in 0 until c.vertexCount })
            assertTrue(name, c.uv.all { it in -0.001f..1.001f })
            assertTrue(name, c.headW.all { it in 0f..1f } && c.jaw.all { it in 0f..1f })
            // framed as the heads: the chin at -1, the eyes near 0, cut below the shoulders
            assertTrue(name, c.eyes.size in 1..2 && c.eyes.all { it.y in -0.3f..0.3f })
            val mouthY = (0 until c.mouth.size / 3).map { c.mouth[3 * it + 1] }
            assertTrue(name, mouthY.all { it in -0.8f..-0.5f })
            assertTrue(name, c.verts.indices.filter { it % 3 == 1 }.all { c.verts[it] > c.cut - 1e-3f })
            // the jaw moves the chin, not the forehead
            val jawTop = c.verts.indices.filter { it % 3 == 1 && c.jaw[it / 3] > 0.05f }.maxOf { c.verts[it] }
            assertTrue("$name jaw reaches $jawTop", jawTop < -0.4f)
        }
    }
}
