package com.jarvis.android.widget

import com.jarvis.android.reminders.ReminderRecord
import com.jarvis.android.reminders.ReminderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class WidgetTextTest {
    private val zone = ZoneId.of("Europe/Paris")
    /** Monday 5 October 2026, 10:00 in Paris. */
    private val now = ZonedDateTime.of(2026, 10, 5, 10, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun at(day: Int, hour: Int, minute: Int = 0, month: Int = 10) =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun record(id: Int, triggerAt: Long, text: String = "Rappel $id", status: ReminderStatus = ReminderStatus.SCHEDULED) =
        ReminderRecord(id, "token-$id", text, "2026-10-05 18:00", zone.id, triggerAt, status = status)

    @Test
    fun `the next reminder is the soonest one still to come`() {
        val records = listOf(
            record(1, at(5, 9)),                                            // already past
            record(2, at(7, 8)),
            record(3, at(5, 18)),
            record(4, at(5, 12), status = ReminderStatus.DELIVERED),        // rung early
            record(5, at(5, 11), status = ReminderStatus.BLOCKED),
        )
        assertEquals(3, nextReminder(records, now)?.id)
        assertNull(nextReminder(listOf(record(1, at(5, 9))), now))
        assertNull(nextReminder(emptyList(), now))
    }

    @Test
    fun `the day is said the shortest way`() {
        assertEquals("Aujourd’hui 18:00 · Acheter du pain", reminderLine(record(1, at(5, 18), "Acheter du pain"), now, zone))
        assertEquals("Demain 08:30 · Dentiste", reminderLine(record(1, at(6, 8, 30), "Dentiste"), now, zone))
        assertEquals("Jeudi 09:00 · Réunion", reminderLine(record(1, at(8, 9), "Réunion"), now, zone))
        assertEquals("Dimanche 20:15 · Appeler maman", reminderLine(record(1, at(11, 20, 15), "Appeler maman"), now, zone))
        val farther = reminderLine(record(1, at(12, 9), "Vidange"), now, zone)
        assertTrue(farther, farther.startsWith("12 oct") && farther.endsWith(" 09:00 · Vidange"))
    }

    @Test
    fun `a long or ragged text is tidied and cut`() {
        val line = reminderLine(record(1, at(5, 18), "  Prendre   le\ncolis " + "x".repeat(200)), now, zone, maxChars = 30)
        assertTrue(line, line.startsWith("Aujourd’hui 18:00 · Prendre le colis "))
        assertTrue(line, line.endsWith("…"))
        assertEquals(30, line.substringAfter(" · ").length)
    }

    @Test
    fun `the weather is read from Open-Meteo's answer`() {
        val body = """{"latitude":45.76,"current":{"time":"2026-10-05T10:00","temperature_2m":17.6,"weather_code":2,"is_day":1}}"""
        assertEquals(WidgetWeather(17.6, 2, true), parseWidgetWeather(body))
        assertEquals(false, parseWidgetWeather(body.replace("\"is_day\":1", "\"is_day\":0"))?.day)
        assertNull(parseWidgetWeather("""{"error":true,"reason":"bad"}"""))
        assertNull(parseWidgetWeather("""{"current":{"temperature_2m":17.6}}"""))
        assertNull(parseWidgetWeather("""{"current":{"temperature_2m":999,"weather_code":0}}"""))
        assertNull(parseWidgetWeather("not json"))
    }

    @Test
    fun `the weather line has a symbol, the rounded temperature, the words and the place`() {
        assertEquals("⛅ 18 °C, partiellement nuageux · Lyon", weatherLine(WidgetWeather(17.6, 2, true), "Lyon"))
        assertEquals("🌙 0 °C, ciel dégagé", weatherLine(WidgetWeather(-0.4, 0, false), null))
        assertEquals("🌧️ -3 °C, pluie", weatherLine(WidgetWeather(-3.2, 61, true), " "))
        assertEquals("⛈️", weatherSymbol(95, true))
        assertEquals("🌨️", weatherSymbol(73, false))
    }
}
