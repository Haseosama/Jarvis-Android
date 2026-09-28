package com.jarvis.android.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class HairStyleTest {
    private val choices = HairChoice.parseList(File("src/main/assets/avatar/hair/styles.json").readText())
    private val heads = listOf("head_mesh.bin", "head_mesh_lea.bin", "head_mesh_marc.bin").associateWith {
        HeadMesh.parse(File("src/main/assets/avatar/$it").readBytes())
    }
    private val colours = HairStyle.Colours(0x33302F, 0x151313, 0x4E4A46, grey = 0.2f)

    @Test fun `every style offered is there, and has a name in both languages`() {
        assertTrue(choices.size >= 20)
        assertEquals(choices.size, choices.map { it.id }.toSet().size)
        for (c in choices) {
            assertTrue(c.id, File("src/main/assets/${c.asset}").isFile)
            assertTrue(c.id, c.labelFr.isNotBlank() && c.labelEn.isNotBlank())
        }
    }

    @Test fun `any style on any head stays within the avatar's triangle budget`() {
        val budget = heads.getValue("head_mesh.bin").faceCount * 1.25
        for (c in choices) {
            val style = HairStyle.parse(File("src/main/assets/${c.asset}").readBytes())
            for ((name, head) in heads) {
                val fitted = style.fitOn(head, colours)
                assertTrue("${c.id} on $name: ${fitted.faceCount}", fitted.faceCount <= budget)
                assertTrue(fitted.faces.all { it in 0 until fitted.vertexCount })
                assertEquals(0, fitted.lockCount)
                // the head's own locks are gone, its skin is all there
                assertEquals((0 until head.faceCount).count { head.faceGroup[it] <= 1.5f }, (0 until fitted.faceCount).count { fitted.faceGroup[it] <= 1.5f })
                assertEquals(style.faceCount, (0 until fitted.faceCount).count { fitted.faceGroup[it] > 2.25f })
            }
        }
    }

    @Test fun `no strand ends up over the eyes, and the hair is coloured as hair`() {
        for (c in choices) {
            val style = HairStyle.parse(File("src/main/assets/${c.asset}").readBytes())
            for ((name, head) in heads) {
                val fitted = style.fitOn(head, colours)
                for (e in 0 until head.eyeCentre.size / 3) {
                    val ex = head.eyeCentre[3 * e]; val ey = head.eyeCentre[3 * e + 1]; val ez = head.eyeCentre[3 * e + 2]
                    val over = (head.vertexCount until fitted.vertexCount).count { i ->
                        abs(fitted.verts[3 * i] - ex) < 0.08f && abs(fitted.verts[3 * i + 1] - ey) < 0.04f && fitted.verts[3 * i + 2] > ez
                    }
                    // a side fringe may fall over an eye (a few dozen points); what the test is for is a style carrying its source head's
                    // eyeballs, or a strand set down inside an eye socket: thousands
                    assertTrue("${c.id} on $name: $over strand points over eye $e", over < 150)
                }
                assertTrue((head.vertexCount until fitted.vertexCount).all { (fitted.paint[it] ushr 24) == 254 })
            }
        }
    }

    @Test fun `a strand is dark at the roots, some are grey when asked`() {
        val root = HairStyle.strandColour(200, 0f, colours) and 0xFFFFFF
        val body = HairStyle.strandColour(200, 0.3f, colours) and 0xFFFFFF
        assertTrue((root shr 16) < (body shr 16))
        val greys = (0..255).count { k -> HairStyle.strandColour(k, 0.3f, colours).let { (it shr 16 and 0xFF) > 0x50 } }
        assertTrue("$greys", greys in 20..90)
    }

    @Test fun `a hair colour turns the face's own hair, and only the hair`() {
        val marc = heads.getValue("head_mesh_marc.bin")
        val face = avatarFace(2)
        val blond = hairShade("blond")!!
        val out = recolourHair(marc, face.hairColours, blond.colours)
        var hair = 0
        for (i in 0 until marc.vertexCount) {
            val p = marc.paint[i]; val q = out.paint[i]
            if (p == 0 || (p ushr 24) == 0xFF) { assertEquals(p, q); continue }       // the skin, the eyes, the mouth: as they were
            hair++
            assertEquals(p ushr 24, q ushr 24)                                        // the cover over the skin kept
            assertTrue((q shr 16 and 0xFF) >= (p shr 16 and 0xFF))                     // blond is lighter than Marc's dark hair
        }
        assertTrue(hair > 1000)
        assertEquals(HAIR_SHADES.size, HAIR_SHADES.map { it.id }.toSet().size)
    }
}
