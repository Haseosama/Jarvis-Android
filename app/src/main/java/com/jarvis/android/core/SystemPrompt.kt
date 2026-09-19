package com.jarvis.android.core

import com.jarvis.android.JarvisContainer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * System instruction shared by the voice session and the REST text chat: language rule, clock,
 * identity, the memory core, then the tool routing prompt. [textMode] adds a note telling the
 * model it is answering on screen, not through a speaker.
 */
internal suspend fun buildSystemInstruction(container: JarvisContainer, textMode: Boolean = false): String {
    val assistantName = container.configStore.snapshotAssistantName()
    val userName = container.configStore.snapshotUserName()
    val memoryBlock = container.memoryManager.formatForPrompt()
    val base = readPromptAsset(container, "system_prompt.txt")

    val now = SimpleDateFormat("EEEE, MMMM d, yyyy — hh:mm a", Locale.getDefault()).format(Date())
    val timeCtx = "[CURRENT DATE & TIME]\nRight now it is: $now\nUse this to calculate exact times for reminders.\n"

    val addr = if (userName.isNotBlank()) "ADDRESS: Always call the user '$userName'."
    else "ADDRESS: Address the user with the ordinary respectful form for a superior in the language you are currently speaking."
    val identityCtx = "[IDENTITY]\nYour name is $assistantName. Always refer to yourself as $assistantName.\n$addr\n"

    val modeCtx = if (textMode) TEXT_MODE_DIRECTIVE else ""
    return listOf(buildLanguageDirective(), modeCtx, timeCtx, identityCtx, memoryBlock, base)
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

internal const val TEXT_MODE_DIRECTIVE =
    "[MODE]\n" +
        "You are answering in a TEXT chat on the phone screen. There is no microphone and no speaker: " +
        "write short, clear replies, and never say that you are listening or speaking. " +
        "Use tools when they help, exactly as you would in a voice conversation.\n"

private fun readPromptAsset(container: JarvisContainer, name: String): String =
    try {
        container.appContext.assets.open(name).use { stream ->
            BufferedReader(InputStreamReader(stream)).readText()
        }
    } catch (_: Exception) {
        ""
    }
