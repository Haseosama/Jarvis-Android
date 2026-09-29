package com.jarvis.android.energy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EnergyTest {
    @Test fun `Tempo days and what is left`() {
        assertEquals(TempoDay("2026-09-29", 1), parseTempoDay("""{"dateJour":"2026-09-29","codeJour":1,"periode":"2026-2027","libCouleur":"Bleu"}"""))
        assertEquals(0, parseTempoDay("""{"dateJour":"2026-09-30","codeJour":0,"periode":"2026-2027","libCouleur":"Inconnu"}""")!!.code)
        assertNull(parseTempoDay("""{"title":"An error occurred"}"""))
        assertEquals(TempoLeft(271, 43, 22), parseTempoLeft("""{"periode":"2026-2027","joursBleusRestants":271,"joursBlancsRestants":43,"joursRougesRestants":22}"""))
        assertEquals("rouge", tempoColour(3))
        assertTrue(tempoAdvice(3).contains("après 22 h"))
    }

    @Test fun `EcoWatt's signal from RTE, the tense hours in words`() {
        val json = """{"signals":[
          {"GenerationFichier":"2027-01-14T23:00:00+01:00","jour":"2027-01-16T00:00:00+01:00","dvalue":2,"message":"Système électrique tendu.",
           "values":[{"pas":7,"hvalue":1},{"pas":8,"hvalue":2},{"pas":9,"hvalue":2},{"pas":10,"hvalue":2},{"pas":18,"hvalue":3},{"pas":19,"hvalue":2},{"pas":20,"hvalue":1}]},
          {"GenerationFichier":"2027-01-14T23:00:00+01:00","jour":"2027-01-15T00:00:00+01:00","dvalue":1,"message":"Pas d'alerte.","values":[{"pas":0,"hvalue":0}]}]}"""
        val days = parseEcoWatt(json)
        assertEquals(listOf(LocalDate.of(2027, 1, 15), LocalDate.of(2027, 1, 16)), days.map { it.date })
        assertEquals(listOf(8, 9, 10, 18, 19), days[1].tenseHours)
        assertEquals("de 8 h à 11 h et de 18 h à 20 h", hoursWords(days[1].tenseHours))
        assertEquals("EcoWatt orange demain : réseau tendu de 8 h à 11 h et de 18 h à 20 h ; évitez les gros appareils à ces heures", ecoWattWords(days[1], LocalDate.of(2027, 1, 15)))
        assertEquals("EcoWatt vert aujourd’hui : pas de tension sur le réseau", ecoWattWords(days[0], LocalDate.of(2027, 1, 15)))
        assertTrue(parseEcoWatt("nope").isEmpty())
    }
}
