package com.jarvis.android.docs

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun xmlEscape(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\n' || it == '\t' || it == '\r' || it.code >= 0x20 }

private fun zip(entries: List<Pair<String, String>>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for ((name, content) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
private const val W_NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""

// ── Word ──────────────────────────────────────────────────────────────────────────────────────────────────────────────

private fun wPara(text: String, style: String? = null, indent: Int = 0, bold: Boolean = false): String {
    val props = buildString {
        if (style != null) append("<w:pStyle w:val=\"$style\"/>")
        if (indent > 0) append("<w:ind w:left=\"$indent\" w:hanging=\"280\"/>")
    }
    val run = if (bold) "<w:rPr><w:b/></w:rPr>" else ""
    val body = text.split('\n').joinToString("<w:br/>") { "<w:t xml:space=\"preserve\">${xmlEscape(it)}</w:t>" }
    return "<w:p>" + (if (props.isNotEmpty()) "<w:pPr>$props</w:pPr>" else "") + "<w:r>$run$body</w:r></w:p>"
}

private fun wTable(rows: List<List<String>>): String {
    val columns = rows.maxOf { it.size }.coerceAtLeast(1)
    val width = 9000 / columns
    val border = "w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"999999\""
    return buildString {
        append("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders>")
        for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) append("<w:$side $border/>")
        append("</w:tblBorders></w:tblPr><w:tblGrid>")
        repeat(columns) { append("<w:gridCol w:w=\"$width\"/>") }
        append("</w:tblGrid>")
        for ((r, row) in rows.withIndex()) {
            append("<w:tr>")
            for (c in 0 until columns) {
                append("<w:tc><w:tcPr><w:tcW w:w=\"$width\" w:type=\"dxa\"/></w:tcPr>")
                append(wPara(row.getOrElse(c) { "" }, bold = r == 0))
                append("</w:tc>")
            }
            append("</w:tr>")
        }
        append("</w:tbl>")
    }
}

/** A Word (.docx) file: the title, then headings, paragraphs, lists (with "•" or numbers) and tables. */
internal fun buildDocx(title: String, blocks: List<Block>): ByteArray {
    val body = buildString {
        if (title.isNotBlank()) append(wPara(title, "Title"))
        for (block in blocks) when (block) {
            is Block.Heading -> append(wPara(block.text, "Heading${block.level.coerceIn(1, 3)}"))
            is Block.Paragraph -> append(wPara(block.text))
            is Block.Bullets -> block.items.forEachIndexed { i, item ->
                append(wPara((if (block.numbered) "${i + 1}. " else "•  ") + item, indent = 420))
            }
            is Block.Table -> if (block.rows.isNotEmpty()) { append(wTable(block.rows)); append(wPara("")) }
        }
    }
    val document = "$XML<w:document $W_NS><w:body>$body<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
        "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/></w:sectPr></w:body></w:document>"
    fun style(id: String, name: String, size: Int, bold: Boolean, after: Int) =
        "<w:style w:type=\"paragraph\" w:styleId=\"$id\"><w:name w:val=\"$name\"/><w:basedOn w:val=\"Normal\"/>" +
            "<w:pPr><w:spacing w:before=\"240\" w:after=\"$after\"/></w:pPr><w:rPr>" + (if (bold) "<w:b/>" else "") + "<w:sz w:val=\"$size\"/></w:rPr></w:style>"
    val styles = "$XML<w:styles $W_NS><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\"/>" +
        "<w:sz w:val=\"22\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"120\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>" +
        style("Title", "Title", 44, true, 240) + style("Heading1", "heading 1", 32, true, 120) +
        style("Heading2", "heading 2", 28, true, 100) + style("Heading3", "heading 3", 24, true, 80) + "</w:styles>"
    return zip(
        listOf(
            "[Content_Types].xml" to "$XML<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>",
            "_rels/.rels" to "$XML<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
            "word/_rels/document.xml.rels" to "$XML<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>",
            "word/document.xml" to document,
            "word/styles.xml" to styles,
        )
    )
}

// ── Excel ─────────────────────────────────────────────────────────────────────────────────────────────────────────────

/** Column letters: 0 -> A, 25 -> Z, 26 -> AA. */
internal fun columnLetters(index: Int): String {
    var n = index
    val out = StringBuilder()
    do {
        out.insert(0, ('A' + n % 26))
        n = n / 26 - 1
    } while (n >= 0)
    return out.toString()
}

private val NUMBER = Regex("""^-?(0|[1-9]\d*)([.,]\d+)?$""")

/** A cell: a formula (`=SUM(A1:A3)`), a number, or text. The first row is bold. */
private fun xCell(ref: String, value: String, header: Boolean): String {
    val style = if (header) " s=\"1\"" else ""
    val text = value.trim()
    return when {
        text.startsWith("=") && text.length > 1 -> "<c r=\"$ref\"$style><f>${xmlEscape(text.substring(1))}</f></c>"
        NUMBER.matches(text) && !header -> "<c r=\"$ref\"><v>${text.replace(',', '.')}</v></c>"
        text.isEmpty() -> "<c r=\"$ref\"$style/>"
        else -> "<c r=\"$ref\" t=\"inlineStr\"$style><is><t xml:space=\"preserve\">${xmlEscape(value)}</t></is></c>"
    }
}

/** An Excel (.xlsx) workbook with one sheet; numbers stay numbers, `=…` cells are formulas, the first row is bold. */
internal fun buildXlsx(sheetName: String, rows: List<List<String>>): ByteArray {
    val sheet = buildString {
        append("$XML<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
        for ((r, row) in rows.withIndex()) {
            append("<row r=\"${r + 1}\">")
            for ((c, value) in row.withIndex()) append(xCell("${columnLetters(c)}${r + 1}", value, header = r == 0))
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }
    val name = xmlEscape(sheetName.ifBlank { "Feuille1" }.take(31))
    return zip(
        listOf(
            "[Content_Types].xml" to "$XML<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>",
            "_rels/.rels" to "$XML<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>",
            "xl/workbook.xml" to "$XML<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"$name\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "$XML<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>",
            "xl/styles.xml" to "$XML<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
                "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
                "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
                "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
                "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
                "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs></styleSheet>",
            "xl/worksheets/sheet1.xml" to sheet,
        )
    )
}
