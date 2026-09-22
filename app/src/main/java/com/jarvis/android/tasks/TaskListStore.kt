package com.jarvis.android.tasks

import com.jarvis.android.offline.normalize
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/*
 * Lists that persist between sessions: a shopping list, a to-do list, or any other list the user names — "ajoute du lait à la liste de
 * courses", "qu'est-ce qu'il y a sur ma liste ?", "j'ai acheté le lait", "vide la liste". One phone, one set of lists, shared by the
 * online and the offline mode (see actions/TaskListTool.kt and offline/OfflineIntents.kt) and by the settings screen.
 */

internal const val DEFAULT_LIST_NAME = "liste"
internal const val MAX_ITEM_CHARS = 200
internal const val MAX_ITEMS_PER_LIST = 300
internal const val MAX_LISTS = 30

@Serializable
internal data class TaskItem(val text: String, val done: Boolean = false, val addedAt: Long = 0L)

@Serializable
internal data class TaskListData(val lists: MutableMap<String, MutableList<TaskItem>> = LinkedHashMap())

/** The name as it is stored and matched: the same folding as an item's text (accents and case ignored). Blank becomes the default list. */
internal fun normalizeListName(name: String): String = normalize(name).ifEmpty { DEFAULT_LIST_NAME }

private val LEADING_ARTICLE = Regex("^(les|la|le|des|du|de la|de l|un|une|l) ")

/** Without a leading French article: "du lait" and "le lait" both fold to "lait", so saying either finds the same item. */
private fun articleFold(normalized: String): String = LEADING_ARTICLE.replaceFirst(normalized, "")

/**
 * The item in [items] that best matches [query] (an accent-, case- and article-insensitive substring match): the shortest one
 * that contains it, so "pommes" is preferred over "pommes de terre" for the query "pommes" — the more exact match, not just
 * whichever happens to be the most recently added.
 */
internal fun findItem(items: List<TaskItem>, query: String): TaskItem? {
    val q = articleFold(normalize(query))
    if (q.isEmpty()) return null
    return items.filter { articleFold(normalize(it.text)).contains(q) }.minByOrNull { it.text.length }
}

internal class TaskListStore(private val file: File) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private fun load(): TaskListData = try {
        if (file.exists()) json.decodeFromString<TaskListData>(file.readText()) else TaskListData()
    } catch (_: Exception) {
        TaskListData()
    }

    private fun save(data: TaskListData) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(data))
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Exception) {
        }
    }

    /** The lists that have at least one item, each list's items in the order they were added. */
    suspend fun all(): Map<String, List<TaskItem>> = mutex.withLock { load().lists.filterValues { it.isNotEmpty() } }

    suspend fun items(listName: String): List<TaskItem> = mutex.withLock { load().lists[normalizeListName(listName)].orEmpty() }

    /** Adds [text] to [listName]. False when the list is full, or there are already [MAX_LISTS] other lists. */
    suspend fun add(listName: String, text: String): Boolean = mutex.withLock {
        val clean = text.trim().take(MAX_ITEM_CHARS)
        if (clean.isEmpty()) return@withLock false
        val data = load()
        val key = normalizeListName(listName)
        val existing = data.lists[key]
        if (existing == null && data.lists.size >= MAX_LISTS) return@withLock false
        val list = existing ?: mutableListOf<TaskItem>().also { data.lists[key] = it }
        if (list.size >= MAX_ITEMS_PER_LIST) return@withLock false
        list += TaskItem(clean, addedAt = System.currentTimeMillis())
        save(data)
        true
    }

    /** Marks the item that best matches [query] as done (or not done). True when one was found. */
    suspend fun setDone(listName: String, query: String, done: Boolean): Boolean = mutex.withLock {
        val data = load()
        val list = data.lists[normalizeListName(listName)] ?: return@withLock false
        val target = findItem(list.filter { it.done != done }, query) ?: findItem(list, query) ?: return@withLock false
        val i = list.indexOf(target)
        list[i] = target.copy(done = done)
        save(data)
        true
    }

    /** Removes the item that best matches [query]. True when one was found. */
    suspend fun remove(listName: String, query: String): Boolean = mutex.withLock {
        val data = load()
        val list = data.lists[normalizeListName(listName)] ?: return@withLock false
        val target = findItem(list, query) ?: return@withLock false
        list.remove(target)
        if (list.isEmpty()) data.lists.remove(normalizeListName(listName))
        save(data)
        true
    }

    /** Empties [listName] entirely. */
    suspend fun clear(listName: String) = mutex.withLock {
        val data = load()
        data.lists.remove(normalizeListName(listName))
        save(data)
    }

    /** Removes only the items already marked done. Returns how many were removed. */
    suspend fun clearDone(listName: String): Int = mutex.withLock {
        val data = load()
        val key = normalizeListName(listName)
        val list = data.lists[key] ?: return@withLock 0
        val before = list.size
        list.removeAll { it.done }
        if (list.isEmpty()) data.lists.remove(key) else data.lists[key] = list
        save(data)
        before - list.size
    }
}
