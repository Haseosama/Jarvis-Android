package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.core.UndoEntry
import com.jarvis.android.core.UndoManager
import com.jarvis.android.memory.MemoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Look up a stored fact on demand — backs the "[ALSO REMEMBERED]" index in the system prompt. */
object RecallMemoryTool : Tool {
    override val name = "recall_memory"
    override val description =
        "Search stored long-term memory. Pass a keyword or the user's own question — filler words, plurals, " +
            "French/English wording and dictation slips are handled. Use this before saying you don't know " +
            "something about the user."
    override val parameters = objectSchema {
        string("query", "Keyword or question to search for, or empty to list everything stored.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String =
        ctx.memoryManager.search(args.stringArg("query"))
}

/** Explicitly save a new fact — used when the user says "remember that…". */
object RememberTool : Tool {
    override val name = "remember_fact"
    override val description =
        "Save a fact about the user to long-term memory. Also use it for standing instructions about how to " +
            "speak to them (tutoiement, shorter answers, no emoji): store those under category 'preferences' " +
            "and they will be applied as a rule in later conversations."
    override val parameters = objectSchema(required = listOf("key", "value")) {
        string("key", "Short identifier, e.g. 'sister_name' or 'tutoiement'.")
        string("value", "The fact itself, or the instruction to follow.")
        string("category", "One of: identity, preferences, projects, relationships, wishes, notes. Default notes.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String =
        run(args, ctx.memoryManager, ctx.undoManager)

    internal suspend fun run(args: JsonObject, memory: MemoryManager, undo: UndoManager): String {
        val key = args.stringArg("key")
        val value = args.stringArg("value")
        val category = args.stringArg("category", "notes")
        if (key.isBlank() || value.isBlank()) return "Une clé et une valeur sont nécessaires pour mémoriser."
        return withContext(Dispatchers.IO) {
            val change = memory.rememberChange(key, value, category)
            if (change.changed) {
                undo.push(UndoEntry("mémorisation ${change.category}/$key") { memory.restore(change) })
            }
            change.message
        }
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
        run(args, ctx.memoryManager, ctx.undoManager)

    internal suspend fun run(args: JsonObject, memory: MemoryManager, undo: UndoManager): String {
        val key = args.stringArg("key")
        if (key.isBlank()) return "Une clé est nécessaire pour supprimer un souvenir."
        return withContext(Dispatchers.IO) {
            val change = memory.forgetChange(key, args.stringArg("category", "notes"))
            if (change.changed) {
                undo.push(UndoEntry("suppression ${change.category}/$key") { memory.restore(change) })
            }
            change.message
        }
    }
}

/** Reverses the last reversible tool call — voice equivalent of saying "undo". */
object UndoTool : Tool {
    override val name = "undo"
    override val description = "Undo the last reversible action Jarvis took."

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = ctx.undoManager.undoLast()
}
