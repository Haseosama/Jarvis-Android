package com.jarvis.android.car

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.jarvis.android.actions.PlayVideoTool
import com.jarvis.android.podcasts.PodcastSubscriptions
import com.jarvis.android.video.VideoHistory
import com.jarvis.android.video.VideoPanel
import com.jarvis.android.video.clampPan
import com.jarvis.android.video.zoomAround
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLibraryTest {
    private val sub = PodcastSubscriptions.Sub("Secrets d'Histoire", "France Télévisions", "https://feed.example/secrets.xml")
    private val episode = VideoHistory.Entry("https://audio.example/e.mp3", url = "https://audio.example/e.mp3", title = "Guillaume", positionS = 34, audio = true, artist = "Secrets d'Histoire")
    private val film = VideoHistory.Entry("yt:Way9Dexny3w", youtubeId = "Way9Dexny3w", title = "Dune", positionS = 600)

    @Test fun `the car's first page shows only folders that have something`() {
        assertEquals(listOf("news", "radios"), CarLibrary.children(CarLibrary.ROOT, emptyList(), emptyList(), listOf(film)).map { it.id })
        val full = CarLibrary.children(CarLibrary.ROOT, emptyList(), listOf(sub), listOf(episode))
        assertEquals(listOf("news", "radios", "podcasts", "resume"), full.map { it.id })
        assertTrue(full.none { it.playable })
    }

    @Test fun `radios put the ones played lately first, each once, and a video is never offered in the car`() {
        val radios = CarLibrary.children(CarLibrary.RADIOS, listOf("Nova", "FIP"), emptyList(), emptyList())
        assertEquals(listOf("radio:Nova", "radio:FIP", "radio:France Inter"), radios.take(3).map { it.id })
        assertEquals(1, radios.count { it.id.equals("radio:FIP", ignoreCase = true) })
        assertEquals("Écoutée récemment", radios[0].subtitle)
        assertEquals(listOf("podcast:https://feed.example/secrets.xml"), CarLibrary.children(CarLibrary.PODCASTS, emptyList(), listOf(sub), emptyList()).map { it.id })
        val resume = CarLibrary.children(CarLibrary.RESUME, emptyList(), emptyList(), listOf(film, episode))
        assertEquals(listOf("resume:https://audio.example/e.mp3"), resume.map { it.id })
        assertTrue(CarLibrary.children(CarLibrary.NEWS, emptyList(), emptyList(), emptyList()).all { it.playable && it.id.startsWith("news:") })
    }

    @Test fun `an episode kept for later plays again as sound, with its podcast's name`() {
        val v = episode.video()
        assertTrue(v.podcast && v.audioOnly)
        assertEquals(34, v.startAt)
        assertEquals("Secrets d'Histoire", v.artist)
        assertFalse(film.video().audioOnly)
    }

    @Test fun `a zoom keeps the point under the fingers in place, and never shows past the photo`() {
        val box = Size(1000f, 500f)
        val t = zoomAround(Offset(250f, 125f), box, Offset.Zero, 1f, 2f)
        // the point (250, 125) of the photo stays under (250, 125): c + (p - c) * s + t = p
        assertEquals(250f, 500f + (250f - 500f) * 2f + t.x, 0.01f)
        assertEquals(125f, 250f + (125f - 250f) * 2f + t.y, 0.01f)
        assertEquals(Offset(500f, 250f), clampPan(Offset(9_000f, 9_000f), 2f, box))
        assertEquals(Offset.Zero, clampPan(Offset(300f, -40f), 1f, box))
    }

    @Test fun `the words of a zoom give a point of the photo`() {
        assertEquals(0.2f to 0.2f, PlayVideoTool.zoomArea("haut_gauche"))
        assertEquals(0.8f to 0.8f, PlayVideoTool.zoomArea("en bas à droite"))
        assertEquals(0.5f to 0.5f, PlayVideoTool.zoomArea(""))
        val p = VideoPanel()
        assertFalse(p.command(VideoPanel.Command.Zoom(2f)))                // nothing shown
    }
}
