package com.jarvis.android.docs

import com.jarvis.android.text.normalize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A document being written, kept in the app so it can be edited again and exported in any format: its content is light Markdown. */
internal data class DocDraft(val id: String, val title: String, val markdown: String, val updated: Long)

internal fun encodeDraft(draft: DocDraft): String = JsonObject(
    mapOf(
        "id" to JsonPrimitive(draft.id), "title" to JsonPrimitive(draft.title),
        "markdown" to JsonPrimitive(draft.markdown), "updated" to JsonPrimitive(draft.updated),
    )
).toString()

internal fun decodeDraft(text: String): DocDraft? = try {
    val o = Json.parseToJsonElement(text).jsonObject
    DocDraft(
        o["id"]?.jsonPrimitive?.content ?: error("no id"),
        o["title"]?.jsonPrimitive?.content.orEmpty(),
        o["markdown"]?.jsonPrimitive?.content.orEmpty(),
        o["updated"]?.jsonPrimitive?.longOrNull ?: 0L,
    )
} catch (_: Exception) {
    null
}

/** The draft whose title best matches [query] (same title, then a title containing it), the most recent first; null for none. */
internal fun findDraft(drafts: List<DocDraft>, query: String): DocDraft? {
    val q = normalize(query).trim()
    if (q.isEmpty()) return drafts.maxByOrNull { it.updated }
    val recent = drafts.sortedByDescending { it.updated }
    return recent.firstOrNull { normalize(it.title).trim() == q }
        ?: recent.firstOrNull { normalize(it.title).contains(q) }
        ?: recent.firstOrNull { d -> q.split(' ').filter { it.length > 2 }.let { words -> words.isNotEmpty() && words.all { normalize(d.title).contains(it) } } }
}

/** The pictures (by path) the editor copied for [drafts]: those still used by one. */
internal fun usedPictures(drafts: List<DocDraft>): Set<String> =
    drafts.flatMap { d -> parseBlocks(d.markdown).filterIsInstance<Block.Image>().map { it.source } }.toSet()
