package com.jarvis.android.docs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

/** The structure of a document as the assistant (or the document editor) writes it: a light Markdown. */
internal sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class Bullets(val items: List<String>, val numbered: Boolean) : Block
    data class Table(val rows: List<List<String>>) : Block

    /** A picture: [source] is a gallery reference, a file, a content URI or a web address (see [imageSource]); [widthPercent] of the page. */
    data class Image(val source: String, val caption: String = "", val widthPercent: Int = 100) : Block
    data class Quote(val text: String) : Block
    data class Code(val text: String) : Block
    data class Checklist(val items: List<CheckItem>) : Block
    data object Divider : Block
    data object PageBreak : Block
}

internal data class CheckItem(val text: String, val done: Boolean)

/** Removes the Markdown emphasis marks (`**bold**`, `__bold__`, `` `code` ``) that a plain document does not render. */
internal fun stripMarkdown(text: String): String =
    text.replace("**", "").replace("__", "").replace("`", "")

private val BULLET = Regex("""^\s*[-*•]\s+(.*)$""")
private val NUMBERED = Regex("""^\s*\d+[.)]\s+(.*)$""")
private val HEADING = Regex("""^(#{1,3})\s+(.*)$""")
private val CHECK = Regex("""^\s*[-*•]\s+\[([ xX])]\s+(.*)$""")
private val IMAGE = Regex("""^\s*!\[([^\]]*)]\(\s*([^)]+?)\s*\)\s*$""")
private val DIVIDER = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
private val PAGE_BREAK = Regex("""^\s*(\[(saut de page|nouvelle page|page break)]|\\pagebreak|\\newpage)\s*$""", RegexOption.IGNORE_CASE)
private val QUOTE = Regex("""^\s*>\s?(.*)$""")
private val TABLE_SEPARATOR = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

private fun isTableLine(line: String) = line.trim().let { it.startsWith("|") || (it.count { c -> c == '|' } >= 2) }

