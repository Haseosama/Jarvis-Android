package com.jarvis.android.docs

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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

/** A PDF: the title, then headings, paragraphs, lists, tables, pictures (from [images], by source), quotes and code laid out on A4 pages. */
internal fun buildPdf(title: String, blocks: List<Block>, images: Map<String, DocImage> = emptyMap()): ByteArray {
    val document = PdfDocument()
    val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.BLACK; typeface = Typeface.SANS_SERIF }
    fun heading(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; color = Color.rgb(20, 20, 20); typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    val italic = Paint(body).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC); color = Color.rgb(70, 70, 70) }
    val caption = Paint(italic).apply { textSize = 9.5f; color = Color.rgb(100, 100, 100) }
    val mono = Paint(body).apply { textSize = 9.5f; typeface = Typeface.MONOSPACE; color = Color.rgb(30, 30, 30) }
    val rule = Paint().apply { color = Color.rgb(170, 170, 170); strokeWidth = 0.8f; style = Paint.Style.STROKE }
    val fill = Paint().apply { style = Paint.Style.FILL }
    val contentWidth = PAGE_WIDTH - 2 * MARGIN
    val bottom = PAGE_HEIGHT - 48f

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
    fun ensure(height: Float) { if (page == null || y + height > bottom) newPage() }

    /** Writes [value] wrapped; [decorate] draws behind or beside each line (its top and bottom), on the page that line lands on. */
    fun text(
        value: String, paint: Paint, left: Float = MARGIN, width: Float = contentWidth, after: Float = 6f, center: Boolean = false,
        decorate: ((Float, Float) -> Unit)? = null,
    ) {
        val lineHeight = paint.textSize * 1.35f
        for (line in wrapText(value, width) { paint.measureText(it) }) {
            ensure(lineHeight)
            decorate?.invoke(y, y + lineHeight)
            y += paint.textSize
            val x = if (center) left + (width - paint.measureText(line)) / 2 else left
            canvas!!.drawText(line, x, y, paint)
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
        is Block.Checklist -> block.items.forEach { item ->
            ensure(body.textSize * 1.35f)
            val top = y + 2f
            canvas!!.drawRect(MARGIN + 4f, top, MARGIN + 13f, top + 9f, Paint(rule).apply { color = Color.rgb(90, 90, 90) })
            if (item.done) {
                val tick = Paint(rule).apply { color = Color.rgb(30, 120, 60); strokeWidth = 1.6f }
                canvas!!.drawLine(MARGIN + 5.5f, top + 4.5f, MARGIN + 8f, top + 7.5f, tick)
                canvas!!.drawLine(MARGIN + 8f, top + 7.5f, MARGIN + 12.5f, top + 1f, tick)
            }
            text(item.text, if (item.done) Paint(body).apply { color = Color.GRAY } else body, left = MARGIN + 22f, width = contentWidth - 22f, after = 3f)
        }.also { y += 4f }
        is Block.Table -> if (block.rows.isNotEmpty()) {
            val columns = block.rows.maxOf { it.size }.coerceAtLeast(1)
            val cellWidth = contentWidth / columns
            for ((r, row) in block.rows.withIndex()) {
                val paint = if (r == 0) heading(10.5f) else Paint(body).apply { textSize = 10f }
                val wrapped = (0 until columns).map { c -> wrapText(row.getOrElse(c) { "" }, cellWidth - 8f) { paint.measureText(it) } }
                val rowHeight = wrapped.maxOf { it.size } * paint.textSize * 1.3f + 8f
                ensure(rowHeight)
                for (c in 0 until columns) {
                    val left = MARGIN + c * cellWidth
                    if (r == 0) canvas!!.drawRect(left, y, left + cellWidth, y + rowHeight, fill.apply { color = Color.rgb(235, 239, 245) })
                    canvas!!.drawRect(left, y, left + cellWidth, y + rowHeight, rule)
                    var ty = y + 4f
                    for (line in wrapped[c]) { ty += paint.textSize; canvas!!.drawText(line, left + 4f, ty, paint); ty += paint.textSize * 0.3f }
                }
                y += rowHeight
            }
            y += 10f
        }
        is Block.Image -> {
            val picture = images[block.source]
            val bitmap = picture?.let { BitmapFactory.decodeByteArray(it.jpeg, 0, it.jpeg.size) }
            if (picture == null || bitmap == null) {
                text("[Image introuvable : ${block.caption.ifBlank { block.source }}]", caption, center = true)
            } else {
                val captionHeight = if (block.caption.isBlank()) 0f else caption.textSize * 1.35f + 4f
                val (w, h) = fitBox(picture.width, picture.height, contentWidth * block.widthPercent / 100f, bottom - MARGIN - captionHeight - 8f)
                ensure(h + captionHeight + 4f)
                val left = MARGIN + (contentWidth - w) / 2
                canvas!!.drawBitmap(bitmap, null, RectF(left, y, left + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                bitmap.recycle()
                y += h + 4f
                if (block.caption.isNotBlank()) text(block.caption, caption, center = true, after = 4f)
            }
            y += 6f
        }
        is Block.Quote -> {
            y += 2f
            text(block.text, italic, left = MARGIN + 16f, width = contentWidth - 16f) { top, bot ->
                canvas!!.drawRect(MARGIN + 3f, top, MARGIN + 6f, bot, fill.apply { color = Color.rgb(46, 117, 182) })
            }
        }
        is Block.Code -> {
            // the indentation is kept: leading spaces become non-breaking, which the wrapping does not drop
            val kept = block.text.lines().joinToString("\n") { l -> l.replace(Regex("^ +")) { " ".repeat(it.value.length) }.ifEmpty { " " } }
            text(kept, mono, left = MARGIN + 8f, width = contentWidth - 16f, after = 8f) { top, bot ->
                canvas!!.drawRect(MARGIN, top, MARGIN + contentWidth, bot, fill.apply { color = Color.rgb(242, 242, 242) })
            }
        }
        Block.Divider -> {
            ensure(16f)
            y += 8f
            canvas!!.drawLine(MARGIN, y, MARGIN + contentWidth, y, rule)
            y += 8f
        }
        Block.PageBreak -> if (y > MARGIN) newPage()
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
