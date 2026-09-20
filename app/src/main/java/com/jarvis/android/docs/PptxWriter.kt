package com.jarvis.android.docs

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One line of a slide: a bullet, a plain line, or a bold sub-title. */
internal data class SlideLine(val text: String, val bullet: Boolean = false, val bold: Boolean = false)

/** A slide of the deck; [subtitle] is only used by the title slide. */
internal data class Slide(val title: String, val lines: List<SlideLine> = emptyList(), val isTitle: Boolean = false, val subtitle: String = "")

private const val MAX_LINES_PER_SLIDE = 7
private const val MAX_CHARS_PER_SLIDE = 700

/**
 * Cuts a document into slides. A deck title makes a title slide (its subtitle is the paragraph that comes before the first heading);
 * each `#` or `##` heading starts a slide, `###` is a bold line, bullets stay bullets, a table becomes lines of cells, and a slide that
 * would be too full continues on the next one ("suite").
 */
internal fun slidesFromBlocks(deckTitle: String, blocks: List<Block>): List<Slide> {
    val rest = blocks.filterNot { it is Block.Paragraph && it.text.trim().matches(Regex("""[-*_]{3,}""")) }.toMutableList()
    var title = deckTitle.trim()
    if (title.isEmpty()) {
        val first = rest.firstOrNull()
        if (first is Block.Heading && first.level == 1) {
            title = first.text
            rest.removeAt(0)
        }
    }
    val slides = mutableListOf<Slide>()
    if (title.isNotEmpty()) {
        var subtitle = ""
        val first = rest.firstOrNull()
        if (first is Block.Paragraph) {
            subtitle = first.text.take(200)
            rest.removeAt(0)
        }
        slides += Slide(title, isTitle = true, subtitle = subtitle)
    }

    var current: String? = null
    var lines = mutableListOf<SlideLine>()
    fun flush() {
        if (current == null && lines.isEmpty()) return
        val heading = current ?: title.ifEmpty { "Présentation" }
        var chunk = mutableListOf<SlideLine>()
        var chars = 0
        var part = 0
        fun emit() {
            slides += Slide(if (part == 0) heading else "$heading (suite)", chunk)
            part++
            chunk = mutableListOf()
            chars = 0
        }
        for (line in lines) {
            if (chunk.isNotEmpty() && (chunk.size >= MAX_LINES_PER_SLIDE || chars + line.text.length > MAX_CHARS_PER_SLIDE)) emit()
            chunk += line
            chars += line.text.length
        }
        if (chunk.isNotEmpty() || part == 0) emit()
        lines = mutableListOf()
    }
    for (block in rest) {
        when (block) {
            is Block.Heading ->
                if (block.level <= 2) {
                    flush()
                    current = block.text
                } else {
                    lines += SlideLine(block.text, bold = true)
                }
            is Block.Paragraph -> lines += SlideLine(block.text)
            is Block.Bullets -> block.items.forEachIndexed { i, item -> lines += if (block.numbered) SlideLine("${i + 1}. $item") else SlideLine(item, bullet = true) }
            is Block.Table -> block.rows.forEachIndexed { r, row -> lines += SlideLine(row.joinToString("  ·  "), bold = r == 0) }
        }
    }
    flush()
    return slides
}

private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
private const val NS_A = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
private const val NS_R = "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
private const val NS_P = "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
private const val NS = "$NS_A $NS_R $NS_P"
private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
private const val PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
private const val NAVY = "0B1E3F"
private const val GROUP = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
    "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"

private fun rels(vararg pairs: Triple<String, String, String>): String =
    XML + "<Relationships xmlns=\"$PKG_REL\">" + pairs.joinToString("") { (id, type, target) -> "<Relationship Id=\"$id\" Type=\"$REL/$type\" Target=\"$target\"/>" } + "</Relationships>"

