package com.jarvis.android.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.launch
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
        val p = VideoPanel(log = { logged += it })
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

    @Test fun `what was found is kept for the next and the previous, with the same sound and screen`() {
        val p = VideoPanel()
        var shown = 0
        p.onShown = { shown++ }
        assertFalse(p.step(1))
        p.showList(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc").map { VideoPanel.Video(youtubeId = it, title = it.take(1)) })
        assertEquals(1, p.video.value!!.position); assertEquals(3, p.video.value!!.count)
        p.setSound(true); p.setFullscreen(true)
        assertTrue(p.step(1))
        with(p.video.value!!) { assertEquals("bbbbbbbbbbb", youtubeId); assertEquals(2, position); assertTrue(sound); assertTrue(fullscreen) }
        assertTrue(p.step(1)); assertFalse(p.step(1))
        assertEquals("ccccccccccc", p.video.value!!.youtubeId)
        assertTrue(p.step(-2)); assertEquals("aaaaaaaaaaa", p.video.value!!.youtubeId)
        assertFalse(p.step(-1))
        assertEquals(4, shown)
        // a new search starts muted, in the screen the last one had
        p.show(VideoPanel.Video(url = "https://example.org/a.mp4"))
        with(p.video.value!!) { assertFalse(sound); assertTrue(fullscreen); assertEquals(1, count) }
    }

    @Test fun `a slideshow goes photo by photo and has no sound`() {
        val p = VideoPanel()
        p.show(VideoPanel.Video(title = "Photos", photos = listOf("content://1", "content://2")))
        assertTrue(p.video.value!!.isSlideshow)
        assertFalse(p.setSound(true))
        val sent = mutableListOf<VideoPanel.Command>()
        kotlinx.coroutines.runBlocking {
            val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { p.commands.collect { sent += it } }
            assertTrue(p.step(1)); assertTrue(p.step(-1))
            job.cancel()
        }
        assertEquals(listOf(VideoPanel.Command.Step(1), VideoPanel.Command.Step(-1)), sent)
    }

    @Test fun `over the video's sound the microphone opens only with the floor, which closes by itself`() {
        var now = 0L
        val p = VideoPanel(clock = { now })
        var floors = 0
        p.onFloor = { floors++ }
        assertTrue(p.micOpen())                                  // nothing shown
        p.show(VideoPanel.Video(youtubeId = "Way9Dexny3w"))
        assertTrue(p.micOpen())                                  // muted video
        assertFalse(p.openFloor())                               // no sound to talk over
        p.setSound(true)
        assertFalse(p.micOpen())
        assertTrue(p.openFloor()); assertEquals(1, floors)
        assertTrue(p.micOpen()); assertTrue(p.video.value!!.ducked)
        assertEquals(DUCK_CHECK, volumeOf(p.video.value!!))
        now = VideoPanel.FLOOR_OPEN_MS - 1000
        p.keepFloor()                                            // still talking: open 4 s more
        now = VideoPanel.FLOOR_OPEN_MS + 2000
        assertTrue(p.micOpen())
        now += VideoPanel.FLOOR_KEEP_MS
        assertFalse(p.micOpen()); assertFalse(p.video.value!!.ducked)
        assertEquals(100, volumeOf(p.video.value!!))
        p.keepFloor()                                            // a closed floor is not reopened by talk
        assertFalse(p.micOpen())
        p.openFloor(); p.setSound(false)
        assertTrue(p.micOpen()); assertFalse(p.video.value!!.ducked)
        assertEquals(0, volumeOf(p.video.value!!))
    }

    @Test fun `a timer stops the video at its time, or at the end of the one playing`() {
        var now = 0L
        val p = VideoPanel(clock = { now })
        assertFalse(p.setTimer(20))                              // no video
        p.showList(listOf(VideoPanel.Video(url = "content://1"), VideoPanel.Video(url = "content://2")))
        assertTrue(p.setTimer(20))
        assertEquals(20 * 60_000L, p.timerLeftMs())
        now = 20 * 60_000L - 1
        assertFalse(p.checkTimer())
        now += 1
        assertTrue(p.checkTimer()); assertNull(p.video.value); assertNull(p.timer.value)

        // at the end: a video file would go on to the next one, the timer closes instead
        p.showList(listOf(VideoPanel.Video(url = "content://1"), VideoPanel.Video(url = "content://2")))
        p.ended(); assertEquals("content://2", p.video.value!!.url)
        p.setTimer(null, atEnd = true)
        assertNull(p.timerLeftMs())
        p.ended(); assertNull(p.video.value)

        // YouTube stays on its end screen; a timer set to nothing is none
        p.show(VideoPanel.Video(youtubeId = "Way9Dexny3w"))
        p.ended(); assertEquals("Way9Dexny3w", p.video.value!!.youtubeId)
        p.setTimer(0); assertNull(p.timer.value)
    }

    @Test fun `the players say where they are, and a new search starts where the video was left, with the same subtitles`() {
        val p = VideoPanel()
        val heard = mutableListOf<Pair<Int, Int>>()
        p.onProgress = { _, pos, dur -> heard += pos to dur }
        p.show(VideoPanel.Video(youtubeId = "Way9Dexny3w", startAt = 42))
        assertEquals(42, p.positionS)
        p.progress(50, 600)
        assertEquals(50, p.positionS); assertEquals(listOf(50 to 600), heard)
        assertTrue(p.command(VideoPanel.Command.Subtitles("fr")))
        assertEquals("fr", p.video.value!!.subtitles)
        p.show(VideoPanel.Video(youtubeId = "aqz-KE-bpKQ"))
        assertEquals("fr", p.video.value!!.subtitles)
        p.show(VideoPanel.Video(title = "Photos", photos = listOf("content://1")))
        p.progress(3, 0)
        assertEquals(1, heard.size)                               // a slideshow has no place to come back to
    }

    @Test fun `the page starts YouTube where asked, with subtitles in a clean language code`() {
        val html = youtubePage("Way9Dexny3w", 75, "fr")
        assertTrue(html.contains("&start=75") && html.contains("cc_load_policy=1&cc_lang_pref=fr"))
        assertFalse(youtubePage("Way9Dexny3w").contains("start="))
        assertTrue(youtubePage("Way9Dexny3w", 0, "e\"n<").contains("cc_lang_pref=en&"))
    }

    @Test fun `a paused sound gives the microphone back, and the words say how to talk over the sound`() {
        val p = VideoPanel()
        p.show(VideoPanel.Video(url = "https://radio.example/live", radio = true))
        p.setSound(true)
        assertTrue(p.soundOn); assertFalse(p.micOpen())
        p.command(VideoPanel.Command.Pause)
        assertFalse(p.soundOn); assertTrue(p.micOpen())
        assertFalse(p.openFloor())                                // nothing to talk over while paused
        p.command(VideoPanel.Command.Resume)
        assertFalse(p.micOpen())
        assertTrue(p.talkOverWords().contains("visage"))           // no wake word installed: the tap or a pause
        p.wakePhrase = { "« Hey Jarvis »" }
        assertTrue(p.talkOverWords().contains("« Hey Jarvis »"))
    }

    private companion object { const val DUCK_CHECK = 12 }
}
