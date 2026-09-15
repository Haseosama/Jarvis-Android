package com.jarvis.android.memory

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class MemEntry(val value: String, val updated: String)

@Serializable
data class SessionEntry(val date: String, val summary: String, val language: String = "")

@Serializable
data class MemoryStore(
    val identity: MutableMap<String, MemEntry> = mutableMapOf(),
    val preferences: MutableMap<String, MemEntry> = mutableMapOf(),
    val projects: MutableMap<String, MemEntry> = mutableMapOf(),
    val relationships: MutableMap<String, MemEntry> = mutableMapOf(),
    val wishes: MutableMap<String, MemEntry> = mutableMapOf(),
    val notes: MutableMap<String, MemEntry> = mutableMapOf(),
    val sessions: MutableList<SessionEntry> = mutableListOf(),
) {
    fun categories(): Map<String, MutableMap<String, MemEntry>> = linkedMapOf(
        "identity" to identity, "preferences" to preferences, "projects" to projects,
        "relationships" to relationships, "wishes" to wishes, "notes" to notes,
    )
}

/**
 * Persistent fact store — Android port of `memory/memory_manager.py`.
 *
 * Same design as the desktop version, ported line-for-line in spirit:
 *   - MEMORY_MAX_CHARS is a runaway guard, not a feature limit — nothing normal
 *     reaches it, and a trim is reported to the activity log, never silent.
 *   - Only a budgeted "core" (identity + most-recently-updated facts, capped
 *     per category) rides in every session's system prompt; the rest is
 *     fetched on demand via [searchMemory], with an index of leftover keys so
 *     the model knows what it can still look up.
 */
class MemoryManager(context: Context) {
    private val file = File(context.filesDir, "long_term_memory.json")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val identityFields = listOf("name", "age", "birthday", "city", "job", "language", "school", "nationality")
    private val categoryLabels = linkedMapOf(
        "preferences" to "Preferences",
        "projects" to "Active projects / goals",
        "relationships" to "People in their life",
        "wishes" to "Wishes / plans",
        "notes" to "Notes",
    )
    private val validCategories = setOf("identity", "preferences", "projects", "relationships", "wishes", "notes")

    private val memoryMaxChars = 200_000
    private val promptCoreChars = 900
    private val promptIndexChars = 420
    private val promptMaxPerCategory = 6
    private val maxValueLength = 380

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    suspend fun load(): MemoryStore = mutex.withLock { loadLocked() }

    private fun loadLocked(): MemoryStore {
        if (!file.exists()) return MemoryStore()
        return try {
            json.decodeFromString(MemoryStore.serializer(), file.readText())
        } catch (e: Exception) {
            MemoryStore()
        }
    }

