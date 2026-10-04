package com.jarvis.android.core

enum class JarvisState { ASLEEP, CONNECTING, LISTENING, THINKING, SPEAKING, ERROR }

enum class ConversationRole { USER, ASSISTANT, SYSTEM }

data class ConversationMessage(
    val role: ConversationRole,
    val text: String,
    val complete: Boolean = false,
)

internal const val MAX_MESSAGE_CHARS = 8_000
internal const val MAX_CONVERSATION_MESSAGES = 100

internal fun appendConversation(
    messages: List<ConversationMessage>,
    role: ConversationRole,
    text: String,
    complete: Boolean = false,
): List<ConversationMessage> {
    if (text.isEmpty()) return messages
    val last = messages.lastOrNull()
    val result = if (!complete && last?.role == role && !last.complete) {
        messages.dropLast(1) + last.copy(text = (last.text + text).take(MAX_MESSAGE_CHARS))
    } else {
        finishConversationTurn(messages) + ConversationMessage(role, text.take(MAX_MESSAGE_CHARS), complete)
    }
    return result.takeLast(MAX_CONVERSATION_MESSAGES)
}

internal fun finishConversationTurn(messages: List<ConversationMessage>): List<ConversationMessage> =
    messages.map { if (it.complete) it else it.copy(complete = true) }
