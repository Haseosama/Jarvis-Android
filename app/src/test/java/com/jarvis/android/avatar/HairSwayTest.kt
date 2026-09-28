package com.jarvis.android.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt
import kotlin.random.Random

class HairSwayTest {
    private val lea = HeadMesh.parse(File("src/main/assets/avatar/head_mesh_lea.bin").readBytes())
    private val long = HairStyle.parse(File("src/main/assets/avatar/hair/women09_01.bin").readBytes())
        .fitOn(lea, HairStyle.Colours(0x1F2533, 0x0B0D13, 0x5B7496))

    /** How far the swinging head's hair is from where it would be fixed to the head, vertex by vertex, after the same moves. */
    private fun apart(mesh: HeadMesh, moves: (HoloAvatar) -> Unit): FloatArray {
        val a = HoloAvatar(mesh, Random(1)); val b = HoloAvatar(mesh.copyWith(hairSway = FloatArray(mesh.vertexCount)), Random(1))
        moves(a); moves(b); a.pose(); b.pose()
        return FloatArray(mesh.vertexCount) { i ->
            val dx = a.pv[3 * i] - b.pv[3 * i]; val dy = a.pv[3 * i + 1] - b.pv[3 * i + 1]; val dz = a.pv[3 * i + 2] - b.pv[3 * i + 2]
            sqrt(dx * dx + dy * dy + dz * dz)
        }
    }

    private fun turn(a: HoloAvatar, settle: Float) {
        a.yawOverride = -0.4f
        repeat(90) { a.step(1 / 30f, 0f, false, Mood.IDLE, null) }
        a.yawOverride = 0.4f
        repeat((settle * 30).toInt().coerceAtLeast(1)) { a.step(1 / 30f, 0f, false, Mood.IDLE, null) }
    }

    @Test fun `long hair lags behind a turn of the head, then settles`() {
        val hair = lea.vertexCount until long.vertexCount
        val justAfter = apart(long) { turn(it, 0.1f) }
        val later = apart(long) { turn(it, 3f) }
        val moved = hair.maxOf { justAfter[it] }
        assertTrue("the ends swing: $moved", moved > 0.05f)
        assertTrue("then settle: ${hair.maxOf { later[it] }}", hair.maxOf { later[it] } < moved / 3)
        // the face, and the hair lying on top of the skull, stay with the head
        assertEquals(0f, (0 until lea.vertexCount).maxOf { justAfter[it] }, 1e-5f)
        val top = hair.filter { long.verts[3 * it + 1] > 0.6f }
        assertTrue(top.maxOf { justAfter[it] } < 0.01f)
    }

    @Test fun `the head's own locks swing at their tips`() {
        val marc = HeadMesh.parse(File("src/main/assets/avatar/head_mesh_marc.bin").readBytes())
        val d = apart(marc) { turn(it, 0.1f) }
        val tips = (0 until marc.lockCount).map { l -> marc.lockFirst + (l * marc.lockRows + marc.lockRows - 1) * 3 }
        val roots = (0 until marc.lockCount).map { l -> marc.lockFirst + l * marc.lockRows * 3 }
        assertTrue(tips.maxOf { d[it] } > 0.005f)
        assertEquals(0f, roots.maxOf { d[it] }, 1e-5f)
    }
}
