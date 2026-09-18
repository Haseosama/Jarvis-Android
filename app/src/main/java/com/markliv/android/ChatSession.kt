package com.markliv.android

class ChatSession(private val client: GeminiClient) {

    private val history = mutableListOf<ChatMessage>()

    val messages: List<ChatMessage>
        get() = history.toList()

    internal fun messagesWithDraft(text: String): List<ChatMessage> {
        val draft = text.trim()
        if (draft.isEmpty()) {
            throw GeminiException(GeminiClient.ERROR_EMPTY_DRAFT)
        }
        return history + ChatMessage("user", draft)
    }

    internal fun appendTurn(userText: String, assistantText: String) {
        history += ChatMessage("user", userText.trim())
        history += ChatMessage("assistant", assistantText.trim())
    }

    suspend fun send(apiKey: String, model: String, text: String): String {
        val response = client.generate(apiKey, model, messagesWithDraft(text))
        appendTurn(text, response)
        return response
    }

    fun reset() {
        history.clear()
    }
}
