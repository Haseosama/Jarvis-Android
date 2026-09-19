package com.jarvis.android.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque

data class UndoEntry(val label: String, val revert: suspend () -> String)

class UndoManager {
    private val stack = ArrayDeque<UndoEntry>()
    private val undoMutex = Mutex()

    private val _hasUndo = MutableStateFlow(false)
    val hasUndo: StateFlow<Boolean> = _hasUndo.asStateFlow()

    fun push(entry: UndoEntry) = synchronized(stack) {
        stack.push(entry)
        while (stack.size > 50) stack.removeLast()
        _hasUndo.value = true
    }

    suspend fun undoLast(): String = undoMutex.withLock {
        val entry = synchronized(stack) { stack.peek() } ?: return@withLock "Rien à annuler."
        try {
            val result = entry.revert()
            synchronized(stack) {
                stack.removeFirstOccurrence(entry)
                _hasUndo.value = stack.isNotEmpty()
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Impossible d’annuler « ${entry.label} » : ${e.message ?: "erreur inconnue"}"
        }
    }
}
