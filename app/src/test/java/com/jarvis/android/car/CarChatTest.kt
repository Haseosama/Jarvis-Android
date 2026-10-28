package com.jarvis.android.car

import com.jarvis.android.engine.CAR_TEXT_DIRECTIVE
import com.jarvis.android.engine.DRIVING_DIRECTIVE
import com.jarvis.android.engine.TEXT_MODE_DIRECTIVE
import com.jarvis.android.engine.modeDirective
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarChatTest {
    @Test fun `an answer is said in the car without Markdown, lists or links`() {
        val said = carSpoken("## Demain\n- **Pluie** à 8 h\n- Voir [Météo-France](https://meteofrance.com) ou https://x.fr/a\n1. `rappel` posé")
        assertEquals("Demain Pluie à 8 h Voir Météo-France ou rappel posé", said)
    }

    @Test fun `a long answer stops at a sentence and says the rest is on the phone`() {
        val long = "Première phrase assez longue pour compter. ".repeat(30)
        val said = carSpoken(long, max = 200)
        assertTrue(said.length < 260)
        assertTrue(said.startsWith("Première phrase"))
        assertTrue(said.endsWith("(La suite est sur le téléphone.)"))
        assertTrue(said.removeSuffix(" (La suite est sur le téléphone.)").endsWith("."))
        assertEquals("Court.", carSpoken("Court."))
    }

    @Test fun `the car's conversation keeps the last lines`() {
        var lines = emptyList<CarLine>()
        repeat(12) { lines = carLinesAfter(lines, CarLine("m$it", mine = it % 2 == 0, at = it.toLong())) }
        assertEquals(CAR_CHAT_KEPT, lines.size)
        assertEquals("m11", lines.last().text)
        assertEquals("m4", lines.first().text)
    }

    @Test fun `the model is told it speaks through the car only for the text chat in Android Auto`() {
        assertEquals(CAR_TEXT_DIRECTIVE, modeDirective(textMode = true, inCar = true, driving = false))
        assertEquals(TEXT_MODE_DIRECTIVE, modeDirective(textMode = true, inCar = false, driving = true))
        assertEquals(DRIVING_DIRECTIVE, modeDirective(textMode = false, inCar = false, driving = true))
        assertEquals("", modeDirective(textMode = false, inCar = false, driving = false))
        assertTrue("Markdown" in CAR_TEXT_DIRECTIVE)
    }

    @Test fun `the recording ends after a pause, gives up when nothing is said, and has a longest`() {
        val chunk = 1_600   // 100 ms at 16 kHz
        val pause = EndOfSpeech(sampleRate = 16_000, silenceMs = 1_000, nothingSaidMs = 5_000, longestMs = 20_000)
        repeat(10) { assertFalse(pause.feed(0.3f, chunk)) }
        repeat(9) { assertFalse(pause.feed(0.01f, chunk)) }
        assertTrue(pause.feed(0.01f, chunk))
        assertTrue(pause.spoke)

        val silent = EndOfSpeech(sampleRate = 16_000, silenceMs = 1_000, nothingSaidMs = 5_000, longestMs = 20_000)
        repeat(49) { assertFalse(silent.feed(0.0f, chunk)) }
        assertTrue(silent.feed(0.0f, chunk))
        assertFalse(silent.spoke)

        val talker = EndOfSpeech(sampleRate = 16_000, silenceMs = 1_000, nothingSaidMs = 5_000, longestMs = 3_000)
        repeat(29) { assertFalse(talker.feed(0.5f, chunk)) }
        assertTrue(talker.feed(0.5f, chunk))
    }

    @Test fun `car microphone bytes become samples`() {
        assertEquals(listOf<Short>(1, -1, 256), pcmToShorts(byteArrayOf(1, 0, -1, -1, 0, 1, 7)).toList())
    }

    @Test fun `the car's first page has six rows, talking first, and places open the navigation app`() {
        val home = carHomeEntries()
        assertEquals(6, home.size)
        assertEquals("talk", home.first().id)
        assertTrue(home.filter { it.id == "day" || it.id == "messages" }.all { !it.question.isNullOrBlank() })
        assertTrue(CAR_PLACE_KINDS.size <= 6)
        assertEquals("geo:0,0?q=48.858400,2.294500(Tour%20Eiffel)", navigationUri(48.8584, 2.2945, "Tour Eiffel"))
    }
}
