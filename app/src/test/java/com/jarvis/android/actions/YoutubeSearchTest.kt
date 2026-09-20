package com.jarvis.android.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeSearchTest {
    // Shape taken from a real results page: thumbnail first, then the title runs.
    private val page = "<html>{\"contents\":[{\"videoRenderer\":{\"videoId\":\"5NV6Rdv1a3I\",\"thumbnail\":{\"thumbnails\":[{\"url\":\"https://i.ytimg.com/vi/5NV6Rdv1a3I/hq720.jpg?a=1\\u0026rs=2\"}]}," +
        "\"title\":{\"runs\":[{\"text\":\"Daft Punk - Get Lucky (Official Audio) ft. Pharrell Williams\"}],\"accessibility\":{}}}}," +
        "{\"videoRenderer\":{\"videoId\":\"CCHdMIEGaaM\",\"title\":{\"runs\":[{\"text\":\"Second\"}]}}}]}</html>"

    @Test
    fun `first video id and title are read`() {
        val hit = parseFirstVideo(page)!!
        assertEquals("5NV6Rdv1a3I", hit.videoId)
        assertEquals("Daft Punk - Get Lucky (Official Audio) ft. Pharrell Williams", hit.title)
    }

    @Test
    fun `a page without videos gives nothing`() {
        assertNull(parseFirstVideo("<html>consent</html>"))
    }

    @Test
    fun `a malformed id is skipped for the next video`() {
        val html = "\"videoRenderer\":{\"videoId\":\"bad id!\",\"title\":{\"runs\":[{\"text\":\"X\"}]}}" +
            "\"videoRenderer\":{\"videoId\":\"CCHdMIEGaaM\",\"title\":{\"runs\":[{\"text\":\"Second\"}]}}"
        assertEquals("CCHdMIEGaaM", parseFirstVideo(html)!!.videoId)
    }

    @Test
    fun `escapes in titles are decoded and control characters removed`() {
        val html = "\"videoRenderer\":{\"videoId\":\"CCHdMIEGaaM\",\"title\":{\"runs\":[{\"text\":\"A \\\"quoted\\\" \\u0026 b\\nline\"}]}}"
        assertEquals("A \"quoted\" & b line", parseFirstVideo(html)!!.title)
    }

    @Test
    fun `search url asks for videos and encodes the query`() {
        val url = youtubeVideosUrl("daft punk & co")
        assertTrue(url.startsWith("https://www.youtube.com/results?search_query=daft%20punk%20%26%20co"))
        assertTrue(url.contains("sp=EgIQAQ"))
    }

    @Test
    fun `watch url goes to youtube music and is accepted by liberty`() {
        assertTrue(isLibertyLink(libertyWatchUrl("5NV6Rdv1a3I")))
    }
}
