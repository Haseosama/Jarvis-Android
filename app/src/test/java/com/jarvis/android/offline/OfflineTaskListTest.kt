package com.jarvis.android.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The task_list patterns of OfflineIntents.kt: interpret() only, no store involved (that is TaskListStoreTest). */
class OfflineTaskListTest {
    private fun call(text: String) = interpret(text) as OfflineAction.ToolCall

    @Test fun `adding to the default list, and to a named one`() {
        val a = call("ajoute du lait a la liste")
        assertEquals("task_list", a.name)
        assertEquals("add", a.args["action"]); assertEquals("du lait", a.args["item"]); assertNull(a.args["liste"])

        val b = call("ajoute des oeufs a ma liste de courses")
        assertEquals("des oeufs", b.args["item"]); assertEquals("courses", b.args["liste"])

        val c = call("mets appeler le dentiste sur ma liste de taches")
        assertEquals("appeler le dentiste", c.args["item"]); assertEquals("taches", c.args["liste"])
    }

    @Test fun `checking an item off, by name or by the natural i-bought-it phrasing`() {
        val a = call("coche le lait sur ma liste de courses")
        assertEquals("done", a.args["action"]); assertEquals("le lait", a.args["item"]); assertEquals("courses", a.args["liste"])

        val b = call("j ai achete du pain")
        assertEquals("done", b.args["action"]); assertEquals("du pain", b.args["item"]); assertNull(b.args["liste"])

        val c = call("j ai fait la vaisselle")
        assertEquals("done", c.args["action"]); assertEquals("la vaisselle", c.args["item"])
    }

    @Test fun `removing an item`() {
        val a = call("retire le lait de ma liste de courses")
        assertEquals("remove", a.args["action"]); assertEquals("le lait", a.args["item"]); assertEquals("courses", a.args["liste"])
        val b = call("supprime les oeufs de la liste")
        assertEquals("remove", b.args["action"]); assertEquals("les oeufs", b.args["item"])
    }

    @Test fun `reading and clearing a list`() {
        val a = call("qu est ce qu il y a sur ma liste de courses")
        assertEquals("list", a.args["action"]); assertEquals("courses", a.args["liste"])
        val b = call("montre ma liste")
        assertEquals("list", b.args["action"]); assertNull(b.args["liste"])
        val c = call("vide ma liste de courses")
        assertEquals("clear", c.args["action"]); assertEquals("courses", c.args["liste"])
    }

    @Test fun `the reply speaks the tool's own answer, not a fixed sentence`() {
        val a = call("ajoute du lait a la liste")
        assertEquals("le résultat exact du tool", a.format!!("le résultat exact du tool"))
    }

    @Test fun `help mentions lists, and something unrelated is still unknown`() {
        assertTrue(OFFLINE_HELP.contains("liste"))
        assertEquals(OfflineAction.Unknown, interpret("raconte-moi une histoire"))
    }
}