    private fun saveLocked(store: MemoryStore, onTrim: ((String) -> Unit)?) {
        var text = json.encodeToString(store)
        if (text.length > memoryMaxChars) {
            val entries = mutableListOf<Triple<String, String, MemEntry>>()
            store.categories().forEach { (cat, items) -> items.forEach { (k, e) -> entries.add(Triple(cat, k, e)) } }
            entries.sortBy { it.third.updated }
            val dropped = mutableListOf<String>()
            for ((cat, key, _) in entries) {
                text = json.encodeToString(store)
                if (text.length <= memoryMaxChars) break
                store.categories()[cat]?.remove(key)
                dropped += "$cat/$key"
            }
            text = json.encodeToString(store)
            if (dropped.isNotEmpty()) {
                onTrim?.invoke("Memory full — forgot ${dropped.size} oldest entries (${dropped.take(3).joinToString(", ")}${if (dropped.size > 3) "…" else ""})")
            }
        }
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private fun truncate(v: String) = if (v.length > maxValueLength) v.take(maxValueLength).trimEnd() + "…" else v

    /** category -> key -> newValue. Writes are additive/overwriting, never destructive of other keys. */
    suspend fun update(update: Map<String, Map<String, String>>, onTrim: ((String) -> Unit)? = null): MemoryStore =
        mutex.withLock {
            val store = loadLocked()
            var changed = false
            for ((cat, kv) in update) {
                if (cat !in validCategories) continue
                val target = store.categories()[cat] ?: continue
                for ((key, value) in kv) {
                    if (value.isBlank()) continue
                    val newVal = truncate(value)
                    val existing = target[key]
                    if (existing?.value != newVal) {
                        target[key] = MemEntry(newVal, today())
                        changed = true
                    }
                }
            }
            if (changed) saveLocked(store, onTrim)
            store
        }

    suspend fun remember(key: String, value: String, category: String = "notes"): String {
        val cat = if (category in validCategories) category else "notes"
        update(mapOf(cat to mapOf(key to value)))
        return "Remembered: $cat/$key = $value"
    }

    suspend fun forget(key: String, category: String = "notes"): String = mutex.withLock {
        val store = loadLocked()
        val target = store.categories()[category] ?: return@withLock "Not found: $category/$key"
        return@withLock if (target.remove(key) != null) {
            saveLocked(store, null)
            "Forgotten: $category/$key"
        } else "Not found: $category/$key"
    }

    private fun pretty(key: String) = key.replace('_', ' ').trim()

    suspend fun formatForPrompt(): String {
        val store = load()
        val coreLines = mutableListOf<String>()

        for (f in identityFields) {
            val v = store.identity[f]?.value ?: continue
            if (v.isBlank()) continue
            coreLines += if (f == "language")
                "Has spoken to you in: $v (an observation about the past — always answer in the language of their CURRENT message)"
            else "${f.replaceFirstChar { it.uppercase() }}: $v"
        }
        for ((key, entry) in store.identity) {
            if (key in identityFields) continue
            if (entry.value.isBlank()) continue
            coreLines += "${pretty(key).replaceFirstChar { it.uppercase() }}: ${entry.value}"
        }

        data class Row(val updated: String, val cat: String, val key: String, val value: String)
        val rest = mutableListOf<Row>()
        for (cat in categoryLabels.keys) {
            for ((key, entry) in store.categories()[cat] ?: emptyMap()) {
                if (entry.value.isBlank()) continue
                rest += Row(entry.updated.ifBlank { "0000-00-00" }, cat, key, entry.value)
            }
        }
        rest.sortByDescending { it.updated }

        var used = coreLines.sumOf { it.length + 1 }
        val shown = linkedMapOf<String, MutableList<String>>()
        val overflow = linkedMapOf<String, MutableList<String>>()
        val perCatUsed = mutableMapOf<String, Int>()

        for (row in rest) {
            val line = "  - ${pretty(row.key).replaceFirstChar { it.uppercase() }}: ${row.value}"
            if ((perCatUsed[row.cat] ?: 0) < promptMaxPerCategory && used + line.length + 1 <= promptCoreChars) {
                shown.getOrPut(row.cat) { mutableListOf() } += line
                perCatUsed[row.cat] = (perCatUsed[row.cat] ?: 0) + 1
                used += line.length + 1
            } else {
                overflow.getOrPut(row.cat) { mutableListOf() } += pretty(row.key)
            }
        }

        val indexed = mutableListOf<String>()
        if (overflow.isNotEmpty()) {
            val cats = categoryLabels.keys.filter { overflow[it]?.isNotEmpty() == true }.toMutableList()
            val cursor = cats.associateWith { 0 }.toMutableMap()
            while (cats.isNotEmpty()) {
                val iter = cats.iterator()
                while (iter.hasNext()) {
                    val cat = iter.next()
                    val i = cursor[cat] ?: 0
                    val list = overflow[cat] ?: emptyList()
                    if (i >= list.size) {
                        iter.remove()
                        continue
                    }
                    indexed += list[i]
                    cursor[cat] = i + 1
                }
            }
        }

        for ((cat, label) in categoryLabels) {
            val lines = shown[cat] ?: continue
            if (lines.isEmpty()) continue
            coreLines += ""
            coreLines += "$label:"
            coreLines += lines
        }

        if (coreLines.isEmpty() && indexed.isEmpty()) return ""

        val out = mutableListOf("[WHAT YOU KNOW ABOUT THIS PERSON — use naturally, never recite like a list]")
        out += coreLines

        if (indexed.isNotEmpty()) {
            var budget = promptIndexChars
            val names = mutableListOf<String>()
            for (n in indexed) {
                if (budget - n.length - 2 < 0) break
                names += n
                budget -= n.length + 2
            }
            if (names.isNotEmpty()) {
                out += ""
                out += "[ALSO REMEMBERED — values not shown here. Call recall_memory with a keyword to read any of these before saying you do not know]"
                out += names.joinToString(", ") + if (indexed.size > names.size) " (+${indexed.size - names.size} more)" else ""
            }
        }
        return out.joinToString("\n") + "\n"
    }

    suspend fun search(query: String, limit: Int = 8): String {
        val store = load()
        val words = query.lowercase().split(Regex("[^\\w]+")).filter { it.length > 1 }

        data class Row(val score: Int, val cat: String, val key: String, val value: String)
        val rows = mutableListOf<Row>()
        for ((cat, items) in store.categories()) {
            for ((key, entry) in items) {
                if (entry.value.isBlank()) continue
                val s = if (words.isNotEmpty()) score(words, cat, key, entry.value) else 1
                if (s > 0) rows += Row(s, cat, key, entry.value)
            }
        }
        if (rows.isEmpty()) {
            return if (query.isNotBlank()) "Nothing stored about '$query'." else "I have not stored anything about this person yet."
        }
        rows.sortWith(compareByDescending<Row> { it.score }.thenBy { it.key })
        val lines = rows.take(limit.coerceAtLeast(1)).map { "${it.cat}/${pretty(it.key)}: ${it.value}" }
        val head = if (query.isNotBlank()) "Stored facts matching '$query':" else "Everything currently stored:"
        val more = if (rows.size > lines.size) "\n(+${rows.size - lines.size} more — search with a narrower keyword)" else ""
        return "$head\n${lines.joinToString("\n")}$more"
    }

    private fun score(words: List<String>, cat: String, key: String, value: String): Int {
        val hayKey = pretty(key).lowercase()
        val hayVal = value.lowercase()
        var s = 0
        for (w in words) {
            if (w.isEmpty()) continue
            if (w == hayKey) s += 10 else if (hayKey.contains(w)) s += 6
            if (hayVal.contains(w)) s += 3
            if (cat.contains(w)) s += 1
        }
        return s
    }

    data class UiRow(val category: String, val key: String, val value: String, val updated: String)

    suspend fun allEntriesForUi(): List<UiRow> {
        val store = load()
        val rows = mutableListOf<UiRow>()
        for ((cat, items) in store.categories()) {
            for ((key, entry) in items) {
                if (entry.value.isBlank()) continue
                rows += UiRow(cat, key, entry.value, entry.updated)
            }
        }
        return rows.sortedByDescending { it.updated.ifBlank { "0000-00-00" } }
    }

    suspend fun saveSessionSummary(summary: String, language: String = "") = mutex.withLock {
        if (summary.isBlank()) return@withLock
        val store = loadLocked()
        store.sessions += SessionEntry(today(), summary.take(280), language)
        while (store.sessions.size > 3) store.sessions.removeAt(0)
        saveLocked(store, null)
    }

    suspend fun popLastSession(): SessionEntry? = mutex.withLock {
        val store = loadLocked()
        if (store.sessions.isEmpty()) return@withLock null
        val entry = store.sessions.removeAt(store.sessions.size - 1)
        saveLocked(store, null)
        entry
    }
}
