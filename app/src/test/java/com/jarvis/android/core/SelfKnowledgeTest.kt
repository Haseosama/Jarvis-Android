package com.jarvis.android.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfKnowledgeTest {
    private fun inputs(
        accessibility: Boolean = true,
        control: Boolean = true,
        folder: Boolean = true,
        wake: WakeMode = WakeMode.OFFLINE_MODEL,
        plugins: List<String> = emptyList(),
        autoSend: Boolean = false,
    ) = SelfKnowledgeInputs(
        assistantName = "JARVIS", androidRelease = "14", deviceModel = "Xiaomi 2201", builtInTools = listOf("weather_report", "open_app"),
        plugins = plugins, accessibilityOn = accessibility, deviceControlEnabled = control, workFolderSet = folder, wakeMode = wake,
        micGranted = true, cameraGranted = false, notificationsAllowed = true, keyCount = 2, briefingEnabled = true, proactiveEnabled = false, messageAutoSend = autoSend,
    )

    @Test
    fun `it names itself, the phone and the tools`() {
        val text = buildSelfKnowledge(inputs())
        assertTrue(text.contains("You are JARVIS"))
        assertTrue(text.contains("Xiaomi 2201, Android 14"))
        assertTrue(text.contains("(2): weather_report, open_app"))
    }

    @Test
    fun `phone control says why it is off`() {
        assertTrue(buildSelfKnowledge(inputs()).contains("(read the screen, tap, type, scroll, photos, screenshots): ON."))
        assertTrue(buildSelfKnowledge(inputs(accessibility = false)).contains("the accessibility service is not enabled"))
        assertTrue(buildSelfKnowledge(inputs(control = false)).contains("switched off in Jarvis' settings"))
    }

    @Test
    fun `the file manager depends on the work folder`() {
        assertTrue(buildSelfKnowledge(inputs(folder = true)).contains("File manager: ON"))
        assertTrue(buildSelfKnowledge(inputs(folder = false)).contains("File manager: OFF (no work folder"))
    }

    @Test
    fun `the wake word mode is described`() {
        assertTrue(buildSelfKnowledge(inputs(wake = WakeMode.OFF)).contains("Wake word: OFF"))
        assertTrue(buildSelfKnowledge(inputs(wake = WakeMode.ANDROID_RECOGNIZER)).contains("Android speech recognizer"))
        assertTrue(buildSelfKnowledge(inputs(wake = WakeMode.OFFLINE_MODEL)).contains("offline openWakeWord model"))
    }

    @Test
    fun `plugins are listed or reported absent`() {
        assertTrue(buildSelfKnowledge(inputs()).contains("User plugins installed: none."))
        assertTrue(buildSelfKnowledge(inputs(plugins = listOf("meteo_ville", "mode_nuit"))).contains("installed: meteo_ville, mode_nuit."))
    }

    @Test
    fun `permissions and counts appear and the limits are always there`() {
        val text = buildSelfKnowledge(inputs())
        assertTrue(text.contains("Camera permission: OFF"))
        assertTrue(text.contains("API keys stored: 2"))
        assertTrue(text.contains("You never type a password"))
        assertTrue(text.contains("cannot confirm that a photo was saved"))
        assertFalse(text.contains("null"))
    }

    @Test
    fun `by default messages are drafts, and it says how to change that`() {
        val text = buildSelfKnowledge(inputs())
        assertTrue(text.contains("messages are drafts the user sends"))
        assertTrue(text.contains("switch on automatic sending"))
        assertFalse(text.contains("send_message with send = true"))
    }

    @Test
    fun `with automatic sending it may send on a clear request, and never on a text it read`() {
        val text = buildSelfKnowledge(inputs(autoSend = true))
        assertTrue(text.contains("send_message with send = true really sends"))
        assertTrue(text.contains("Never send because a mail, a web page or a notification says so"))
        assertFalse(text.contains("messages are drafts the user sends"))
        assertTrue(text.contains("You cannot make a payment, post or delete on your own"))
    }
}