private val THEME = XML + "<a:theme $NS_A name=\"Jarvis\"><a:themeElements>" +
    "<a:clrScheme name=\"Jarvis\"><a:dk1><a:srgbClr val=\"222222\"/></a:dk1><a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1>" +
    "<a:dk2><a:srgbClr val=\"$NAVY\"/></a:dk2><a:lt2><a:srgbClr val=\"E7ECF4\"/></a:lt2>" +
    "<a:accent1><a:srgbClr val=\"2E75B6\"/></a:accent1><a:accent2><a:srgbClr val=\"00A3A3\"/></a:accent2><a:accent3><a:srgbClr val=\"7F7F7F\"/></a:accent3>" +
    "<a:accent4><a:srgbClr val=\"ED7D31\"/></a:accent4><a:accent5><a:srgbClr val=\"70AD47\"/></a:accent5><a:accent6><a:srgbClr val=\"A5A5A5\"/></a:accent6>" +
    "<a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink></a:clrScheme>" +
    "<a:fontScheme name=\"Jarvis\"><a:majorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>" +
    "<a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>" +
    "<a:fmtScheme name=\"Jarvis\"><a:fillStyleLst>" + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>".repeat(3) + "</a:fillStyleLst>" +
    "<a:lnStyleLst>" + "<a:ln w=\"9525\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>".repeat(3) + "</a:lnStyleLst>" +
    "<a:effectStyleLst>" + "<a:effectStyle><a:effectLst/></a:effectStyle>".repeat(3) + "</a:effectStyleLst>" +
    "<a:bgFillStyleLst>" + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>".repeat(3) + "</a:bgFillStyleLst></a:fmtScheme>" +
    "</a:themeElements></a:theme>"

private const val MASTER = XML + "<p:sldMaster $NS><p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg><p:spTree>$GROUP" +
    "<p:sp><p:nvSpPr><p:cNvPr id=\"4\" name=\"Bandeau\"/><p:cNvSpPr/><p:nvPr userDrawn=\"1\"/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"12192000\" cy=\"137160\"/></a:xfrm>" +
    "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:solidFill><a:schemeClr val=\"accent1\"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr></p:sp>" +
    "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Titre\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr>" +
    "<p:spPr><a:xfrm><a:off x=\"609600\" y=\"365760\"/><a:ext cx=\"10972800\" cy=\"1143000\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>" +
    "<p:txBody><a:bodyPr anchor=\"ctr\"><a:normAutofit/></a:bodyPr><a:lstStyle/><a:p><a:r><a:rPr lang=\"fr-FR\"/><a:t>Titre</a:t></a:r></a:p></p:txBody></p:sp>" +
    "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Contenu\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"body\" idx=\"1\"/></p:nvPr></p:nvSpPr>" +
    "<p:spPr><a:xfrm><a:off x=\"609600\" y=\"1600200\"/><a:ext cx=\"10972800\" cy=\"4525963\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>" +
    "<p:txBody><a:bodyPr><a:normAutofit/></a:bodyPr><a:lstStyle/><a:p><a:r><a:rPr lang=\"fr-FR\"/><a:t>Texte</a:t></a:r></a:p></p:txBody></p:sp>" +
    "</p:spTree></p:cSld>" +
    "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
    "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/><p:sldLayoutId id=\"2147483650\" r:id=\"rId2\"/></p:sldLayoutIdLst>" +
    "<p:txStyles><p:titleStyle><a:lvl1pPr algn=\"l\"><a:defRPr sz=\"3600\" b=\"1\"><a:solidFill><a:schemeClr val=\"tx2\"/></a:solidFill><a:latin typeface=\"+mj-lt\"/></a:defRPr></a:lvl1pPr></p:titleStyle>" +
    "<p:bodyStyle><a:lvl1pPr marL=\"342900\" indent=\"-342900\"><a:spcBef><a:spcPts val=\"900\"/></a:spcBef><a:buFont typeface=\"Arial\"/><a:buChar char=\"•\"/><a:defRPr sz=\"2400\"><a:solidFill><a:schemeClr val=\"tx1\"/></a:solidFill><a:latin typeface=\"+mn-lt\"/></a:defRPr></a:lvl1pPr></p:bodyStyle>" +
    "<p:otherStyle><a:lvl1pPr><a:defRPr sz=\"1800\"/></a:lvl1pPr></p:otherStyle></p:txStyles></p:sldMaster>"

