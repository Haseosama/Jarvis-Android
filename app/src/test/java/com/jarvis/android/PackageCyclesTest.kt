package com.jarvis.android

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app's packages do not depend on each other in a circle, so each one can be read, tested and changed with only the ones below it
 * in mind. The root package is left out: the app's container (JarvisContainer, JarvisApp) builds everything, and every feature receives
 * it. A package depends on another when its code names it (an import, or a full name in the code); comments and text do not count.
 */
class PackageCyclesTest {
    @Test
    fun `packages do not depend on each other in a circle`() {
        val sources = File("src/main/java/com/jarvis/android")
        assertTrue("sources not found from ${File(".").absolutePath}", sources.isDirectory)
        val cycle = findCycle(packageDependencies(sources))
        assertNull("packages in a circle: " + cycle?.joinToString(" → ") { it.removePrefix("$BASE.") }, cycle)
    }

    @Test
    fun `a circle is found`() {
        val deps = mapOf("$BASE.a" to setOf("$BASE.b"), "$BASE.b" to setOf("$BASE.c"), "$BASE.c" to setOf("$BASE.a"))
        assertTrue(findCycle(deps)!!.containsAll(deps.keys))
        assertNull(findCycle(mapOf("$BASE.a" to setOf("$BASE.b"), "$BASE.b" to setOf("$BASE.c"))))
    }

    @Test
    fun `comments and text are not code`() {
        val code = codeOnly("val a = 1 // com.jarvis.android.x.A\n/* com.jarvis.android.y.B */ val s = \"com.jarvis.android.z.C \${com.jarvis.android.w.D}\"")
        assertTrue("x.A" !in code && "y.B" !in code && "z.C" !in code && "com.jarvis.android.w.D" in code)
    }

    private companion object {
        const val BASE = "com.jarvis.android"
        val PACKAGE = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
        val NAMED = Regex("""(?<![\w.])com\.jarvis\.android((?:\.\w+)+)""")

        /** For each package under [BASE] (not the root one), the other packages its code names. */
        fun packageDependencies(sources: File): Map<String, Set<String>> {
            val files = sources.walk().filter { it.isFile && it.extension == "kt" }.map { it to it.readText() }.toList()
            val packageOf = files.associate { (file, text) -> file to (PACKAGE.find(text)?.groupValues?.get(1) ?: BASE) }
            val packages = packageOf.values.toSet()
            val deps = mutableMapOf<String, MutableSet<String>>()
            for ((file, text) in files) {
                val own = packageOf.getValue(file)
                if (own == BASE) continue
                for (match in NAMED.findAll(codeOnly(text).replace(PACKAGE, ""))) {
                    val parts = match.groupValues[1].removePrefix(".").split('.')
                    // the longest package the name starts with, short of the whole name (its last part is what is named in it)
                    val target = (parts.size - 1 downTo 1).map { "$BASE." + parts.take(it).joinToString(".") }.firstOrNull { it in packages }
                    if (target != null && target != own) deps.getOrPut(own) { mutableSetOf() } += target
                }
            }
            return deps
        }

        /** One circle of packages in [deps], or null when there is none. */
        fun findCycle(deps: Map<String, Set<String>>): List<String>? {
            val done = mutableSetOf<String>()
            val path = mutableListOf<String>()
            fun visit(p: String): List<String>? {
                val at = path.indexOf(p)
                if (at >= 0) return path.subList(at, path.size) + p
                if (p in done) return null
                path += p
                for (next in deps[p].orEmpty().sorted()) visit(next)?.let { return it }
                path.removeAt(path.size - 1)
                done += p
                return null
            }
            for (p in deps.keys.sorted()) visit(p)?.let { return it }
            return null
        }

        /** [text] without its comments and the text of its strings; the code inside a string's `${…}` stays. */
        fun codeOnly(text: String): String {
            val out = StringBuilder()
            var i = 0
            fun skipString(raw: Boolean) {
                val end = if (raw) "\"\"\"" else "\""
                i += end.length
                while (i < text.length && !text.startsWith(end, i)) {
                    when {
                        !raw && text[i] == '\\' -> i += 2
                        text.startsWith("\${", i) -> {
                            i += 2
                            var depth = 1
                            val start = i
                            while (i < text.length && depth > 0) {
                                if (text[i] == '{') depth++ else if (text[i] == '}') depth--
                                i++
                            }
                            out.append(' ').append(text, start, maxOf(start, i - 1)).append(' ')
                        }
                        else -> i++
                    }
                }
                i += end.length
            }
            while (i < text.length) {
                when {
                    text.startsWith("//", i) -> i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    text.startsWith("/*", i) -> i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    text.startsWith("\"\"\"", i) -> { skipString(raw = true); out.append("\"\"") }
                    text[i] == '"' -> { skipString(raw = false); out.append("\"\"") }
                    text[i] == '\'' && i + 2 < text.length && (text[i + 2] == '\'' || text[i + 1] == '\\') ->
                        i = text.indexOf('\'', if (text[i + 1] == '\\') i + 3 else i + 2).let { if (it < 0) text.length else it + 1 }
                    else -> out.append(text[i++])
                }
            }
            return out.toString()
        }
    }
}
