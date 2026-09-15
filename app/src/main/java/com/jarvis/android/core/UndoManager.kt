package com.jarvis.android.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque

data class UndoEntry(val label: String, val revert: suspend () -> String)

/**
 * Last-action-reversal stack — Android equivalent of `core/undo.py`. Tools that
 * perform a reversible side effect (create a reminder, write a memory fact)
 * push an [UndoEntry]; saying "undo" pops and runs it. Costs nothing until used.
 */
class UndoManager {
    private val stack = ArrayDeque<UndoEntry>()

    private val _hasUndo = MutableStateFlow(false)
    val hasUndo: StateFlow<Boolean> = _hasUndo

    fun push(entry: UndoEntry) {
        stack.push(entry)
        _hasUndo.value = true
    }

    suspend fun undoLast(): String {
        val entry = stack.poll() ?: return "Nothing to undo."
        _hasUndo.value = stack.isNotEmpty()
        return try {
            entry.revert()
        } catch (e: Exception) {
            "Could not undo '${entry.label}': ${e.message}"
        }
    }
}
