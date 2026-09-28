package com.jarvis.android.plugins

import com.jarvis.android.actions.parseStations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginFieldsTest {
    // shapes taken from the real answers (Wikipedia's "on this day", TVmaze, JokeAPI)
    private val onThisDay = """{"selected":[{"text":"début de la seconde intifada.","year":2000,"pages":[{"title":"x"}]},
        {"text":"Découverte de la pénicilline.","year":1928,"pages":[]},{"year":1066}]}"""
    private val show = """{"name":"The Bear","status":"Ended","premiered":"2022-06-23","rating":{"average":7.9},"network":null,
        "genres":["Drama","Comedy"],"_embedded":{"nextepisode":{"airdate":"2026-10-01","name":"Pilot"}}}"""

    @Test fun `a list keeps the chosen values of each element, the first ones only, and skips empty ones`() {
        assertEquals(
            "- 2000 — début de la seconde intifada.\n- 1928 — Découverte de la pénicilline.\n- 1066",
            pickFields(onThisDay, "selected", listOf("year", "text"), 10),
        )
        assertEquals("- 2000 — début de la seconde intifada.", pickFields(onThisDay, "selected", listOf("year", "text"), 1))
        assertNull(pickFields(onThisDay, "missing", listOf("year"), 5))
    }

    @Test fun `without a list, one line per value found, lists of words joined`() {
        assertEquals(
            "name : The Bear\nstatus : Ended\naverage : 7.9\nairdate : 2026-10-01\ngenres : Drama, Comedy",
            pickFields(show, null, listOf("name", "status", "rating.average", "network.name", "_embedded.nextepisode.airdate", "genres"), 10),
        )
        assertEquals("joke : Deux…", pickFields("""{"joke":"Deux…","setup":null}""", null, listOf("joke", "setup", "delivery"), 10))
        assertNull(pickFields("not json", null, listOf("a"), 10))
    }

    @Test fun `the new keys are checked when the plugin is read`() {
        fun parse(extra: String) = parsePlugin(
            """{"name":"essai","description":"Une description assez longue.","type":"http","url":"https://example.org/x"$extra}""", emptySet(),
        )
        assertTrue(parse(""","result_items":"a.b","result_fields":["year","text"],"result_max":5""") is PluginParse.Ok)
        assertTrue(parse(""","result_items":"a"""") is PluginParse.Error)                          // a list needs its fields
        assertTrue(parse(""","result_fields":["a b"]""") is PluginParse.Error)                     // a path is letters, digits, _ and dots
        assertTrue(parse(""","result_fields":["x"],"result_max":99""") is PluginParse.Error)
        assertTrue(parse(""","result_fields":["a","b","c","d","e","f","g","h","i"]""") is PluginParse.Error)
    }

    @Test fun `radio stations keep only https streams, each once, with a clean name`() {
        val json = """[{"name":"FIP","url":"http://x","url_resolved":"https://stream.radiofrance.fr/fip/fip.m3u8","tags":"jazz,eclectic","countrycode":"FR","codec":"AAC","bitrate":192},
            {"name":"FIP bis","url_resolved":"https://stream.radiofrance.fr/fip/fip.m3u8"},
            {"name":"Old","url_resolved":"http://old.example/stream"},
            {"name":"","url_resolved":"https://noname.example/s"},
            {"name":"Radio\u0007 Nova","url":"https://novazz.ice.infomaniak.ch/novazz-128.mp3","bitrate":128}]"""
        val s = parseStations(json)
        assertEquals(listOf("FIP", "Radio Nova"), s.map { it.name })
        assertEquals("https://stream.radiofrance.fr/fip/fip.m3u8", s[0].stream)
        assertEquals(128, s[1].bitrate)
        assertTrue(parseStations("<html>").isEmpty())
    }
}
