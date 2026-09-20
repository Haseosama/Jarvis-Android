package com.jarvis.android.plugins

import com.jarvis.android.actions.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every plugin shipped in the app must pass the same checks as one the user imports. */
class PluginCatalogAssetsTest {
    private val files = File("src/main/assets/plugins").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }

    @Test
    fun `the catalogue is not empty`() {
        assertTrue(files.size >= 10)
    }

    @Test
    fun `every bundled plugin is valid and named after its file`() {
        val builtIn = ToolRegistry.builtInNames()
        for (file in files) {
            val parsed = parsePlugin(file.readText(), builtIn)
            assertTrue("${file.name} : $parsed", parsed is PluginParse.Ok)
            assertEquals(file.nameWithoutExtension, (parsed as PluginParse.Ok).spec.name)
        }
    }

    @Test
    fun `bundled plugins and the examples folder are identical`() {
        for (file in files) {
            val example = File("../plugins-examples/${file.name}")
            assertTrue("${file.name} manque dans plugins-examples", example.exists())
            assertEquals(file.readText(), example.readText())
        }
    }
}
