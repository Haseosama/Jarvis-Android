package com.jarvis.android.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPanelTest {
    @Test fun `a YouTube video is found in any of its links`() {
        val id = "Way9Dexny3w"
        for (link in listOf(
            "https://www.youtube.com/watch?v=$id", "https://youtube.com/watch?feature=share&v=$id&t=10", "https://m.youtube.com/watch?v=$id",
            "https://youtu.be/$id?si=abc", "https://www.youtube.com/shorts/$id", "https://www.youtube-nocookie.com/embed/$id",
            "https://www.youtube.com/live/$id",
        )) assertEquals(link, id, VideoPanel.youtubeId(link))
        assertNull(VideoPanel.youtubeId("https://www.youtube.com/results?search_query=dune"))
        assertNull(VideoPanel.youtubeId("https://evil.example/watch?v=$id"))
        assertNull(VideoPanel.youtubeId("https://youtu.be/not-an-id"))
    }

    @Test fun `a video file is told by its address`() {
        assertTrue(VideoPanel.isVideoFile("https://example.org/media/clip.mp4"))
        assertTrue(VideoPanel.isVideoFile("https://example.org/live/stream.m3u8?token=1"))
        assertFalse(VideoPanel.isVideoFile("https://example.org/page.html"))
        assertFalse(VideoPanel.isVideoFile("file:///sdcard/clip.mp4"))
        assertFalse(VideoPanel.isVideoFile("javascript:alert(1).mp4"))
    }

    @Test fun `a video starts muted, its sound silences the microphone until turned off or closed`() {
        val logged = mutableListOf<String>()
        val p = VideoPanel { logged += it }
        assertFalse(p.setSound(true))                      // nothing shown
        p.show(VideoPanel.Video(youtubeId = "Way9Dexny3w", title = "t", sound = true))
        assertFalse(p.video.value!!.sound)
        assertFalse(p.soundOn)
        assertTrue(p.setSound(true)); assertTrue(p.soundOn)
        assertEquals(1, logged.size)
        assertTrue(p.setSound(false)); assertFalse(p.soundOn)
        p.setSound(true)
        assertTrue(p.close()); assertFalse(p.soundOn); assertNull(p.video.value)
        assertFalse(p.close())
    }

    @Test fun `commands reach the player only while a video is shown, and pause shows on its button`() {
        val p = VideoPanel()
        var shown = 0; var closed = 0
        p.onShown = { shown++ }; p.onClosed = { closed++ }
        assertFalse(p.command(VideoPanel.Command.Pause))
        p.show(VideoPanel.Video(url = "content://media/external/video/media/3", title = "t", paused = true))
        assertEquals(1, shown)
        assertFalse(p.video.value!!.paused)
        assertTrue(p.command(VideoPanel.Command.Pause)); assertTrue(p.video.value!!.paused)
        assertTrue(p.command(VideoPanel.Command.SeekBy(30))); assertTrue(p.video.value!!.paused)
        assertTrue(p.command(VideoPanel.Command.Resume)); assertFalse(p.video.value!!.paused)
        p.command(VideoPanel.Command.Pause)
        assertTrue(p.command(VideoPanel.Command.Restart)); assertFalse(p.video.value!!.paused)
        p.close(); p.close()
        assertEquals(1, closed)
    }

    @Test fun `the embedded page asks for YouTube's muted, inline, privacy-enhanced player`() {
        val html = youtubePage("Way9Dexny3w")
        assertTrue(html.contains("youtube-nocookie.com/embed/Way9Dexny3w?"))
        assertTrue(html.contains("mute=1") && html.contains("autoplay=1") && html.contains("playsinline=1") && html.contains("enablejsapi=1"))
    }
}
