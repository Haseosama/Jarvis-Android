package com.jarvis.android.i18n

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class I18nTest {
    @After
    fun reset() {
        Lang.code = Lang.FRENCH
    }

    @Test
    fun `french stays the source text`() {
        Lang.code = Lang.FRENCH
        assertEquals("Paramètres", tr("Paramètres"))
    }

    @Test
    fun `english is looked up and placeholders are filled`() {
        Lang.code = Lang.ENGLISH_CODE
        assertEquals("Settings", tr("Paramètres"))
        assertEquals("Key 2 ✓", trf("Clé {0} ✓", 2))
        assertEquals("unknown text", tr("unknown text"))
    }

    @Test
    fun `every text wrapped in the interface code has an english version`() {
        val call = Regex("\\btrf?\\(\"((?:[^\"\\\\]|\\\\.)*)\"")
        val missing = File("src/main/java/com/jarvis/android").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.parentFile.name != "i18n" }
            .flatMap { f -> call.findAll(f.readText()).map { f.name to it.groupValues[1] } }
            .filter { (_, key) -> key !in ENGLISH }
            .toList()
        assertTrue("textes sans traduction : $missing", missing.isEmpty())
    }

    @Test
    fun `english placeholders match the french ones`() {
        val ph = Regex("\\{\\d+\\}")
        ENGLISH.forEach { (fr, en) ->
            assertEquals("placeholders de « $fr »", ph.findAll(fr).map { it.value }.toSet(), ph.findAll(en).map { it.value }.toSet())
        }
    }
}
