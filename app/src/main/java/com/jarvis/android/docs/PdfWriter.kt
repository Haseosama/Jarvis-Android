package com.jarvis.android.docs

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream

/** Cuts [text] into lines no wider than [maxWidth], at spaces (a word wider than the line is cut anywhere). */
internal fun wrapText(text: String, maxWidth: Float, measure: (String) -> Float): List<String> {
    val lines = mutableListOf<String>()
    for (paragraph in text.split('\n')) {
        var line = ""
        for (word in paragraph.split(' ').filter { it.isNotEmpty() }) {
            var piece = word
            val candidate = if (line.isEmpty()) piece else "$line $piece"
            if (measure(candidate) <= maxWidth) {
                line = candidate
                continue
            }
            if (line.isNotEmpty()) { lines += line; line = "" }
            while (measure(piece) > maxWidth && piece.length > 1) {
                var cut = piece.length - 1
                while (cut > 1 && measure(piece.substring(0, cut)) > maxWidth) cut--
                lines += piece.substring(0, cut)
                piece = piece.substring(cut)
            }
            line = piece
        }
        lines += line
    }
    return lines
}

private const val PAGE_WIDTH = 595
private const val PAGE_HEIGHT = 842
private const val MARGIN = 48f

/** A PDF: the title, then headings, paragraphs, lists and tables laid out on A4 pages. */
internal fun buildPdf(title: String, blocks: List<Block>): ByteArray {
    val document = PdfDocument()
    val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.BLACK; typeface = Typeface.SANS_SERIF }
    fun heading(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; color = Color.rgb(20, 20, 20); typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    val rule = Paint().apply { color = Color.rgb(170, 170, 170); strokeWidth = 0.8f; style = Paint.Style.STROKE }
    val contentWidth = PAGE_WIDTH - 2 * MARGIN

    var pageNumber = 0
    var page: PdfDocument.Page? = null
    var canvas: Canvas? = null
    var y = MARGIN

    fun newPage() {
        page?.let { finished ->
            canvas?.drawText("${pageNumber}", PAGE_WIDTH / 2f, PAGE_HEIGHT - 24f, Paint(body).apply { textSize = 9f; textAlign = Paint.Align.CENTER; color = Color.GRAY })
            document.finishPage(finished)
        }
        pageNumber++
        page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        canvas = page!!.canvas
        y = MARGIN
    }
    fun ensure(height: Float) { if (page == null || y + height > PAGE_HEIGHT - 48f) newPage() }

    fun text(value: String, paint: Paint, left: Float = MARGIN, width: Float = contentWidth, after: Float = 6f) {
        val lineHeight = paint.textSize * 1.35f
        for (line in wrapText(value, width) { paint.measureText(it) }) {
            ensure(lineHeight)
            y += paint.textSize
            canvas!!.drawText(line, left, y, paint)
            y += lineHeight - paint.textSize
        }
        y += after
    }

    newPage()
    if (title.isNotBlank()) { text(title, heading(22f), after = 12f) }
    for (block in blocks) when (block) {
        is Block.Heading -> { y += 6f; text(block.text, heading(when (block.level) { 1 -> 16f; 2 -> 13.5f; else -> 12f })) }
        is Block.Paragraph -> text(block.text, body)
        is Block.Bullets -> block.items.forEachIndexed { i, item ->
            val marker = if (block.numbered) "${i + 1}." else "•"
            ensure(body.textSize * 1.35f)
            canvas!!.drawText(marker, MARGIN + 4f, y + body.textSize, body)
            text(item, body, left = MARGIN + 22f, width = contentWidth - 22f, after = 3f)
        }.also { y += 4f }
        is Block.Table -> {
            val columns = block.rows.maxOf { it.size }.coerceAtLeast(1)
            val cellWidth = contentWidth / columns
            for ((r, row) in block.rows.withIndex()) {
                val paint = if (r == 0) heading(10.5f) else Paint(body).apply { textSize = 10f }
                val wrapped = (0 until columns).map { c -> wrapText(row.getOrElse(c) { "" }, cellWidth - 8f) { paint.measureText(it) } }
                val rowHeight = wrapped.maxOf { it.size } * paint.textSize * 1.3f + 8f
                ensure(rowHeight)
                for (c in 0 until columns) {
                    val left = MARGIN + c * cellWidth
                    canvas!!.drawRect(left, y, left + cellWidth, y + rowHeight, rule)
                    var ty = y + 4f
                    for (line in wrapped[c]) { ty += paint.textSize; canvas!!.drawText(line, left + 4f, ty, paint); ty += paint.textSize * 0.3f }
                }
                y += rowHeight
            }
            y += 10f
        }
    }
    page?.let { last ->
        canvas?.drawText("$pageNumber", PAGE_WIDTH / 2f, PAGE_HEIGHT - 24f, Paint(body).apply { textSize = 9f; textAlign = Paint.Align.CENTER; color = Color.GRAY })
        document.finishPage(last)
    }
    val out = ByteArrayOutputStream()
    document.writeTo(out)
    document.close()
    return out.toByteArray()
}
