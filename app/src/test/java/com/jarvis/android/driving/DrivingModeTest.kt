package com.jarvis.android.driving

import com.jarvis.android.offline.OfflineAction
import com.jarvis.android.offline.interpret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DrivingModeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `a message is announced with the app and the sender, long texts cut at a word`() {
        assertEquals("Message WhatsApp de Paul : j'arrive dans 10 minutes", announcement("WhatsApp", "Paul", "j'arrive\n dans 10 minutes"))
        assertEquals("Message Messages de quelqu'un.", announcement("Messages", "", ""))
        val long = announcement("SMS", "Léa", "mot ".repeat(200))
        assertTrue(long.endsWith("mot…"))
        assertTrue(long.length < 340)
    }

    @Test
    fun `an automatic answer goes once per person per half hour, and ten an hour at most`() {
        val now = 10_000_000L
        assertTrue(autoReplyAllowed(emptyList(), "Paul", now))
        assertFalse(autoReplyAllowed(emptyList(), "", now))
        assertFalse(autoReplyAllowed(listOf("paul" to now - 60_000), "Paul", now))
        assertTrue(autoReplyAllowed(listOf("Paul" to now - AUTO_REPLY_QUIET_MS), "Paul", now))
        val ten = (1..10).map { "p$it" to now - it * 60_000L }
        assertFalse(autoReplyAllowed(ten, "Léa", now))
        assertTrue(autoReplyAllowed(ten.drop(1), "Léa", now))
    }

    @Test
    fun `the automatic answer is off until the user turns it on`() {
        val store = DrivingStore(File(tmp.root, "d.json"))
        assertFalse(store.load().autoReply)
        assertFalse(store.load().autoStart)
        store.update { it.copy(autoReply = true, replyText = "Au volant") }
        assertEquals("Au volant", DrivingStore(File(tmp.root, "d.json")).load().replyText)
    }

    @Test
    fun `offline, driving mode starts and stops by voice, and stop alone still ends the session`() {
        assertEquals("start", (interpret("Mode conduite") as OfflineAction.ToolCall).args["action"])
        assertEquals("start", (interpret("je prends la route") as OfflineAction.ToolCall).args["action"])
        assertEquals("stop", (interpret("Arrête le mode conduite") as OfflineAction.ToolCall).args["action"])
        assertEquals("stop", (interpret("je suis arrivé") as OfflineAction.ToolCall).args["action"])
        assertTrue((interpret("stop") as OfflineAction.Say).end)
    }
}
