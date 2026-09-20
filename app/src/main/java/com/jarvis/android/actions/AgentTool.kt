package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Hands a multi-step request to the background agent. */
object AgentTool : Tool {
    override val name = "agent_task"
    override val description =
        "Lancer en arrière-plan une tâche à plusieurs étapes sur le téléphone (par exemple ouvrir une appli, chercher un élément puis le partager). " +
            "Retourne tout de suite ; la fin de la tâche est annoncée. Actions : start (défaut), status, cancel."
    override val parameters = objectSchema {
        string("goal", "L’objectif complet, en une ou deux phrases.")
        string("action", "'start' (défaut), 'status' ou 'cancel'.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String =
        when (args["action"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().lowercase()) {
            "cancel" -> ctx.agent.cancel()
            "status" -> ctx.agent.status()
            else -> ctx.agent.start(args["goal"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
}
