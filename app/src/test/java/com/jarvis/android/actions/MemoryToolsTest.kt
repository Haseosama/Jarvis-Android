package com.jarvis.android.actions

import com.jarvis.android.core.UndoManager
import com.jarvis.android.memory.MemEntry
import com.jarvis.android.memory.MemoryManager
import com.jarvis.android.memory.MemoryStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class MemoryToolsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val file get() = File(temporary.root, "memory.json")

    private fun args(key: String = "ville", value: String = "Lyon", category: String = "notes") = buildJsonObject {
        put("key", key)
        put("value", value)
        put("category", category)
    }

    @Test
    fun `remember tool restores overwritten entry and normalized category`() = runBlocking {
        val original = MemEntry("Paris", "2000-01-01")
        file.writeText(Json.encodeToString(MemoryStore(identity = mutableMapOf("ville" to original))))
        val memory = MemoryManager(file)
        val undo = UndoManager()
        RememberTool.run(args(category = " IDENTITY "), memory, undo)
        assertTrue(undo.hasUndo.value)
        undo.undoLast()
        assertEquals(original, memory.load().identity["ville"])
        assertFalse(undo.hasUndo.value)
    }

    @Test
    fun `unknown category creation is correctly undone`() = runBlocking {
        val memory = MemoryManager(file)
        val undo = UndoManager()
        RememberTool.run(args(category = "invalid"), memory, undo)
        assertEquals("Lyon", memory.load().notes.getValue("ville").value)
        undo.undoLast()
        assertTrue(memory.load().notes.isEmpty())
    }

    @Test
    fun `forget tool restores deleted entry and no op does not hide previous undo`() = runBlocking {
        val memory = MemoryManager(file)
        val undo = UndoManager()
        RememberTool.run(args(), memory, undo)
        val original = memory.load().notes.getValue("ville")
        ForgetMemoryTool.run(args(), memory, undo)
        ForgetMemoryTool.run(args(), memory, undo)
        assertTrue(memory.load().notes.isEmpty())
        undo.undoLast()
        assertEquals(original, memory.load().notes["ville"])
        undo.undoLast()
        assertFalse(undo.hasUndo.value)
        assertTrue(memory.load().notes.isEmpty())
    }

    @Test
    fun `unchanged remember does not consume another undo slot`() = runBlocking {
        val memory = MemoryManager(file)
        val undo = UndoManager()
        RememberTool.run(args(), memory, undo)
        RememberTool.run(args(), memory, undo)
        undo.undoLast()
        assertTrue(memory.load().notes.isEmpty())
        assertFalse(undo.hasUndo.value)
    }

    @Test
    fun `invalid arguments do not write or register undo`() = runBlocking {
        val memory = MemoryManager(file)
        val undo = UndoManager()
        RememberTool.run(args(key = " "), memory, undo)
        RememberTool.run(args(value = " "), memory, undo)
        ForgetMemoryTool.run(args(key = " "), memory, undo)
        assertFalse(file.exists())
        assertFalse(undo.hasUndo.value)
    }

    @Test
    fun `failed persistence does not register undo`() = runBlocking {
        file.writeText("corrupt")
        val memory = MemoryManager(file)
        val undo = UndoManager()
        try {
            RememberTool.run(args(), memory, undo)
            throw AssertionError("Expected IOException")
        } catch (_: IOException) {
            assertFalse(undo.hasUndo.value)
            assertEquals("corrupt", file.readText())
        }
    }
}
