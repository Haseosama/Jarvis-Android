package com.jarvis.android.tasks

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TaskListStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = TaskListStore(tmp.newFile())

    @Test fun `an empty store has nothing on the default list`() = runBlocking {
        val s = store()
        assertTrue(s.items(DEFAULT_LIST_NAME).isEmpty())
        assertTrue(s.all().isEmpty())
    }

    @Test fun `adding puts an item on the named list, or on the default one without a name`() = runBlocking {
        val s = store()
        s.add("", "du lait")
        s.add("courses", "des oeufs")
        assertEquals(listOf("du lait"), s.items(DEFAULT_LIST_NAME).map { it.text })
        assertEquals(listOf("des oeufs"), s.items("courses").map { it.text })
        assertEquals(setOf(DEFAULT_LIST_NAME, "courses"), s.all().keys)
    }

    @Test fun `list names are matched without regard to case or accents`() = runBlocking {
        val s = store()
        s.add("Tâches", "appeler le dentiste")
        assertEquals(1, s.items("taches").size)
        assertEquals(1, s.items("TÂCHES").size)
    }

    @Test fun `an item can be found, checked off, and unchecked by a partial, accent-insensitive match`() = runBlocking {
        val s = store()
        s.add("courses", "un litre de lait demi-écrémé")
        assertTrue(s.setDone("courses", "lait", true))
        assertTrue(s.items("courses").single().done)
        assertTrue(s.setDone("courses", "LAIT", false))
        assertFalse(s.items("courses").single().done)
        assertFalse(s.setDone("courses", "fromage", true))
    }

    @Test fun `a different French article does not stop a match — du lait is found by le lait`() = runBlocking {
        val s = store()
        s.add("courses", "du lait")
        assertTrue(s.setDone("courses", "le lait", true))
        assertTrue(s.items("courses").single().done)
        assertTrue(s.remove("courses", "un lait"))
    }

    @Test fun `checking prefers a not-yet-done item, so the same word twice is not confused`() = runBlocking {
        val s = store()
        s.add("courses", "pommes")
        s.add("courses", "pommes de terre")
        assertTrue(s.setDone("courses", "pommes", true))
        val items = s.items("courses")
        assertTrue(items.first { it.text == "pommes" }.done)
        assertFalse(items.first { it.text == "pommes de terre" }.done)
    }

    @Test fun `removing takes the item off the list, and an empty list disappears from all()`() = runBlocking {
        val s = store()
        s.add("courses", "du pain")
        assertTrue(s.remove("courses", "pain"))
        assertTrue(s.items("courses").isEmpty())
        assertTrue(s.all().isEmpty())
        assertFalse(s.remove("courses", "pain"))
    }

    @Test fun `clear empties a list, clearDone removes only what is checked`() = runBlocking {
        val s = store()
        s.add("courses", "lait"); s.add("courses", "pain"); s.add("courses", "oeufs")
        s.setDone("courses", "lait", true)
        assertEquals(1, s.clearDone("courses"))
        assertEquals(listOf("pain", "oeufs"), s.items("courses").map { it.text })
        s.clear("courses")
        assertTrue(s.items("courses").isEmpty())
    }

    @Test fun `what was added first comes back first`() = runBlocking {
        val s = store()
        s.add("courses", "un"); s.add("courses", "deux"); s.add("courses", "trois")
        assertEquals(listOf("un", "deux", "trois"), s.items("courses").map { it.text })
    }

    @Test fun `a blank item is not added, and a damaged file behaves as an empty store`() = runBlocking {
        val s = store()
        assertFalse(s.add("courses", "   "))
        assertTrue(s.items("courses").isEmpty())
    }

    @Test fun `at most MAX_LISTS distinct lists, and at most MAX_ITEMS_PER_LIST items each`() = runBlocking {
        val s = store()
        for (i in 0 until MAX_LISTS) assertTrue(s.add("liste$i", "x"))
        assertFalse(s.add("une liste de trop", "x"))
        val full = store()
        for (i in 0 until MAX_ITEMS_PER_LIST) assertTrue(full.add("courses", "item $i"))
        assertFalse(full.add("courses", "un de trop"))
    }

    @Test fun `findItem prefers the shortest, most exact match`() {
        val items = listOf(TaskItem("compote de pommes"), TaskItem("pommes"))
        assertEquals("pommes", findItem(items, "pomme")?.text)
        assertNull(findItem(emptyList(), "rien"))
        assertNull(findItem(items, ""))
    }
}
