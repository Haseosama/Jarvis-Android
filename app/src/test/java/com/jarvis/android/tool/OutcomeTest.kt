package com.jarvis.android.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutcomeTest {
    @Test
    fun `a result carries its outcome and loses it when said`() {
        assertEquals(Outcome.DONE, outcomeOf(done("Volume set to 40%.")))
        assertEquals(Outcome.UNVERIFIED, outcomeOf(unverified("Asked.")))
        assertEquals(Outcome.FAILED, outcomeOf(failed("No.")))
        assertNull(outcomeOf("Opening Spotify."))
        assertEquals("Volume set to 40%.", withoutOutcome(done("Volume set to 40%.")))
        assertEquals("plain", withoutOutcome("plain"))
    }

    @Test
    fun `the prompt rule names every word`() {
        Outcome.entries.forEach { assertTrue(it.tag, OUTCOME_RULE.contains(it.tag)) }
    }
}
