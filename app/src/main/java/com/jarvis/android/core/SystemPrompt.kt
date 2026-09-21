package com.jarvis.android.core

import com.jarvis.android.JarvisContainer
import kotlinx.coroutines.flow.first
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
    val selfCtx = buildSelfKnowledge(collectSelfKnowledge(container, assistantName))
    val briefingCtx = if (textMode) "" else container.briefing.prepare()
    return listOf(buildLanguageDirective(if (com.jarvis.android.i18n.Lang.isEnglish) "English" else "French (France)"), modeCtx, timeCtx, identityCtx, selfCtx, memoryBlock, briefingCtx, base)
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
        builtInTools = com.jarvis.android.actions.ToolRegistry.ALL.map { it.name },
        plugins = com.jarvis.android.actions.ToolRegistry.pluginTools().map { it.name },
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
    )
}
