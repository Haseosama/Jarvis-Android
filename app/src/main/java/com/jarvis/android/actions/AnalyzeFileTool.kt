package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.files.ERROR_NO_FILE
import com.jarvis.android.files.buildFileRequest
import com.jarvis.android.files.parseFileAnswer
import com.jarvis.android.rest.RestChatException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Answers a question about the file the user attached with the paperclip button. */
object AnalyzeFileTool : Tool {
    override val name = "analyze_file"
    override val description =
        "Analyser le fichier que l’utilisateur a joint (PDF, image, texte, Word, audio) et répondre à une question à son sujet. Le fichier est envoyé à Gemini."
    override val parameters = objectSchema {
        string("question", "Ce que l’utilisateur veut savoir du fichier ; vide pour un résumé.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val file = ctx.attachedFiles.current.value ?: return ERROR_NO_FILE
        return try {
            val request = buildFileRequest(args["question"]?.jsonPrimitive?.contentOrNull.orEmpty(), file)
            val model = ctx.configStore.snapshotRestModel()
            "Fichier « ${file.name} » :\n" + parseFileAnswer(ctx.restChat.transport.generate(model, request))
        } catch (e: RestChatException) {
            "Analyse du fichier impossible : ${e.message}"
        }
    }
}
