package com.jarvis.android.space

import com.jarvis.android.weather.dayLight
import com.jarvis.android.weather.dayLightWords
import com.jarvis.android.weather.uvPeak
import com.jarvis.android.weather.uvWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ObservingTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test fun `the score of an hour`() {
        assertEquals(0, observingScore(0, 50, 5.0, -3.0, -10.0, 0.0)) // still light
        assertEquals(100, observingScore(0, 50, 5.0, -25.0, -10.0, 0.9)) // dark, clear, Moon down
        assertEquals(70, observingScore(0, 50, 5.0, -25.0, 20.0, 1.0)) // the full Moon up
        assertEquals(40, observingScore(60, 50, 5.0, -25.0, -10.0, 0.0))
        assertEquals(55, observingScore(10, 95, 45.0, -25.0, -10.0, 0.0)) // damp and windy: 90 − 15 − 20
        assertEquals(45, observingScore(10, 50, 5.0, -8.0, -10.0, 0.0)) // twilight still
    }

    @Test fun `a night's best stretch, in words`() {
        fun h(hourUtc: Int, day: Int, clouds: Int, score: Int, moonAlt: Double = -10.0) = ObsHour(
            ms("2026-10-%02dT%02d:00:00Z".format(day, hourUtc)), clouds, 0, 0, clouds, 70, 8.0, 3.0, 8.0, -25.0, moonAlt, 0.4, score,
        )
        val night = obsNights(listOf(h(19, 1, 90, 10), h(20, 1, 80, 20), h(21, 1, 10, 85), h(22, 1, 5, 90), h(23, 1, 5, 88), h(0, 2, 40, 55), h(1, 2, 90, 10)), paris).single()
        assertEquals(LocalDate.of(2026, 10, 1), night.evening)
        assertEquals(3, night.best.size)
        assertEquals("Cette nuit : excellent de 23h à 2h (6 % de nuages, Lune couchée, 8 °C au plus froid)", obsNightWords(night, paris, LocalDate.of(2026, 10, 1)))
        val cloudy = obsNights(listOf(h(21, 3, 100, 0), h(22, 3, 95, 5)), paris).single()
        assertEquals("La nuit du samedi 3 : pas bon pour observer (97 % de nuages en moyenne)", obsNightWords(cloudy, paris, LocalDate.of(2026, 10, 1)))
    }

    @Test fun `Open-Meteo's hours keep the dark ones, with the Sun and the Moon`() {
        val json = """{"hourly":{"time":["2026-10-01T12:00","2026-10-01T22:00"],"cloud_cover":[20,15],"cloud_cover_low":[0,5],"cloud_cover_mid":[0,0],"cloud_cover_high":[20,10],
            "relative_humidity_2m":[60,85],"dew_point_2m":[5,6],"temperature_2m":[18,9],"wind_speed_10m":[10,12]}}"""
        val hours = parseObsHours(json, Observer(48.85, 2.35))
        assertEquals(1, hours.size) // noon is not night
        assertEquals(ms("2026-10-01T22:00:00Z"), hours[0].timeMs)
        assertTrue(hours[0].sunAlt < -18)
        assertEquals(85, hours[0].humidity)
    }

    @Test fun `the longest day in Paris`() {
        val d = dayLight(Observer(48.8566, 2.3522), LocalDate.of(2026, 6, 21), paris)
        // Paris, 21 June: sunrise 05:47, sunset 21:58, the Sun at 64.6° at 13:52 (local summer time)
        assertTrue(kotlin.math.abs(d.sunrise!! - ms("2026-06-21T03:47:00Z")) < 3 * 60_000L)
        assertTrue(kotlin.math.abs(d.sunset!! - ms("2026-06-21T19:58:00Z")) < 3 * 60_000L)
        assertEquals(64.6, d.noonAltitude, 0.3)
        assertTrue(kotlin.math.abs(d.noon - ms("2026-06-21T11:52:00Z")) < 3 * 60_000L)
        assertTrue(d.eveningGolden!!.first < d.sunset!! && d.eveningGolden!!.second > d.sunset!!)
        assertTrue(d.eveningBlue!!.first >= d.eveningGolden!!.second - 60_000L)
        assertTrue(dayLightWords(d, paris).startsWith("lever 05h4"))
        // Tromsø: the midnight Sun
        val north = dayLight(Observer(69.65, 18.96), LocalDate.of(2026, 6, 21), ZoneId.of("Europe/Oslo"))
        assertNull(north.sunset)
        assertTrue(dayLightWords(north, ZoneId.of("Europe/Oslo")).startsWith("le Soleil ne se couche pas"))
    }

    @Test fun `the UV, its peak and what to do`() {
        assertTrue(uvWords(1.0).startsWith("faible"))
        assertTrue(uvWords(7.2).startsWith("élevé : crème"))
        assertTrue(uvWords(11.5).startsWith("extrême"))
        val json = """{"hourly":{"time":["2026-07-01T10:00","2026-07-01T12:00","2026-07-01T14:00","2026-07-02T12:00"],"uv_index":[5.1,7.8,6.2,9.0]}}"""
        val (uv, at) = uvPeak(json, LocalDate.of(2026, 7, 1), paris)!!
        assertEquals(7.8, uv, 1e-9)
        assertEquals(ms("2026-07-01T12:00:00Z"), at)
        assertEquals("uv", com.jarvis.android.wakeup.sectionKey("la crème solaire"))
    }
}
