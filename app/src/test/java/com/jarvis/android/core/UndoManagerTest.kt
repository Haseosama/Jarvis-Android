package com.jarvis.android.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UndoManagerTest {
    @Test
    fun `undo is lifo and updates availability`() = runBlocking {
        val undo = UndoManager()
        assertFalse(undo.hasUndo.value)
        assertEquals("Rien à annuler.", undo.undoLast())
        undo.push(UndoEntry("first") { "first" })
        undo.push(UndoEntry("second") { "second" })
        assertTrue(undo.hasUndo.value)
        assertEquals("second", undo.undoLast())
        assertEquals("first", undo.undoLast())
        assertFalse(undo.hasUndo.value)
    }

    @Test
    fun `failed undo remains available for retry`() = runBlocking {
        val undo = UndoManager()
        var attempts = 0
        undo.push(UndoEntry("retry") {
            attempts++
            if (attempts == 1) throw IOException("unavailable")
            "done"
        })
        assertTrue(undo.undoLast().contains("Impossible"))
        assertTrue(undo.hasUndo.value)
        assertEquals("done", undo.undoLast())
        assertFalse(undo.hasUndo.value)
    }

    @Test
    fun `cancellation propagates and preserves pending undo`() = runBlocking {
        val undo = UndoManager()
        undo.push(UndoEntry("cancel") { throw CancellationException("cancelled") })
        try {
            undo.undoLast()
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            assertTrue(undo.hasUndo.value)
        }
    }

    @Test
    fun `concurrent undo is serialized and push during revert is preserved`() = runBlocking {
        val undo = UndoManager()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        undo.push(UndoEntry("slow") {
            calls++
            entered.complete(Unit)
            release.await()
            "slow"
        })
        val first = async(start = CoroutineStart.UNDISPATCHED) { undo.undoLast() }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { undo.undoLast() }
        undo.push(UndoEntry("new") { "new" })
        release.complete(Unit)
        assertEquals("slow", first.await())
        assertEquals("new", second.await())
        assertEquals(1, calls)
        assertFalse(undo.hasUndo.value)
    }

    @Test
    fun `history is bounded to fifty most recent actions`() = runBlocking {
        val undo = UndoManager()
        repeat(55) { index -> undo.push(UndoEntry("$index") { "$index" }) }
        for (index in 54 downTo 5) assertEquals("$index", undo.undoLast())
        assertFalse(undo.hasUndo.value)
    }
}
