package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.core.UndoEntry
import kotlinx.serialization.json.JsonObject

/** Look up a stored fact on demand — backs the "[ALSO REMEMBERED]" index in the system prompt. */
object RecallMemoryTool : Tool {
    override val name = "recall_memory"
    override val description =
        "Search stored long-term memory for a keyword. Use this before saying you don't know something about the user."
    override val parameters = objectSchema {
        string("query", "Keyword to search for, or empty to list everything stored.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String =
        ctx.memoryManager.search(args.stringArg("query"))
}

/** Explicitly save a new fact — used when the user says "remember that…". */
object RememberTool : Tool {
    override val name = "remember_fact"
    override val description = "Save a fact about the user to long-term memory."
    override val parameters = objectSchema(required = listOf("key", "value")) {
        string("key", "Short identifier, e.g. 'sister_name'.")
        string("value", "The fact itself.")
        string("category", "One of: identity, preferences, projects, relationships, wishes, notes. Default notes.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val key = args.stringArg("key")
        val value = args.stringArg("value")
        val category = args.stringArg("category", "notes")
        if (key.isBlank() || value.isBlank()) return "I need both a key and a value to remember."
        val result = ctx.memoryManager.remember(key, value, category)
        ctx.undoManager.push(UndoEntry("remember $category/$key") {
            ctx.memoryManager.forget(key, category)
        })
        return result
    }
}

/** Delete a stored fact — the memory panel's per-entry delete, also reachable by voice. */
object ForgetMemoryTool : Tool {
    override val name = "forget_fact"
    override val description = "Delete a previously remembered fact."
    override val parameters = objectSchema(required = listOf("key")) {
        string("key", "The fact's key, as shown by recall_memory.")
        string("category", "The category it's stored under. Default notes.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String =
        ctx.memoryManager.forget(args.stringArg("key"), args.stringArg("category", "notes"))
}

/** Reverses the last reversible tool call — voice equivalent of saying "undo". */
object UndoTool : Tool {
    override val name = "undo"
    override val description = "Undo the last reversible action Jarvis took."

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = ctx.undoManager.undoLast()
}
