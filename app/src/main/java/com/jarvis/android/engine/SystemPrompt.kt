package com.jarvis.android.engine

import com.jarvis.android.JarvisContainer
import kotlinx.coroutines.flow.first
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.jarvis.android.core.SelfKnowledgeInputs
import com.jarvis.android.core.WakeMode
import com.jarvis.android.core.buildSelfKnowledge

/**
 * System instruction shared by the voice session and the REST text chat: language rule, clock,
 * identity, the memory core, then the tool routing prompt. [textMode] adds a note telling the
 * model it is answering on screen, not through a speaker.
 */
internal suspend fun buildSystemInstruction(container: JarvisContainer, textMode: Boolean = false): String {
    val assistantName = container.configStore.snapshotAssistantName()
    val userName = container.configStore.snapshotUserName()
    val memoryBlock = container.memoryManager.formatForPrompt()
    val recentExchanges = if (container.configStore.keepSessionTranscripts.first()) container.sessionTranscripts.promptBlock() else ""
    val base = readPromptAsset(container, "system_prompt.txt")

    val now = SimpleDateFormat("EEEE, MMMM d, yyyy — hh:mm a", Locale.getDefault()).format(Date())
    val timeCtx = "[CURRENT DATE & TIME]\nRight now it is: $now\nUse this to calculate exact times for reminders.\n"

    val addr = if (userName.isNotBlank()) "ADDRESS: Always call the user '$userName'."
    else "ADDRESS: Address the user with the ordinary respectful form for a superior in the language you are currently speaking."
    val identityCtx = "[IDENTITY]\nYour name is $assistantName. Always refer to yourself as $assistantName.\n$addr\n"

    val modeCtx = modeDirective(textMode, inCar = textMode && com.jarvis.android.core.CarAudio.projection(container.appContext), driving = com.jarvis.android.driving.DrivingMode.active)
    val selfCtx = buildSelfKnowledge(collectSelfKnowledge(container, assistantName))
    val briefingCtx = if (textMode) "" else container.briefing.prepare()
    val outcomeCtx = "[TOOL OUTCOMES]\n" + com.jarvis.android.tool.OUTCOME_RULE + "\n"
    return listOf(buildLanguageDirective(if (com.jarvis.android.i18n.Lang.isEnglish) "English" else "French (France)"), modeCtx, timeCtx, identityCtx, selfCtx, outcomeCtx, memoryBlock, recentExchanges, briefingCtx, base)
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

internal const val DRIVING_DIRECTIVE =
    "[DRIVING]\n" +
        "The user is driving: answer in one or two short spoken sentences, no lists, no reading of long texts unless asked, " +
        "and never ask them to look at or touch the screen. Navigation, calls, messages and music come first.\n"

/** The note on how the answer reaches the user: on the phone's screen, said by the car (Android Auto), or spoken while driving. */
internal fun modeDirective(textMode: Boolean, inCar: Boolean, driving: Boolean): String = when {
    textMode && inCar -> CAR_TEXT_DIRECTIVE
    textMode -> TEXT_MODE_DIRECTIVE
    driving -> DRIVING_DIRECTIVE
    else -> ""
}

internal const val CAR_TEXT_DIRECTIVE =
    "[CAR]\n" +
        "The user is driving and talks to you through Android Auto: what they say is dictated, and your reply is read aloud by the car " +
        "and shown on its screen. Answer in one to three short spoken sentences, plain words only: no Markdown, no lists, no tables, no links, " +
        "no emojis. Never ask them to look at or touch the phone. Use tools exactly as you would on the phone (reminders, messages, calls, " +
        "navigation, weather, the PC…), and say plainly what you did.\n"

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

/** Reads the live state (permissions, switches, tools) that the self-knowledge block describes. */
internal suspend fun collectSelfKnowledge(container: JarvisContainer, assistantName: String): SelfKnowledgeInputs {
    val context = container.appContext
    fun granted(permission: String) =
        context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val store = container.configStore
    val wakeMode = when {
        !store.wakeWordEnabled.first() -> WakeMode.OFF
        container.wakeModel.installed() -> WakeMode.OFFLINE_MODEL
        else -> WakeMode.ANDROID_RECOGNIZER
    }
    return SelfKnowledgeInputs(
        assistantName = assistantName,
        androidRelease = android.os.Build.VERSION.RELEASE.orEmpty(),
        deviceModel = listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" "),
        builtInTools = com.jarvis.android.registry.ToolRegistry.ALL.map { it.name },
        plugins = com.jarvis.android.registry.ToolRegistry.pluginTools().map { it.name },
        accessibilityOn = com.jarvis.android.device.JarvisAccessibilityService.instance != null,
        deviceControlEnabled = store.deviceControlEnabled.first(),
        workFolderSet = store.workFolder.first().isNotBlank(),
        wakeMode = wakeMode,
        micGranted = granted(android.Manifest.permission.RECORD_AUDIO),
        cameraGranted = granted(android.Manifest.permission.CAMERA),
        notificationsAllowed = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled(),
        keyCount = store.keySlotsFilled().count { it },
        briefingEnabled = store.briefingEnabled.first(),
        proactiveEnabled = store.proactiveEnabled.first(),
        messageAutoSend = store.messageAutoSend.first(),
        skipConfirmations = store.skipConfirmations.first(),
        connectors = container.connectors.list().filter { it.enabled }.map { c ->
            c.name + when {
                c.needsLogin -> " (login needed)"
                c.status.isNotEmpty() && c.tools.isEmpty() -> " (unreachable)"
                else -> " (${c.tools.size} tools)"
            }
        },
    )
}
