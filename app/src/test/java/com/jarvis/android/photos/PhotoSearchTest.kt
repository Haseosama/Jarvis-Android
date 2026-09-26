package com.jarvis.android.photos

import com.jarvis.android.offline.OfflineAction
import com.jarvis.android.offline.interpret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class PhotoSearchTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 9, 26)
    private fun ms(d: LocalDate, h: Int = 12) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
    private fun photo(d: LocalDate, album: String = "Camera") = Photo(ms(d).hashCode().toLong(), ms(d), album)

    @Test
    fun `the range covers whole days, one day alone, the last week by default, in any order`() {
        val aug = LocalDate.of(2026, 8, 1)
        assertEquals(ms(aug, 0) to ms(LocalDate.of(2026, 9, 1), 0), photoRange("2026-08-01", "2026-08-31", today, zone))
        assertEquals(ms(aug, 0) to ms(aug.plusDays(1), 0), photoRange("2026-08-01", "", today, zone))
        assertEquals(ms(today.minusDays(6), 0) to ms(today.plusDays(1), 0), photoRange("", "", today, zone))
        assertEquals(photoRange("2026-08-01", "2026-08-31", today, zone), photoRange("2026-08-31", "2026-08-01", today, zone))
        assertNull(photoRange("août", "", today, zone))
    }

    @Test
    fun `the answer says how many, over which days, and the main album`() {
        assertEquals("Aucune photo pour cette période.", describePhotos(emptyList(), zone))
        assertEquals(
            "3 photos du 3 au 17 août 2026, surtout dans Camera.",
            describePhotos(listOf(photo(LocalDate.of(2026, 8, 3)), photo(LocalDate.of(2026, 8, 10)), photo(LocalDate.of(2026, 8, 17), "WhatsApp")), zone),
        )
        assertEquals("1 photo prise près de Brest le 1er septembre 2026, dans Camera.", describePhotos(listOf(photo(LocalDate.of(2026, 9, 1))), zone, "Brest"))
        assertEquals(
            "2 photos du 30 août au 2 septembre 2026.",
            describePhotos(listOf(photo(LocalDate.of(2026, 8, 30), "A"), photo(LocalDate.of(2026, 9, 2), "B")), zone),
        )
    }

    @Test
    fun `offline, recent photos are understood`() {
        val now = LocalDateTime.of(2026, 9, 26, 10, 0)
        val y = interpret("Montre-moi mes photos d'hier", now) as OfflineAction.ToolCall
        assertEquals("photos", y.name)
        assertEquals(mapOf("from" to "2026-09-25", "to" to "2026-09-25"), y.args)
        assertEquals("2026-09-20", (interpret("mes dernières photos", now) as OfflineAction.ToolCall).args["from"])
    }
}
