package com.jarvis.android.core

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionResumptionTest {
    private fun retry(decision: ReconnectDecision): ReconnectDecision.Retry {
        assertTrue("attendu Retry, obtenu $decision", decision is ReconnectDecision.Retry)
        return decision as ReconnectDecision.Retry
    }

    @Test
    fun `a fresh connection that never became ready is not retried`() {
        val decision = decideReconnect(
            wasReady = false, hadHandle = false, hasHandle = false, liveMs = 0, consecutiveDrops = 0,
        )
        assertEquals(ReconnectDecision.GiveUp, decision)
    }

    @Test
    fun `a ready connection that drops resumes with the handle`() {
        val r = retry(
            decideReconnect(wasReady = true, hadHandle = false, hasHandle = true, liveMs = 5_000, consecutiveDrops = 0)
        )
        assertTrue(r.useHandle)
        assertEquals(1, r.drops)
        assertEquals(RECONNECT_BASE_DELAY_MS, r.delayMs)
    }

    @Test
    fun `a ready connection with no handle yet reconnects from scratch`() {
        val r = retry(
            decideReconnect(wasReady = true, hadHandle = false, hasHandle = false, liveMs = 5_000, consecutiveDrops = 0)
        )
        assertFalse(r.useHandle)
    }

    @Test
    fun `a refused handle falls back to a fresh session once`() {
        val r = retry(
            decideReconnect(wasReady = false, hadHandle = true, hasHandle = true, liveMs = 0, consecutiveDrops = 1)
        )
        assertFalse(r.useHandle)
        assertEquals(2, r.drops)
    }

    @Test
    fun `a fresh retry that fails to become ready ends the loop`() {
        val decision = decideReconnect(
            wasReady = false, hadHandle = false, hasHandle = false, liveMs = 0, consecutiveDrops = 2,
        )
        assertEquals(ReconnectDecision.GiveUp, decision)
    }

    @Test
    fun `backoff doubles and is capped`() {
        val delays = (0 until MAX_CONSECUTIVE_DROPS).map { previous ->
            retry(
                decideReconnect(wasReady = true, hadHandle = true, hasHandle = true, liveMs = 1_000, consecutiveDrops = previous)
            ).delayMs
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L), delays)
        assertTrue(delays.all { it <= RECONNECT_MAX_DELAY_MS })
    }

    @Test
    fun `a flapping link is abandoned after the maximum number of quick drops`() {
        val decision = decideReconnect(
            wasReady = true, hadHandle = true, hasHandle = true, liveMs = 1_000, consecutiveDrops = MAX_CONSECUTIVE_DROPS,
        )
        assertEquals(ReconnectDecision.GiveUp, decision)
    }

    @Test
    fun `a healthy session resets the drop counter`() {
        val r = retry(
            decideReconnect(
                wasReady = true, hadHandle = true, hasHandle = true,
                liveMs = HEALTHY_SESSION_MS, consecutiveDrops = MAX_CONSECUTIVE_DROPS,
            )
        )
        assertEquals(1, r.drops)
        assertEquals(RECONNECT_BASE_DELAY_MS, r.delayMs)
        assertTrue(r.useHandle)
    }

    @Test
    fun `a resumable update from the server yields a resumption event`() {
        val events = parseServerMessage("""{"sessionResumptionUpdate":{"newHandle":"h-123","resumable":true}}""")
        assertEquals(listOf<LiveEvent>(LiveEvent.ResumptionUpdate("h-123")), events)
    }

    @Test
    fun `a non-resumable update is ignored so the last good handle is kept`() {
        assertTrue(parseServerMessage("""{"sessionResumptionUpdate":{"newHandle":"h-9","resumable":false}}""").isEmpty())
        assertTrue(parseServerMessage("""{"sessionResumptionUpdate":{"resumable":true}}""").isEmpty())
        assertTrue(parseServerMessage("""{"sessionResumptionUpdate":{}}""").isEmpty())
    }

    private fun setupOf(handle: String?) = LiveProtocol.buildSetup(
        model = "models/test",
        systemInstruction = "instruction",
        toolDeclarations = emptyList(),
        voiceName = "Puck",
        resumeHandle = handle,
    )["setup"]!!.jsonObject

    @Test
    fun `setup carries the resumption handle when reconnecting`() {
        val resumption = setupOf("h-123")["sessionResumption"]!!.jsonObject
        assertEquals("h-123", resumption["handle"]!!.jsonPrimitive.content)
    }

    @Test
    fun `first setup asks for resumption handles without sending one`() {
        val setup = setupOf(null)
        val resumption = setup["sessionResumption"]!!.jsonObject
        assertFalse(resumption.containsKey("handle"))
        assertTrue(setup.containsKey("contextWindowCompression"))
    }
}
