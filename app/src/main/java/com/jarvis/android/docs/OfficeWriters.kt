package com.jarvis.android.docs

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun xmlEscape(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\n' || it == '\t' || it == '\r' || it.code >= 0x20 }

/** A zip of text [entries] (UTF-8) and [binary] ones (pictures). */
internal fun zip(entries: List<Pair<String, String>>, binary: List<Pair<String, ByteArray>> = emptyList()): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for ((name, content) in entries.map { (n, t) -> n to t.toByteArray(Charsets.UTF_8) } + binary) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
private const val W_NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""

// ── Word ──────────────────────────────────────────────────────────────────────────────────────────────────────────────

private fun wPara(
    text: String, style: String? = null, indent: Int = 0, bold: Boolean = false, italic: Boolean = false, center: Boolean = false,
    size: Int? = null, color: String? = null, font: String? = null, extraProps: String = "",
): String {
    val props = buildString {
        if (style != null) append("<w:pStyle w:val=\"$style\"/>")
        append(extraProps)
        if (indent > 0) append("<w:ind w:left=\"$indent\" w:hanging=\"280\"/>")
        if (center) append("<w:jc w:val=\"center\"/>")
    }
    val run = buildString {
        if (font != null) append("<w:rFonts w:ascii=\"$font\" w:hAnsi=\"$font\" w:cs=\"$font\"/>")
        if (bold) append("<w:b/>")
        if (italic) append("<w:i/>")
        if (color != null) append("<w:color w:val=\"$color\"/>")
        if (size != null) append("<w:sz w:val=\"$size\"/>")
    }.let { if (it.isEmpty()) "" else "<w:rPr>$it</w:rPr>" }
    val body = text.split('\n').joinToString("<w:br/>") { "<w:t xml:space=\"preserve\">${xmlEscape(it)}</w:t>" }
    return "<w:p>" + (if (props.isNotEmpty()) "<w:pPr>$props</w:pPr>" else "") + "<w:r>$run$body</w:r></w:p>"
}

private const val WP_NS = "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
private const val R_NS = "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
private const val EMU_PER_PIXEL = 9525

/** Space for a picture on the A4 page between the margins, in EMU (11906 − 2 × 1134 twips wide, a little under the height). */
private const val DOCX_MAX_WIDTH = 9638 * 635
private const val DOCX_MAX_HEIGHT = 12000 * 635

/** A centred picture, embedded as relationship [rel] (the [n]-th picture of the document). */
private fun wPicture(n: Int, rel: String, image: DocImage, widthPercent: Int, description: String): String {
    val (w, h) = fitBox(image.width, image.height, DOCX_MAX_WIDTH * widthPercent / 100f, DOCX_MAX_HEIGHT.toFloat())
    val cx = w.toLong()
    val cy = h.toLong()
    return "<w:p><w:pPr><w:jc w:val=\"center\"/><w:keepNext/></w:pPr><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
        "<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$n\" name=\"Image $n\" descr=\"${xmlEscape(description)}\"/>" +
        "<wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
        "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
        "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:nvPicPr><pic:cNvPr id=\"$n\" name=\"image$n.jpeg\"/><pic:cNvPicPr/></pic:nvPicPr>" +
        "<pic:blipFill><a:blip r:embed=\"$rel\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
        "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>" +
        "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
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

/**
 * A Word (.docx) file: the title, then headings, paragraphs, lists, check lists, tables, pictures (from [images], by source; embedded
 * in the file), quotes, code, separators and page breaks.
 */
internal fun buildDocx(title: String, blocks: List<Block>, images: Map<String, DocImage> = emptyMap()): ByteArray {
    val media = mutableListOf<DocImage>()
    val body = buildString {
        if (title.isNotBlank()) append(wPara(title, "Title"))
        for (block in blocks) when (block) {
            is Block.Heading -> append(wPara(block.text, "Heading${block.level.coerceIn(1, 3)}"))
            is Block.Paragraph -> append(wPara(block.text))
            is Block.Bullets -> block.items.forEachIndexed { i, item ->
                append(wPara((if (block.numbered) "${i + 1}. " else "•  ") + item, indent = 420))
            }
            is Block.Checklist -> block.items.forEach { item ->
                append(wPara((if (item.done) "☑  " else "☐  ") + item.text, indent = 420, color = if (item.done) "808080" else null))
            }
            is Block.Table -> if (block.rows.isNotEmpty()) { append(wTable(block.rows)); append(wPara("")) }
            is Block.Image -> {
                val picture = images[block.source]
                if (picture == null) {
                    append(wPara("[Image introuvable : ${block.caption.ifBlank { block.source }}]", italic = true, center = true, color = "808080"))
                } else {
                    media += picture
                    append(wPicture(media.size, "rIdImg${media.size}", picture, block.widthPercent, block.caption))
                    if (block.caption.isNotBlank()) append(wPara(block.caption, italic = true, center = true, size = 19, color = "666666"))
                }
            }
            is Block.Quote -> append(
                wPara(
                    block.text, italic = true, color = "444444",
                    extraProps = "<w:pBdr><w:left w:val=\"single\" w:sz=\"18\" w:space=\"8\" w:color=\"2E75B6\"/></w:pBdr><w:ind w:left=\"360\"/>",
                )
            )
            is Block.Code -> append(
                wPara(
                    block.text, font = "Consolas", size = 19,
                    extraProps = "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F2F2F2\"/><w:spacing w:before=\"60\" w:after=\"120\"/>",
                )
            )
            Block.Divider -> append(wPara("", extraProps = "<w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"999999\"/></w:pBdr>"))
            Block.PageBreak -> append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
        }
    }
    val document = "$XML<w:document $W_NS $R_NS $WP_NS><w:body>$body<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
        "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/></w:sectPr></w:body></w:document>"
    fun style(id: String, name: String, size: Int, bold: Boolean, after: Int) =
        "<w:style w:type=\"paragraph\" w:styleId=\"$id\"><w:name w:val=\"$name\"/><w:basedOn w:val=\"Normal\"/>" +
            "<w:pPr><w:spacing w:before=\"240\" w:after=\"$after\"/></w:pPr><w:rPr>" + (if (bold) "<w:b/>" else "") + "<w:sz w:val=\"$size\"/></w:rPr></w:style>"
    val styles = "$XML<w:styles $W_NS><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\"/>" +
        "<w:sz w:val=\"22\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"120\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>" +
        style("Title", "Title", 44, true, 240) + style("Heading1", "heading 1", 32, true, 120) +
        style("Heading2", "heading 2", 28, true, 100) + style("Heading3", "heading 3", 24, true, 80) + "</w:styles>"
    val pictureRels = media.indices.joinToString("") {
        "<Relationship Id=\"rIdImg${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image${it + 1}.jpeg\"/>"
    }
    return zip(
        listOf(
            "[Content_Types].xml" to "$XML<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>",
            "_rels/.rels" to "$XML<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
            "word/_rels/document.xml.rels" to "$XML<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>$pictureRels</Relationships>",
            "word/document.xml" to document,
            "word/styles.xml" to styles,
        ),
        media.mapIndexed { i, picture -> "word/media/image${i + 1}.jpeg" to picture.jpeg },
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
