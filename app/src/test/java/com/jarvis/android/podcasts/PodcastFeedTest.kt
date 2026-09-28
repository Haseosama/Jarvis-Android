package com.jarvis.android.podcasts

import com.jarvis.android.plugins.pluginDownloadUrl
import com.jarvis.android.wakeup.RadioAlarmSpec
import com.jarvis.android.wakeup.daysWords
import com.jarvis.android.wakeup.nextRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class PodcastFeedTest {
    // the shapes of Radio France's and Audiomeans' feeds, cut short as a size limit would
    private val feed = """<?xml version="1.0"?><rss><channel><title>Le journal de 18h00</title><itunes:author>France Culture</itunes:author>
        <image><title>ignored</title></image>
        <item><title><![CDATA[Le journal de 22h00 du 28/09/2026]]></title><pubDate>Mon, 28 Sep 2026 20:00:41 GMT</pubDate>
          <enclosure url="https://audio.audiomeans.fr/file/a.mp3?_=1&amp;x=2" type="audio/mpeg"/><itunes:duration>00:12:34</itunes:duration></item>
        <item><title>Prison &amp; inéligibilité &#233;t&#xE9;</title><pubDate>Mon, 28 Sep 2026 18:00:00 +0200</pubDate>
          <enclosure type="audio/mpeg" url='https://proxycast.radiofrance.fr/b.mp3'/><itunes:duration>754</itunes:duration></item>
        <item><title>Old http</title><enclosure url="http://old.example/c.mp3"/></item>
        <item><title>Cut in the midd"""

    @Test fun `a feed gives its title, author and https episodes, entities and CDATA read, even cut short`() {
        val f = parseFeed(feed)
        assertEquals("Le journal de 18h00", f.title)
        assertEquals("France Culture", f.author)
        assertEquals(2, f.episodes.size)
        with(f.episodes[0]) {
            assertEquals("Le journal de 22h00 du 28/09/2026", title)
            assertEquals("https://audio.audiomeans.fr/file/a.mp3?_=1&x=2", audio)
            assertEquals(12 * 60 + 34, durationS)
            assertEquals(LocalDateTime.of(2026, 9, 28, 20, 0, 41).toInstant(ZoneOffset.UTC).toEpochMilli(), published)
        }
        assertEquals("Prison & inéligibilité été", f.episodes[1].title)
        assertEquals(754, f.episodes[1].durationS)
        assertTrue(parseFeed("<html>not a feed</html>").episodes.isEmpty())
    }

    @Test fun `dates, lengths and the freshest bulletin`() {
        assertNull(parseDate("hier"))
        assertEquals(3723, parseDuration("1:02:03"))
        assertEquals(0, parseDuration("n/a"))
        val a = Episode("a", "https://x/a", 1000L, 0)
        val b = Episode("b", "https://x/b", 3000L, 0)
        val c = Episode("c", "https://x/c", null, 0)
        assertEquals("b", freshest(listOf("A" to a, "B" to b, "C" to c))!!.second.title)
    }

    @Test fun `the news source is told by the user's words`() {
        assertEquals("franceinter", newsSource("le journal de France Inter"))
        assertEquals("europe1", newsSource("Europe 1"))
        assertEquals("rfi", newsSource("RFI"))
        assertNull(newsSource(""))
        assertTrue(NEWS_FEEDS.values.flatten().all { it.startsWith("https://") })
    }

    @Test fun `only podcasts with an https feed are kept from a search`() {
        val json = """{"results":[{"collectionName":"Face à l'histoire","artistName":"France Inter"},
            {"collectionName":"Le journal d'Europe 1","artistName":"Europe 1","feedUrl":"https://feeds.audiomeans.fr/feed/44f6.xml"},
            {"collectionName":"Old","feedUrl":"http://old.example/feed"}]}"""
        assertEquals(listOf("Le journal d'Europe 1"), parsePodcastSearch(json).map { it.title })
    }

    @Test fun `a radio alarm rings next on its time, on one of its days`() {
        val zone = ZoneId.of("Europe/Paris")
        val monday8 = LocalDateTime.of(2026, 9, 28, 8, 0)        // a Monday
        fun at(spec: RadioAlarmSpec) = java.time.Instant.ofEpochMilli(nextRing(spec, monday8, zone)).atZone(zone).toLocalDateTime()
        assertEquals(LocalDateTime.of(2026, 9, 29, 7, 0), at(RadioAlarmSpec(7, 0, emptyList(), "FIP", "https://x")))          // once: tomorrow
        assertEquals(LocalDateTime.of(2026, 9, 28, 9, 30), at(RadioAlarmSpec(9, 30, emptyList(), "FIP", "https://x")))        // later today
        assertEquals(LocalDateTime.of(2026, 10, 3, 7, 0), at(RadioAlarmSpec(7, 0, listOf(6, 7), "FIP", "https://x")))        // the weekend
        assertEquals("en semaine", daysWords(listOf(5, 4, 3, 2, 1)))
        assertEquals("lun., mer.", daysWords(listOf(3, 1)))
        assertEquals("une fois", daysWords(emptyList()))
    }

    @Test fun `a plugin link must be https, not local, and a GitHub page gives its raw file`() {
        assertEquals("https://raw.githubusercontent.com/me/plugins/main/meteo.json", pluginDownloadUrl("https://github.com/me/plugins/blob/main/meteo.json"))
        assertEquals("https://example.org/p.json", pluginDownloadUrl(" https://example.org/p.json "))
        assertNull(pluginDownloadUrl("http://example.org/p.json"))
        assertNull(pluginDownloadUrl("https://192.168.1.10/p.json"))
        assertNull(pluginDownloadUrl("https://user:pw@example.org/p.json"))
    }
}
