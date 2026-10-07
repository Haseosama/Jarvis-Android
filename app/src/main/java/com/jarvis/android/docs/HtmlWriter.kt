package com.jarvis.android.docs

import java.util.Base64

private fun esc(text: String) = xmlEscape(text).replace("\n", "<br>")

/** A web page (.html) in one file, pictures included (as data), readable on any phone or computer and printable. */
internal fun buildHtml(title: String, blocks: List<Block>, images: Map<String, DocImage> = emptyMap()): String = buildString {
    append("<!DOCTYPE html>\n<html lang=\"fr\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
    append("<title>").append(esc(title.ifBlank { "Document" })).append("</title><style>")
    append(
        "body{font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;max-width:46rem;margin:2rem auto;padding:0 1rem;line-height:1.55;color:#1d1d1f;background:#fff}" +
            "h1,h2,h3{line-height:1.25}figure{margin:1.2rem auto;text-align:center}figure img{max-width:100%;height:auto;border-radius:4px}" +
            "figcaption{font-style:italic;color:#666;font-size:.9rem;margin-top:.3rem}table{border-collapse:collapse;width:100%;margin:1rem 0}" +
            "th,td{border:1px solid #bbb;padding:.35rem .5rem;text-align:left;vertical-align:top}th{background:#ebeff5}" +
            "blockquote{margin:1rem 0;padding:.2rem 1rem;border-left:4px solid #2e75b6;color:#444;font-style:italic}" +
            "pre{background:#f2f2f2;padding:.8rem;overflow-x:auto;border-radius:4px}ul.check{list-style:none;padding-left:.4rem}" +
            ".done{color:#888;text-decoration:line-through}.missing{color:#888;font-style:italic;text-align:center}" +
            "@media print{.pb{page-break-after:always}}hr.pb{border:0}"
    )
    append("</style></head><body>\n")
    if (title.isNotBlank()) append("<h1>").append(esc(title)).append("</h1>\n")
    for (block in blocks) when (block) {
        is Block.Heading -> {
            // the document title is the page's h1: headings start one level lower
            val level = (block.level + 1).coerceIn(2, 4)
            append("<h$level>").append(esc(block.text)).append("</h$level>\n")
        }
        is Block.Paragraph -> append("<p>").append(esc(block.text)).append("</p>\n")
        is Block.Bullets -> {
            val tag = if (block.numbered) "ol" else "ul"
            append("<$tag>").append(block.items.joinToString("") { "<li>${esc(it)}</li>" }).append("</$tag>\n")
        }
        is Block.Checklist -> append("<ul class=\"check\">").append(
            block.items.joinToString("") { if (it.done) "<li class=\"done\">☑ ${esc(it.text)}</li>" else "<li>☐ ${esc(it.text)}</li>" }
        ).append("</ul>\n")
        is Block.Table -> if (block.rows.isNotEmpty()) {
            val columns = block.rows.maxOf { it.size }
            append("<table>")
            block.rows.forEachIndexed { r, row ->
                val cell = if (r == 0) "th" else "td"
                append("<tr>").append((0 until columns).joinToString("") { "<$cell>${esc(row.getOrElse(it) { "" })}</$cell>" }).append("</tr>")
            }
            append("</table>\n")
        }
        is Block.Image -> {
            val picture = images[block.source]
            if (picture == null) {
                append("<p class=\"missing\">[Image introuvable : ").append(esc(block.caption.ifBlank { block.source })).append("]</p>\n")
            } else {
                append("<figure><img style=\"width:${block.widthPercent}%\" alt=\"").append(esc(block.caption)).append("\" src=\"data:image/jpeg;base64,")
                append(Base64.getEncoder().encodeToString(picture.jpeg)).append("\">")
                if (block.caption.isNotBlank()) append("<figcaption>").append(esc(block.caption)).append("</figcaption>")
                append("</figure>\n")
            }
        }
        is Block.Quote -> append("<blockquote>").append(esc(block.text)).append("</blockquote>\n")
        is Block.Code -> append("<pre><code>").append(xmlEscape(block.text)).append("</code></pre>\n")
        Block.Divider -> append("<hr>\n")
        Block.PageBreak -> append("<hr class=\"pb\">\n")
    }
    append("</body></html>\n")
}

/** The document as plain text: what a reader sees, without the formatting. */
internal fun buildPlainText(title: String, blocks: List<Block>): String = buildString {
    if (title.isNotBlank()) append(title).append("\n\n")
    for (block in blocks) {
        when (block) {
            is Block.Heading -> append(block.text.uppercase())
            is Block.Paragraph -> append(block.text)
            is Block.Bullets -> append(block.items.withIndex().joinToString("\n") { (i, s) -> (if (block.numbered) "${i + 1}. " else "• ") + s })
            is Block.Checklist -> append(block.items.joinToString("\n") { (if (it.done) "[x] " else "[ ] ") + it.text })
            is Block.Table -> append(block.rows.joinToString("\n") { it.joinToString(" | ") })
            is Block.Image -> append("[Image : ${block.caption.ifBlank { block.source }}]")
            is Block.Quote -> append(block.text.lines().joinToString("\n") { "« $it »" })
            is Block.Code -> append(block.text)
            Block.Divider -> append("――――――――")
            Block.PageBreak -> append("\u000C")
        }
        append("\n\n")
    }
}.trimEnd() + "\n"
