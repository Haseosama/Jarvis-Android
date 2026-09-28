package com.jarvis.android.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoHistoryTest {
    private val talk = VideoPanel.Video(youtubeId = "iG9CE55wbtY", title = "Talk")
    private val film = VideoPanel.Video(url = "content://media/external/video/media/7", title = "Film")

    @Test fun `a video left on the way is kept, newest first, and one near its start or its end is not`() {
        val h = VideoHistory()
        h.record(talk, 53, 1204, now = 1_000)
        h.record(film, 600, 5400, now = 2_000)
        assertEquals(listOf("Film", "Talk"), h.all().map { it.title })
        assertEquals(53, h.resumeAt(talk))
        assertEquals(600, h.resumeAt(film.copy(title = "another name")))
        h.record(talk, 1190, 1204, now = 3_000)                // watched to the end
        assertEquals(0, h.resumeAt(talk))
        h.record(film, 5, 5400, now = 4_000)                   // started again from the beginning
        assertNull(h.latest())
        h.record(VideoPanel.Video(title = "Photos", photos = listOf("content://1")), 100, 0, now = 5_000)
        assertTrue(h.all().isEmpty())
    }

    @Test fun `the latest is the one to go back to, as a video starting where it was left`() {
        val h = VideoHistory()
        h.record(talk, 53, 1204, now = 1_000)
        h.record(film, 600, 0, now = 2_000)                    // length not known: kept
        val v = h.latest()!!.video()
        assertEquals("content://media/external/video/media/7", v.url)
        assertEquals(600, v.startAt)
    }

    @Test fun `it is written out now and then, read back, and keeps thirty`() {
        var stored: String? = null
        var writes = 0
        val h = VideoHistory({ stored }, { stored = it; writes++ })
        h.record(talk, 53, 1204, now = 100_000)
        h.record(talk, 58, 1204, now = 105_000)                // too soon to write again
        assertEquals(1, writes)
        h.record(talk, 58, 1204, now = 106_000, force = true)
        assertEquals(2, writes)
        assertEquals(58, VideoHistory({ stored }).resumeAt(talk))
        for (k in 0 until 40) h.record(VideoPanel.Video(url = "content://$k"), 30, 0, now = 200_000L + k)
        assertEquals(VideoHistory.MAX_KEPT, h.all().size)
        assertEquals("content://39", h.latest()!!.url)
        assertTrue(VideoHistory({ "not json" }).all().isEmpty())
    }
}