private fun tableCells(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { stripMarkdown(it.trim()) }

/**
 * Reads headings (`#`, `##`, `###`), bullet, numbered and check lists (`- [ ]`, `- [x]`), `|` tables, images (`![légende](source)`,
 * `![légende|50](source)` for half the width), quotes (`>`), code between ``` fences, separators (`---`), page breaks
 * (`[saut de page]`) and paragraphs (separated by blank lines; a line ending with two spaces keeps its line break).
 */
internal fun parseBlocks(text: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val paragraph = StringBuilder()
    var bullets = mutableListOf<String>()
    var numbered = false
    var table = mutableListOf<List<String>>()
    var checks = mutableListOf<CheckItem>()
    val quote = StringBuilder()
    var code: StringBuilder? = null

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
    fun flushChecks() {
        if (checks.isNotEmpty()) blocks += Block.Checklist(checks)
        checks = mutableListOf()
    }
    fun flushQuote() {
        if (quote.isNotBlank()) blocks += Block.Quote(stripMarkdown(quote.toString().trim()))
        quote.clear()
    }
    fun flushAll() { flushParagraph(); flushBullets(); flushTable(); flushChecks(); flushQuote() }

    for (raw in text.replace("\r\n", "\n").lines()) {
        val open = code
        if (open != null) {
            if (raw.trim().startsWith("```")) {
                blocks += Block.Code(open.toString().trimEnd('\n'))
                code = null
            } else {
                open.append(raw.trimEnd()).append('\n')
            }
            continue
        }
        val line = raw.trimEnd()
        when {
            line.isBlank() -> flushAll()
            line.trim().startsWith("```") -> { flushAll(); code = StringBuilder() }
            HEADING.matches(line) -> {
                flushAll()
                val m = HEADING.find(line)!!
                blocks += Block.Heading(m.groupValues[1].length, stripMarkdown(m.groupValues[2].trim()))
            }
            IMAGE.matches(line) -> {
                flushAll()
                val m = IMAGE.find(line)!!
                val label = m.groupValues[1]
                val width = label.substringAfterLast('|', "").trim().removeSuffix("%").toIntOrNull()
                val caption = if (width != null) label.substringBeforeLast('|') else label
                blocks += Block.Image(m.groupValues[2], stripMarkdown(caption.trim()), (width ?: 100).coerceIn(10, 100))
            }
            PAGE_BREAK.matches(line) -> { flushAll(); blocks += Block.PageBreak }
            DIVIDER.matches(line) -> { flushAll(); blocks += Block.Divider }
            QUOTE.matches(line) -> {
                flushParagraph(); flushBullets(); flushTable(); flushChecks()
                val part = QUOTE.find(line)!!.groupValues[1].trim()
                if (part.isEmpty()) {
                    if (quote.isNotEmpty()) quote.append('\n')
                } else {
                    if (quote.isNotEmpty() && !quote.endsWith("\n")) quote.append(' ')
                    quote.append(part)
                }
            }
            isTableLine(line) -> {
                flushParagraph(); flushBullets(); flushChecks(); flushQuote()
                if (!TABLE_SEPARATOR.matches(line)) table += tableCells(line)
            }
            CHECK.matches(line) -> {
                flushParagraph(); flushBullets(); flushTable(); flushQuote()
                val m = CHECK.find(line)!!
                checks += CheckItem(stripMarkdown(m.groupValues[2].trim()), m.groupValues[1] != " ")
            }
            BULLET.matches(line) -> {
                flushParagraph(); flushTable(); flushChecks(); flushQuote()
                if (bullets.isNotEmpty() && numbered) flushBullets()
                numbered = false
                bullets += stripMarkdown(BULLET.find(line)!!.groupValues[1].trim())
            }
            NUMBERED.matches(line) -> {
                flushParagraph(); flushTable(); flushChecks(); flushQuote()
                if (bullets.isNotEmpty() && !numbered) flushBullets()
                numbered = true
                bullets += stripMarkdown(NUMBERED.find(line)!!.groupValues[1].trim())
            }
            else -> {
                flushBullets(); flushTable(); flushChecks(); flushQuote()
                if (paragraph.isNotEmpty() && !paragraph.endsWith("\n")) paragraph.append(' ')
                paragraph.append(line.trim())
                if (raw.endsWith("  ")) paragraph.append('\n')
            }
        }
    }
    code?.let { blocks += Block.Code(it.toString().trimEnd('\n')) }
    flushAll()
    return blocks
}

/** [block] without its empty parts (blank list lines, empty rows), or null when nothing is left of it (an editor's untouched new block). */
internal fun cleanBlock(block: Block): Block? = when (block) {
    is Block.Heading -> block.takeIf { it.text.isNotBlank() }
    is Block.Paragraph -> block.takeIf { it.text.isNotBlank() }
    is Block.Bullets -> block.items.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { block.copy(items = it) }
    is Block.Checklist -> block.items.filter { it.text.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { block.copy(items = it) }
    is Block.Table -> block.rows.filter { row -> row.any { it.isNotBlank() } }.takeIf { it.isNotEmpty() }?.let { block.copy(rows = it) }
    is Block.Image -> block.takeIf { it.source.isNotBlank() }
    is Block.Quote -> block.takeIf { it.text.isNotBlank() }
    is Block.Code -> block.takeIf { it.text.isNotBlank() }
    Block.Divider, Block.PageBreak -> block
}

/** The light Markdown that [parseBlocks] reads back into the same blocks (how the editor keeps a document). */
internal fun toMarkdown(blocks: List<Block>): String = blocks.mapNotNull { cleanBlock(it) }.joinToString("\n\n") { block ->
    when (block) {
        is Block.Heading -> "#".repeat(block.level.coerceIn(1, 3)) + " " + block.text.replace('\n', ' ')
        is Block.Paragraph -> block.text.trim().lines().joinToString("  \n") { it.trim() }
        is Block.Bullets -> block.items.withIndex().joinToString("\n") { (i, item) -> (if (block.numbered) "${i + 1}. " else "- ") + item.replace('\n', ' ') }
        is Block.Table -> block.rows.withIndex().joinToString("\n") { (r, row) ->
            val line = "| " + row.joinToString(" | ") { it.replace('|', '/').replace('\n', ' ').ifEmpty { " " } } + " |"
            if (r == 0) line + "\n|" + row.joinToString("|") { " --- " } + "|" else line
        }
        is Block.Image -> "![" + block.caption.replace(']', ')').replace('|', '/').replace('\n', ' ') +
            (if (block.widthPercent < 100) "|${block.widthPercent}" else "") + "](" + block.source + ")"
        is Block.Quote -> block.text.lines().joinToString("\n>\n") { "> $it".trimEnd() }
        is Block.Code -> "```\n" + block.text.replace("```", "'''") + "\n```"
        is Block.Checklist -> block.items.joinToString("\n") { (if (it.done) "- [x] " else "- [ ] ") + it.text.replace('\n', ' ') }
        Block.Divider -> "---"
        Block.PageBreak -> "[saut de page]"
    }
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
