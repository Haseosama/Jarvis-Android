package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.stringArg
import com.jarvis.android.tool.objectSchema

/**
 * Code review / debugging / generation — Android port of `actions/code_helper.py`.
 * Uses a plain (non-Live) Gemini `generateContent` REST call, since this is a
 * one-shot text task rather than a realtime conversation turn.
 */
object CodeHelperTool : Tool {
    override val name = "code_helper"
    override val description = "Review, debug, explain, or generate a snippet of code."
    override val parameters = objectSchema(required = listOf("request")) {
        string("request", "The code question, plus any code to look at.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val request = args.stringArg("request")
        if (request.isBlank()) return@withContext "What would you like help with?"

        val payload = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", request) } }
                }
            }
        }
        // the app's one transport: the key rotation, the ladder of models, and the other model when Gemini cannot answer
        try {
            when (val reply = com.jarvis.android.rest.parseGenerateResponse(ctx.restChat.transport.generate(ctx.configStore.snapshotRestModel(), payload))) {
                is com.jarvis.android.rest.RestReply.Text -> reply.text
                is com.jarvis.android.rest.RestReply.Calls -> "No answer returned."
            }
        } catch (e: Exception) {
            "Code helper failed: ${e.message}"
        }
    }
}