private const val LAYOUT_TITLE = XML + "<p:sldLayout $NS type=\"title\" showMasterSp=\"0\" preserve=\"1\"><p:cSld name=\"Titre\"><p:spTree>$GROUP" +
    "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Titre\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"ctrTitle\"/></p:nvPr></p:nvSpPr>" +
    "<p:spPr><a:xfrm><a:off x=\"914400\" y=\"2130425\"/><a:ext cx=\"10363200\" cy=\"1470025\"/></a:xfrm></p:spPr>" +
    "<p:txBody><a:bodyPr anchor=\"b\"><a:normAutofit/></a:bodyPr><a:lstStyle><a:lvl1pPr algn=\"l\"><a:defRPr sz=\"4800\"/></a:lvl1pPr></a:lstStyle><a:p><a:r><a:rPr lang=\"fr-FR\"/><a:t>Titre</a:t></a:r></a:p></p:txBody></p:sp>" +
    "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Sous-titre\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"subTitle\" idx=\"1\"/></p:nvPr></p:nvSpPr>" +
    "<p:spPr><a:xfrm><a:off x=\"914400\" y=\"3800000\"/><a:ext cx=\"10363200\" cy=\"1400000\"/></a:xfrm></p:spPr>" +
    "<p:txBody><a:bodyPr><a:normAutofit/></a:bodyPr><a:lstStyle><a:lvl1pPr marL=\"0\" indent=\"0\"><a:buNone/><a:defRPr sz=\"2400\"/></a:lvl1pPr></a:lstStyle><a:p><a:r><a:rPr lang=\"fr-FR\"/><a:t>Sous-titre</a:t></a:r></a:p></p:txBody></p:sp>" +
    "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"

private const val LAYOUT_CONTENT = XML + "<p:sldLayout $NS type=\"obj\" preserve=\"1\"><p:cSld name=\"Titre et contenu\"><p:spTree>$GROUP" +
    "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Titre\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr><p:spPr/>" +
    "<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang=\"fr-FR\"/><a:t>Titre</a:t></a:r></a:p></p:txBody></p:sp>" +
    "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Contenu\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph idx=\"1\"/></p:nvPr></p:nvSpPr><p:spPr/>" +
    "<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang=\"fr-FR\"/><a:t>Texte</a:t></a:r></a:p></p:txBody></p:sp>" +
    "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"

private fun slideText(text: String, size: Int?, bold: Boolean, white: Boolean): String {
    val props = buildString {
        append("<a:rPr lang=\"fr-FR\"")
        if (size != null) append(" sz=\"$size\"")
        if (bold) append(" b=\"1\"")
        append(if (white) "><a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill></a:rPr>" else "/>")
    }
    return "<a:r>$props<a:t>${xmlEscape(text)}</a:t></a:r>"
}

