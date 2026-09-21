package com.jarvis.android.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CartoonAvatarTest {
    private fun animation() = HoloAvatar(HeadMesh.parse(File("src/main/assets/avatar/head_mesh.bin").readBytes()))

    @Test fun `the cartoon face is a choice of the settings`() {
        val cartoon = AVATAR_FACES.filter { it.cartoon }
        assertEquals(1, cartoon.size)
        assertTrue(AVATAR_FACES.indexOf(cartoon.single()) == AVATAR_FACES.lastIndex)
        assertTrue(File("src/main/assets/" + cartoon.single().asset).isFile)
    }

    @Test fun `the eyes open and shut with the blink and the lids`() {
        assertEquals(1f, eyeOpenness(0f, 1f), 1e-6f)
        assertTrue(eyeOpenness(1f, 1f) <= 0.05f)            // a blink shuts them
        assertTrue(eyeOpenness(0f, 0.22f) < 0.3f)           // asleep the lids are low
        assertTrue(eyeOpenness(0f, 0.94f) > 0.9f)           // thinking lowers them a little
        assertTrue(eyeOpenness(5f, -3f) in 0.03f..1f)       // out of range values are held
    }

    @Test fun `the four skin tones are different and go from light to dark`() {
        val tones = (1..4).map { skinPalette(it).base }
        assertEquals(4, tones.toSet().size)
        val brightness = tones.map { it.red + it.green + it.blue }
        assertEquals(brightness.sortedDescending(), brightness)
        assertEquals(skinPalette(1).base, skinPalette(0).base)      // the glowing web is drawn as the light skin
    }

    @Test fun `each lip colour is its own and the natural one follows the skin`() {
        val skin = skinPalette(2)
        val colours = (0..4).map { lipColour(it, skin) }
        assertEquals(5, colours.toSet().size)
        assertNotEquals(lipColour(0, skinPalette(1)), lipColour(0, skinPalette(4)))
    }

    @Test fun `the pose read from the animation stays in range while it runs`() {
        val a = animation()
        val frames = listOf(AudioViseme(open = 1f, level = 1f, wide = 1f), AudioViseme(open = 0.4f, level = 0.5f, wide = -1f))
        repeat(200) { i ->
            a.step(0.033f, 0.6f, speaking = i % 3 != 0, mood = Mood.LISTENING, frames = if (i % 2 == 0) frames else null)
            val pose = cartoonPose(a)
            assertTrue(pose.mouth in 0f..1f)
            assertTrue(pose.wide in -1f..1f)
            assertTrue(pose.brow in -0.4f..1.2f)
            assertTrue(pose.eyeOpen in 0.03f..1f)
            assertTrue(pose.gazeX in -1f..1f && pose.gazeY in -1f..1f)
        }
    }

    @Test fun `speaking opens the mouth and silence closes it`() {
        val a = animation()
        repeat(60) { a.step(0.033f, 0.9f, speaking = true, mood = Mood.IDLE, frames = null) }
        val talking = cartoonPose(a).mouth
        repeat(90) { a.step(0.033f, 0f, speaking = false, mood = Mood.IDLE, frames = null) }
        assertTrue("talking $talking", talking > 0.2f)
        assertTrue(cartoonPose(a).mouth < 0.05f)
    }
}
