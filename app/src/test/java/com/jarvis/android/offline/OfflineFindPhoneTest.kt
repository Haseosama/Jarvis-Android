package com.jarvis.android.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineFindPhoneTest {
    private fun call(text: String) = interpret(text) as OfflineAction.ToolCall

    @Test
    fun `the ways to ask where the phone is all ring it`() {
        for (text in listOf("Où es-tu ?", "Jarvis, où es-tu", "Où est mon téléphone ?", "Fais sonner le téléphone", "t'es où")) {
            val a = call(text)
            assertEquals(text, "find_phone", a.name)
            assertEquals(text, "ring", a.args["action"])
        }
    }

    @Test
    fun `and the ring can be stopped by voice`() {
        assertEquals("stop", call("Arrête de sonner").args["action"])
        assertEquals("stop", call("C'est bon, je t'ai trouvé").args["action"])
    }

    @Test
    fun `a longer sentence that merely mentions the phone does not ring it`() {
        val a = interpret("où est mon téléphone de secours dans la liste")
        assertTrue(a !is OfflineAction.ToolCall || a.name != "find_phone")
    }
}