private fun slideXml(slide: Slide): String {
    val head = "<p:sld $NS><p:cSld>" + (if (slide.isTitle) "<p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"$NAVY\"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>" else "") +
        "<p:spTree>$GROUP"
    val tail = "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
    fun shape(id: Int, name: String, ph: String, paragraphs: String) =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr>$ph</p:nvPr></p:nvSpPr><p:spPr/>" +
            "<p:txBody><a:bodyPr/><a:lstStyle/>$paragraphs</p:txBody></p:sp>"
    if (slide.isTitle) {
        val subtitle = if (slide.subtitle.isBlank()) "" else shape(3, "Sous-titre", "<p:ph type=\"subTitle\" idx=\"1\"/>", "<a:p>${slideText(slide.subtitle, null, false, true)}</a:p>")
        return XML + head + shape(2, "Titre", "<p:ph type=\"ctrTitle\"/>", "<a:p>${slideText(slide.title, null, true, true)}</a:p>") + subtitle + tail
    }
    val total = slide.lines.sumOf { it.text.length }
    val size = when {
        slide.lines.size > 6 || total > 520 -> 1800
        slide.lines.size > 4 || total > 360 -> 2000
        else -> 2400
    }
    val paragraphs = slide.lines.joinToString("") { line ->
        val props = if (line.bullet) "" else "<a:pPr marL=\"0\" indent=\"0\"><a:buNone/></a:pPr>"
        "<a:p>$props${slideText(line.text, size, line.bold, false)}</a:p>"
    }.ifEmpty { "<a:p><a:endParaRPr lang=\"fr-FR\"/></a:p>" }
    return XML + head + shape(2, "Titre", "<p:ph type=\"title\"/>", "<a:p>${slideText(slide.title, null, false, false)}</a:p>") +
        shape(3, "Contenu", "<p:ph idx=\"1\"/>", paragraphs) + tail
}

/** A PowerPoint (.pptx) deck, 16:9, written by hand: a theme, a master, two layouts (title, title and content) and the slides. */
internal fun buildPptx(title: String, blocks: List<Block>): ByteArray {
    val slides = slidesFromBlocks(title, blocks).ifEmpty { listOf(Slide(title.ifBlank { "Présentation" }, isTitle = true)) }
    val entries = mutableListOf<Pair<String, String>>()
    entries += "[Content_Types].xml" to (XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>" +
        "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>" +
        "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>" +
        "<Override PartName=\"/ppt/slideLayouts/slideLayout2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>" +
        "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>" +
        slides.indices.joinToString("") { "<Override PartName=\"/ppt/slides/slide${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>" } +
        "</Types>")
    entries += "_rels/.rels" to rels(Triple("rId1", "officeDocument", "ppt/presentation.xml"))
    entries += "ppt/presentation.xml" to (XML + "<p:presentation $NS><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst>" +
        slides.indices.joinToString("") { "<p:sldId id=\"${256 + it}\" r:id=\"rId${it + 3}\"/>" } +
        "</p:sldIdLst><p:sldSz cx=\"12192000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>")
    entries += "ppt/_rels/presentation.xml.rels" to rels(
        Triple("rId1", "slideMaster", "slideMasters/slideMaster1.xml"),
        Triple("rId2", "theme", "theme/theme1.xml"),
        *slides.indices.map { Triple("rId${it + 3}", "slide", "slides/slide${it + 1}.xml") }.toTypedArray(),
    )
    entries += "ppt/theme/theme1.xml" to THEME
    entries += "ppt/slideMasters/slideMaster1.xml" to MASTER
    entries += "ppt/slideMasters/_rels/slideMaster1.xml.rels" to rels(
        Triple("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml"),
        Triple("rId2", "slideLayout", "../slideLayouts/slideLayout2.xml"),
        Triple("rId3", "theme", "../theme/theme1.xml"),
    )
    entries += "ppt/slideLayouts/slideLayout1.xml" to LAYOUT_TITLE
    entries += "ppt/slideLayouts/slideLayout2.xml" to LAYOUT_CONTENT
    for (n in 1..2) entries += "ppt/slideLayouts/_rels/slideLayout$n.xml.rels" to rels(Triple("rId1", "slideMaster", "../slideMasters/slideMaster1.xml"))
    for ((i, slide) in slides.withIndex()) {
        entries += "ppt/slides/slide${i + 1}.xml" to slideXml(slide)
        entries += "ppt/slides/_rels/slide${i + 1}.xml.rels" to rels(Triple("rId1", "slideLayout", if (slide.isTitle) "../slideLayouts/slideLayout1.xml" else "../slideLayouts/slideLayout2.xml"))
    }
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
