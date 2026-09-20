package com.jarvis.android.core

/** How the wake word currently works. */
internal enum class WakeMode { OFF, ANDROID_RECOGNIZER, OFFLINE_MODEL }

/** What the assistant needs to know about itself, gathered from the real state of the phone. */
internal data class SelfKnowledgeInputs(
    val assistantName: String,
    val androidRelease: String,
    val deviceModel: String,
    val builtInTools: List<String>,
    val plugins: List<String>,
    val accessibilityOn: Boolean,
    val deviceControlEnabled: Boolean,
    val workFolderSet: Boolean,
    val wakeMode: WakeMode,
    val micGranted: Boolean,
    val cameraGranted: Boolean,
    val notificationsAllowed: Boolean,
    val keyCount: Int,
    val briefingEnabled: Boolean,
    val proactiveEnabled: Boolean,
)

private fun onOff(on: Boolean) = if (on) "ON" else "OFF"

/**
 * A block of the system prompt describing what the assistant can do right now and what it cannot,
 * so that it answers "what can you do?" from the truth and does not claim a switched-off ability.
 */
internal fun buildSelfKnowledge(i: SelfKnowledgeInputs): String {
    val phoneControl = when {
        !i.deviceControlEnabled -> "OFF (switched off in Jarvis' settings)"
        !i.accessibilityOn -> "OFF (the accessibility service is not enabled: the user must enable it in Android's accessibility settings)"
        else -> "ON"
    }
    val wake = when (i.wakeMode) {
        WakeMode.OFF -> "OFF"
        WakeMode.ANDROID_RECOGNIZER -> "ON (Android speech recognizer, approximate)"
        WakeMode.OFFLINE_MODEL -> "ON (offline openWakeWord model)"
    }
    val plugins = if (i.plugins.isEmpty()) "none" else i.plugins.joinToString(", ")
    return buildString {
        appendLine("[SELF-KNOWLEDGE]")
        appendLine("You are ${i.assistantName}, an assistant app running on an Android phone (${i.deviceModel}, Android ${i.androidRelease}).")
        appendLine("Tools available right now (${i.builtInTools.size}): ${i.builtInTools.joinToString(", ")}.")
        appendLine("User plugins installed: $plugins.")
        appendLine("Current status:")
        appendLine("- Phone control (read the screen, tap, type, scroll, photos, screenshots): $phoneControl.")
        appendLine("- File manager: " + if (i.workFolderSet) "ON (a work folder is set; nothing outside it can be touched)." else "OFF (no work folder chosen in the settings).")
        appendLine("- Wake word: $wake. Microphone permission: ${onOff(i.micGranted)}. Camera permission: ${onOff(i.cameraGranted)}. Notifications: ${onOff(i.notificationsAllowed)}.")
        appendLine("- API keys stored: ${i.keyCount}. Morning briefing: ${onOff(i.briefingEnabled)}. Background checks: ${onOff(i.proactiveEnabled)}.")
        appendLine("Limits, whatever the user asks (say so plainly, never pretend):")
        appendLine("- You cannot send a message, make a payment, post or delete on your own: messages are drafts the user sends, and sensitive taps need the user's confirmation.")
        appendLine("- You never type a password, a card number or a code, and you do not bypass a confirmation.")
        appendLine("- You cannot confirm that a photo was saved, that a media key or a screenshot was taken up, or that an app accepted a tap: check with screen_read/screen_look or say you cannot check.")
        appendLine("- You see the screen or the camera live only when the user starts it, about one picture every two seconds, not real-time video.")
        appendLine("- You act on files only inside the work folder. Desktop-only abilities (mouse, keyboard, windows, game updaters) do not exist on this phone.")
        appendLine("- Anything marked OFF above is unavailable until the user switches it on: tell them exactly what to switch on.")
        append("If asked what you can do, answer from this block and the tool list, not from memory or guesses.")
    }
}
