package com.jarvis.android.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyRotationTest {
    private fun rotation(vararg keys: String?) = KeyRotation { keys.toList() }

    @Test
    fun `starts on the first configured key`() {
        assertEquals("a", rotation("a", "b", "c").current())
        assertEquals("b", rotation(null, "b", null).current())
        assertEquals("b", rotation("", "b", null).current())
    }

    @Test
    fun `no key at all gives null`() {
        assertNull(rotation(null, null, null).current())
        assertNull(rotation(null, null, null).rejected("x"))
    }

    @Test
    fun `a rejected key moves on to the next one and stays there`() {
        val r = rotation("a", "b", "c")
        assertEquals("b", r.rejected("a"))
        assertEquals("b", r.current())
        assertEquals(2, r.activeSlot())
        assertEquals("c", r.rejected("b"))
        assertEquals("c", r.current())
    }

    @Test
    fun `rotation wraps around and skips empty slots`() {
        val r = rotation("a", null, "c")
        assertEquals("c", r.rejected("a"))
        assertEquals("a", r.rejected("c"))
    }

    @Test
    fun `a single key has nowhere to go`() {
        val r = rotation("a", null, null)
        assertNull(r.rejected("a"))
        assertEquals("a", r.current())
    }

    @Test
    fun `a duplicate of the rejected key does not count as another key`() {
        val r = rotation("a", "a", null)
        assertNull(r.rejected("a"))
    }

    @Test
    fun `changes to the slots are seen`() {
        var keys: List<String?> = listOf("a", null, null)
        val r = KeyRotation { keys }
        assertEquals("a", r.current())
        keys = listOf(null, "b", null)
        assertEquals("b", r.current())
    }
}
