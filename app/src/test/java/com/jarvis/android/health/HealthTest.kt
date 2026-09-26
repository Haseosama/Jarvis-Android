package com.jarvis.android.health

import com.jarvis.android.memory.BriefingInputs
import com.jarvis.android.memory.buildBriefingBlock
import com.jarvis.android.offline.OfflineAction
import com.jarvis.android.offline.interpret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HealthTest {
    private val zone = ZoneId.of("Europe/Paris")
    private fun at(h: Int, m: Int, day: Int = 26) = LocalDate.of(2026, 9, day).atTime(h, m).atZone(zone).toInstant()

    @Test
    fun `numbers read naturally`() {
        assertEquals("45 min", durationWords(45))
        assertEquals("7 h", durationWords(420))
        assertEquals("7 h 05", durationWords(425))
        assertEquals("8 432", thousands(8432))
        assertEquals("1 234 567", thousands(1234567))
        assertEquals("850 m", distanceWords(850.2))
        assertEquals("3,2 km", distanceWords(3240.0))
        assertEquals("5 km", distanceWords(5000.0))
        assertEquals("8 432 pas, 6,1 km", describeActivity(DayActivity(8432, 6100.0)))
        assertEquals("", describeActivity(DayActivity(-1, -1.0)))
    }

    @Test
    fun `sleep counts the stages asleep, or the whole night when there are none`() {
        val start = at(23, 0, 25)
        val end = at(7, 0)
        assertEquals(480, asleepMinutes(start, end, emptyList()))
        val stages = listOf(
            SleepStage(5, start, at(3, 0)), // deep
            SleepStage(1, at(3, 0), at(3, 20)), // awake
            SleepStage(4, at(3, 20), end), // light
        )
        assertEquals(460, asleepMinutes(start, end, stages))
        assertEquals("7 h 40 de sommeil, de 23 h à 7 h", describeNight(NightSleep(start, end, 460), zone))
    }

    @Test
    fun `the morning briefing carries the health line, and it alone is enough for a briefing`() {
        val block = buildBriefingBlock(BriefingInputs(LocalDate.of(2026, 9, 26), "", null, emptyList(), health = "yesterday 8 432 pas"))
        assertTrue(block, block.contains("HEALTH (from Health Connect; numbers only, no medical advice): yesterday 8 432 pas"))
        assertEquals("", buildBriefingBlock(BriefingInputs(LocalDate.of(2026, 9, 26), "", null, emptyList())))
    }

    @Test
    fun `offline, steps and sleep are understood`() {
        assertEquals("steps", (interpret("Combien de pas aujourd'hui ?") as OfflineAction.ToolCall).args["action"])
        assertEquals("sleep", (interpret("Comment j'ai dormi cette nuit ?") as OfflineAction.ToolCall).args["action"])
        assertEquals("health", (interpret("mes pas") as OfflineAction.ToolCall).name)
    }

    @Test
    fun `with several step counters, the largest one counts, not their sum`() {
        assertEquals(8000.0, bestSource(listOf("watch" to 5000.0, "watch" to 3000.0, "phone" to 7500.0)), 0.0)
        assertEquals(0.0, bestSource(emptyList()), 0.0)
        assertTrue(Instant.EPOCH.isBefore(at(0, 0)))
    }
}
