package com.jarvis.android.docs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

/** The structure of a document as the assistant writes it: a light Markdown. */
internal sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class Bullets(val items: List<String>, val numbered: Boolean) : Block
    data class Table(val rows: List<List<String>>) : Block
}

/** Removes the Markdown emphasis marks (`**bold**`, `__bold__`, `` `code` ``) that a plain document does not render. */
internal fun stripMarkdown(text: String): String =
    text.replace("**", "").replace("__", "").replace("`", "")

private val BULLET = Regex("""^\s*[-*•]\s+(.*)$""")
private val NUMBERED = Regex("""^\s*\d+[.)]\s+(.*)$""")
private val HEADING = Regex("""^(#{1,3})\s+(.*)$""")
private val TABLE_SEPARATOR = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

private fun isTableLine(line: String) = line.trim().let { it.startsWith("|") || (it.count { c -> c == '|' } >= 2) }

private fun tableCells(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { stripMarkdown(it.trim()) }

/** Reads headings (`#`, `##`, `###`), bullet and numbered lists, `|` tables and paragraphs (separated by blank lines). */
internal fun parseBlocks(text: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val paragraph = StringBuilder()
    var bullets = mutableListOf<String>()
    var numbered = false
    var table = mutableListOf<List<String>>()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) blocks += Block.Paragraph(stripMarkdown(paragraph.toString().trim()))
        paragraph.clear()
    }
    fun flushBullets() {
        if (bullets.isNotEmpty()) blocks += Block.Bullets(bullets, numbered)
        bullets = mutableListOf()
    }
    fun flushTable() {
        if (table.isNotEmpty()) blocks += Block.Table(table)
        table = mutableListOf()
    }
    fun flushAll() { flushParagraph(); flushBullets(); flushTable() }

    for (raw in text.replace("\r\n", "\n").lines()) {
        val line = raw.trimEnd()
        when {
            line.isBlank() -> flushAll()
            HEADING.matches(line) -> {
                flushAll()
                val m = HEADING.find(line)!!
                blocks += Block.Heading(m.groupValues[1].length, stripMarkdown(m.groupValues[2].trim()))
            }
            isTableLine(line) -> {
                flushParagraph(); flushBullets()
                if (!TABLE_SEPARATOR.matches(line)) table += tableCells(line)
            }
            BULLET.matches(line) -> {
                flushParagraph(); flushTable()
                if (bullets.isNotEmpty() && numbered) flushBullets()
                numbered = false
                bullets += stripMarkdown(BULLET.find(line)!!.groupValues[1].trim())
            }
            NUMBERED.matches(line) -> {
                flushParagraph(); flushTable()
                if (bullets.isNotEmpty() && !numbered) flushBullets()
                numbered = true
                bullets += stripMarkdown(NUMBERED.find(line)!!.groupValues[1].trim())
            }
            else -> {
                flushBullets(); flushTable()
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line.trim())
            }
        }
    }
    flushAll()
    return blocks
}

/**
 * The rows of a table given as JSON (`[["a","b"],[1,2]]`), a Markdown table, or delimited text (tabs, semicolons or commas, with
 * quotes). Empty lines are ignored.
 */
internal fun parseRows(content: String): List<List<String>> {
    val text = content.trim()
    if (text.isEmpty()) return emptyList()
    if (text.startsWith("[")) {
        try {
            val root = Json.parseToJsonElement(text) as? JsonArray
            if (root != null && root.all { it is JsonArray }) {
                return root.map { row -> row.jsonArray.map { cell -> (cell as? JsonPrimitive)?.content ?: cell.toString() } }
            }
        } catch (_: Exception) {
            // Not JSON after all: read it as text.
        }
    }
    val lines = text.replace("\r\n", "\n").lines().filter { it.isNotBlank() }
    if (lines.any { isTableLine(it) }) {
        return lines.filter { !TABLE_SEPARATOR.matches(it) }.map { tableCells(it) }
    }
    val delimiter = when {
        lines.any { '\t' in it } -> '\t'
        lines.any { ';' in it } -> ';'
        else -> ','
    }
    return lines.map { splitDelimited(it, delimiter) }
}

/** Splits one line at [delimiter], honouring double quotes (`"a, b"`, `""` for a quote). */
internal fun splitDelimited(line: String, delimiter: Char): List<String> {
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cell.append('"'); i++ }
            c == '"' -> quoted = !quoted
            c == delimiter && !quoted -> { cells += cell.toString().trim(); cell.clear() }
            else -> cell.append(c)
        }
        i++
    }
    cells += cell.toString().trim()
    return cells
}

/** A CSV file's text: comma-separated, quotes where needed. */
internal fun toCsv(rows: List<List<String>>): String = rows.joinToString("\r\n") { row ->
    row.joinToString(",") { cell ->
        if (cell.any { it == ',' || it == '"' || it == '\n' || it == ';' }) "\"" + cell.replace("\"", "\"\"") + "\"" else cell
    }
}

/** Turns a title into a safe file name (letters, digits, dashes), at most 60 characters. */
internal fun safeFileName(title: String, fallback: String = "document"): String {
    val flat = java.text.Normalizer.normalize(title.trim(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    val slug = flat.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).trim('-')
    return slug.ifEmpty { fallback }
}
