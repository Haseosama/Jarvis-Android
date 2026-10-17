package com.jarvis.android.text

/*
 * Reasoning models (qwen3, deepseek-r1 and their distills) think inside <think>…</think> before they answer, and several servers
 * (Ollama, LM Studio, llama.cpp) pass that through as ordinary text. Read aloud, it is half a minute of "the user greets me, I should
 * answer warmly…" before the greeting (after Mark LVI's providers/llm_openai.py). A tag is often split across two chunks ("<thi" then
 * "nk>"), so the filter works on a stream, holding back only what could still be the start of a tag.
 */

private val OPEN_TAGS = listOf("<think>", "<thinking>", "<reasoning>")
private val CLOSE_TAGS = listOf("</think>", "</thinking>", "</reasoning>")

/** Feeds text as it arrives, gives back what may be shown or said: everything outside the reasoning tags. */
internal class ThinkFilter {
    private var inside = false
    private val pending = StringBuilder()

    /** The visible part of [chunk] (some of it may be held until the next chunk shows it is not a tag). */
    fun feed(chunk: String): String {
        pending.append(chunk)
        val out = StringBuilder()
        while (true) {
            val text = pending.toString()
            val tags = if (inside) CLOSE_TAGS else OPEN_TAGS
            val hit = tags.mapNotNull { tag -> text.indexOf(tag, ignoreCase = true).takeIf { it >= 0 }?.let { it to tag } }.minByOrNull { it.first }
            if (hit != null) {
                if (!inside) out.append(text, 0, hit.first)
                pending.delete(0, hit.first + hit.second.length)
                inside = !inside
                continue
            }
            // keep back the longest tail that could still become a tag
            val keep = tags.maxOf { tag -> (minOf(tag.length - 1, text.length) downTo 1).firstOrNull { n -> tag.startsWith(text.takeLast(n), ignoreCase = true) } ?: 0 }
            if (!inside) out.append(text, 0, text.length - keep)
            pending.delete(0, text.length - keep)
            return out.toString()
        }
    }

    /** What is left at the end: a held tail that never became a tag (nothing while still inside the reasoning). */
    fun finish(): String {
        val rest = if (inside) "" else pending.toString()
        pending.clear()
        return rest
    }
}

/**
 * [text] without its reasoning. Also drops what comes before a lone closing tag: some servers strip the opening one, so the answer
 * starts with the reasoning and a bare "</think>".
 */
internal fun stripThink(text: String): String {
    val lone = CLOSE_TAGS.mapNotNull { tag -> text.indexOf(tag, ignoreCase = true).takeIf { it >= 0 }?.let { it to tag } }.minByOrNull { it.first }
    val opened = OPEN_TAGS.mapNotNull { tag -> text.indexOf(tag, ignoreCase = true).takeIf { it >= 0 } }.minOrNull()
    val body = if (lone != null && (opened == null || opened > lone.first)) text.substring(lone.first + lone.second.length) else text
    val filter = ThinkFilter()
    return (filter.feed(body) + filter.finish()).trim()
}
