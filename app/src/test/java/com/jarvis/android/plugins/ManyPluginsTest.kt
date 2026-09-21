package com.jarvis.android.plugins

import com.jarvis.android.actions.MAX_DECLARED_PLUGINS
import com.jarvis.android.actions.ToolRegistry
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManyPluginsTest {
    @get:Rule val tmp = TemporaryFolder()

    @After fun cleanUp() = ToolRegistry.setPlugins(emptyList())

    private fun json(n: Int, name: String = "p%03d".format(n)) =
        """{"name":"$name","description":"Un plugin d'essai numéro $n qui ouvre une page","parameters":[{"name":"ville","description":"La ville","required":true},{"name":"detail","description":"Un détail","required":false}],"type":"open","url":"https://example.com/$n/{ville}"}"""

    private fun tools(count: Int) = (1..count).map { PluginTool((parsePlugin(json(it), ToolRegistry.builtInNames()) as PluginParse.Ok).spec) }

    private fun declaredNames() = ToolRegistry.declarations().map { it["name"]!!.jsonPrimitive.content }

    @Test fun `a few plugins are all declared and plugin_run stays out of the way`() {
        ToolRegistry.setPlugins(tools(10))
        val names = declaredNames()
        assertEquals(ToolRegistry.ALL.size + 10, names.size)
        assertFalse("plugin_run" in names)
        assertNull(ToolRegistry.get("plugin_run"))
    }

    @Test fun `past the limit the rest goes through one tool that lists them`() {
        ToolRegistry.setPlugins(tools(40))
        val names = declaredNames()
        assertEquals(ToolRegistry.ALL.size + MAX_DECLARED_PLUGINS + 1, names.size)
        assertEquals(names.size, names.toSet().size)
        assertTrue("p001" in names && "p%03d".format(MAX_DECLARED_PLUGINS) in names)
        assertFalse("p%03d".format(MAX_DECLARED_PLUGINS + 1) in names)
        assertTrue("plugin_run" in names)

        val run = ToolRegistry.get("plugin_run")!!
        val text = run.description
        assertTrue(text.contains("p%03d(ville*, detail)".format(MAX_DECLARED_PLUGINS + 1)))
        assertTrue(text.contains("p040(ville*, detail)"))
        assertFalse(text.contains("p001("))          // the declared ones are not repeated
        // any plugin can be found by name, declared or not
        assertNotNull(ToolRegistry.get("p001"))
        assertNotNull(ToolRegistry.get("p040"))
        assertEquals(15, ToolRegistry.extraPlugins().size)
    }

    @Test fun `the declaration of plugin_run is well formed`() {
        ToolRegistry.setPlugins(tools(30))
        val d = ToolRegistry.declarations().first { it["name"]!!.jsonPrimitive.content == "plugin_run" }
        val params = d["parameters"]!!.toString()
        assertTrue(params.contains("\"OBJECT\"") && params.contains("\"plugin\"") && params.contains("\"arguments\""))
    }

    @Test fun `no plugin can take the name plugin_run`() {
        assertTrue("plugin_run" in ToolRegistry.builtInNames())
        val parsed = parsePlugin(json(1, name = "plugin_run"), ToolRegistry.builtInNames())
        assertTrue(parsed is PluginParse.Error)
    }

    @Test fun `arguments come as a json object of plain values`() {
        assertEquals("Paris", parsePluginArguments("""{"ville":"Paris"}""")!!["ville"]!!.jsonPrimitive.content)
        assertEquals("3", parsePluginArguments("""{"n":3}""")!!["n"]!!.jsonPrimitive.content)
        assertTrue(parsePluginArguments("")!!.isEmpty())
        assertTrue(parsePluginArguments("  ")!!.isEmpty())
        assertNull(parsePluginArguments("Paris"))
        assertNull(parsePluginArguments("[1,2]"))
        assertTrue(parsePluginArguments("""{"a":{"b":1},"c":"x"}""")!!.keys == setOf("c"))   // nested values are dropped
    }

    @Test fun `more than twenty plugins can be installed, up to the sanity limit`() {
        val store = PluginStore(tmp.newFolder("plugins"))
        for (n in 1..MAX_PLUGINS) assertNull("plugin $n", store.install(json(n)))
        assertEquals(MAX_PLUGINS, store.reload().size)
        assertEquals(MAX_PLUGINS, ToolRegistry.pluginTools().size)
        assertNotNull(store.install(json(MAX_PLUGINS + 1)))                 // refused, with a message
        assertNull(store.install(json(5)))                                  // replacing an installed one is fine
        store.remove("p001")
        assertNull(store.install(json(MAX_PLUGINS + 1)))
    }

    @Test fun `the limit is well above the old twenty`() {
        assertTrue(MAX_PLUGINS >= 60)
        assertTrue(MAX_DECLARED_PLUGINS in 10..40)
    }
}
