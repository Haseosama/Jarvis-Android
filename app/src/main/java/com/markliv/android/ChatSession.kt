package com.markliv.android

import org.json.JSONObject

class ChatSession(private val client: GeminiClient, private val toolbox: MarkToolbox? = null) {

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
        val draft = text.trim()
        if (draft.isEmpty()) throw GeminiException(GeminiClient.ERROR_EMPTY_DRAFT)

        history += ChatMessage("user", draft)
        return executeTurn(apiKey, model)
    }

    private suspend fun executeTurn(apiKey: String, model: String): String {
        val tools = toolbox?.let { MarkToolbox.getToolDeclarations() }
        val response = client.generate(apiKey, model, history, tools)

        return when (response) {
            is GeminiResponse.Text -> {
                history += ChatMessage("assistant", response.text)
                response.text
            }
            is GeminiResponse.FunctionCall -> {
                val callObj = JSONObject().put("name", response.name).put("args", response.args)
                history += ChatMessage("assistant", "", functionCall = callObj)

                val toolResult = toolbox?.execute(response.name, response.args) ?: "Erreur : Toolbox non disponible"

                val respObj = JSONObject().put("name", response.name).put("response", JSONObject().put("result", toolResult))
                history += ChatMessage("function", "", functionResponse = respObj)

                executeTurn(apiKey, model)
            }
        }
    }

    fun reset() {
        history.clear()
    }
}
